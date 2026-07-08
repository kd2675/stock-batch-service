package stock.batch.service.marketclose.biz;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import stock.batch.service.batch.marketclose.model.MarketCloseOrderRow;
import stock.batch.service.batch.marketclose.writer.MarketCloseRolloverWriter;
import stock.batch.service.execution.lock.OrderBookSymbolLock;
import stock.batch.service.simulation.SimulationClockService;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketCloseRolloverService {

    private static final String BUY = "BUY";
    private static final String SELL = "SELL";

    private final MarketCloseRolloverWriter writer;
    private final SimulationClockService simulationClockService;
    private final OrderBookSymbolLock orderBookSymbolLock;
    private final TransactionTemplate transactionTemplate;

    @Value("${stock.batch.market-close.deadlock-retry-max-attempts:5}")
    private int deadlockRetryMaxAttempts = 5;

    @Value("${stock.batch.market-close.deadlock-retry-backoff-ms:50}")
    private long deadlockRetryBackoffMs = 50;

    public int rolloverClosingPrices() {
        LocalDate simulationTradeDate = simulationClockService.currentDate();
        LocalDateTime closedAt = simulationClockService.currentMarketDateTime();
        return rolloverClosingPrices(null, simulationTradeDate, closedAt);
    }

    public int rolloverClosingPrices(String symbol) {
        return rolloverClosingPrices(symbol, simulationClockService.currentDate(), simulationClockService.currentMarketDateTime());
    }

    public int rolloverClosingPrices(LocalDate simulationTradeDate, LocalDateTime closedAt) {
        return rolloverClosingPrices(null, simulationTradeDate, closedAt);
    }

    public boolean hasCompletedFullCloseRun(LocalDate businessDate) {
        if (businessDate == null) {
            return false;
        }
        return writer.hasCompletedFullCloseRun(businessDate);
    }

    private int rolloverClosingPrices(String symbol, LocalDate simulationTradeDate, LocalDateTime closedAt) {
        if (simulationTradeDate == null) {
            throw new IllegalArgumentException("simulationTradeDate is required");
        }
        if (closedAt == null) {
            throw new IllegalArgumentException("closedAt is required");
        }
        String normalizedSymbol = normalizeSymbol(symbol);
        List<OrderBookSymbolLock.LockHandle> locks = acquireCloseLocks(normalizedSymbol);
        if (locks == null) {
            throw new CannotAcquireLockException("Market close rollover skipped because order-book symbol lock is busy");
        }
        try {
            return runIntInTransactionWithDeadlockRetry(
                    closeRetryLabel(normalizedSymbol),
                    () -> rolloverClosingPricesLocked(normalizedSymbol, simulationTradeDate, closedAt)
            );
        } finally {
            releaseLocks(locks);
        }
    }

    private int rolloverClosingPricesLocked(String normalizedSymbol, LocalDate simulationTradeDate, LocalDateTime closedAt) {
        long closeRunId = writer.createCloseRun(normalizedSymbol, simulationTradeDate, closedAt);
        int cancelledOrderCount = cancelOpenOrderBookOrdersLocked(normalizedSymbol, closedAt);
        int holdingSnapshotCount = writer.snapshotHoldings(closeRunId, normalizedSymbol, closedAt);
        int symbolSnapshotCount = writer.snapshotOrderBookDailySymbols(
                closeRunId,
                normalizedSymbol,
                simulationTradeDate,
                closedAt,
                simulationTradeDate.atStartOfDay(),
                simulationTradeDate.plusDays(1).atStartOfDay()
        );
        int priceRolloverCount = writer.rolloverClosingPrices(normalizedSymbol);
        writer.completeCloseRun(
                closeRunId,
                cancelledOrderCount,
                holdingSnapshotCount,
                priceRolloverCount,
                simulationClockService.currentMarketDateTime()
        );
        return cancelledOrderCount + priceRolloverCount + holdingSnapshotCount + symbolSnapshotCount;
    }

    public int cancelOpenOrderBookOrders(String symbol) {
        String normalizedSymbol = normalizeSymbol(symbol);
        if (normalizedSymbol == null) {
            throw new IllegalArgumentException("symbol is required");
        }
        List<OrderBookSymbolLock.LockHandle> locks = acquireCloseLocks(normalizedSymbol);
        if (locks == null) {
            return 0;
        }
        try {
            return runIntInTransactionWithDeadlockRetry(
                    "open-order-cancel:" + normalizedSymbol,
                    () -> cancelOpenOrderBookOrdersLocked(normalizedSymbol, simulationClockService.currentMarketDateTime())
            );
        } finally {
            releaseLocks(locks);
        }
    }

    private int cancelOpenOrderBookOrdersLocked(String symbol, LocalDateTime closedAt) {
        List<MarketCloseOrderRow> orders = writer.findOpenOrderBookOrdersForUpdate(symbol);
        List<MarketCloseOrderRow> cancelledOrders = new ArrayList<>();
        for (MarketCloseOrderRow order : orders) {
            if (writer.cancelOrder(order.id(), closedAt)) {
                cancelledOrders.add(order);
            }
        }
        creditCancelledBuyReservations(cancelledOrders, closedAt);
        releaseCancelledSellReservations(cancelledOrders, closedAt);
        return cancelledOrders.size();
    }

    private void creditCancelledBuyReservations(List<MarketCloseOrderRow> cancelledOrders, LocalDateTime closedAt) {
        Map<Long, BigDecimal> reservedCashByAccount = new TreeMap<>();
        for (MarketCloseOrderRow order : cancelledOrders) {
            if (!BUY.equals(order.side()) || order.reservedCash().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            reservedCashByAccount.merge(order.accountId(), order.reservedCash(), BigDecimal::add);
        }
        reservedCashByAccount.forEach((accountId, reservedCash) -> writer.creditCash(accountId, reservedCash, closedAt));
    }

    private void releaseCancelledSellReservations(List<MarketCloseOrderRow> cancelledOrders, LocalDateTime closedAt) {
        Map<SellReservationKey, Long> reservedQuantityByHolding = new TreeMap<>(
                Comparator.comparingLong(SellReservationKey::accountId)
                        .thenComparing(SellReservationKey::symbol)
        );
        for (MarketCloseOrderRow order : cancelledOrders) {
            if (!SELL.equals(order.side()) || order.remainingQuantity() <= 0) {
                continue;
            }
            reservedQuantityByHolding.merge(
                    new SellReservationKey(order.accountId(), order.symbol()),
                    order.remainingQuantity(),
                    Long::sum
            );
        }
        reservedQuantityByHolding.forEach((key, quantity) ->
                writer.releaseReservedSellQuantity(key.accountId(), key.symbol(), quantity, closedAt)
        );
    }

    private List<OrderBookSymbolLock.LockHandle> acquireCloseLocks(String symbol) {
        List<String> lockSymbols = writer.findCloseLockSymbols(symbol);
        if (lockSymbols.isEmpty()) {
            return List.of();
        }
        List<OrderBookSymbolLock.LockHandle> locks = new ArrayList<>();
        for (String lockSymbol : lockSymbols) {
            var lock = orderBookSymbolLock.tryLock(lockSymbol);
            if (lock.isEmpty()) {
                releaseLocks(locks);
                log.warn("Market close rollover skipped because order-book symbol is busy: symbol={}", lockSymbol);
                return null;
            }
            locks.add(lock.get());
        }
        return locks;
    }

    private void releaseLocks(List<OrderBookSymbolLock.LockHandle> locks) {
        for (int index = locks.size() - 1; index >= 0; index--) {
            locks.get(index).close();
        }
    }

    private int runIntInTransaction(Supplier<Integer> action) {
        Integer result = transactionTemplate.execute(status -> action.get());
        return result == null ? 0 : result;
    }

    private int runIntInTransactionWithDeadlockRetry(String workName, Supplier<Integer> action) {
        int attempts = Math.max(1, deadlockRetryMaxAttempts);
        CannotAcquireLockException lastException = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return runIntInTransaction(action);
            } catch (CannotAcquireLockException ex) {
                lastException = ex;
                if (attempt >= attempts) {
                    break;
                }
                sleepBeforeRetry(workName, attempt, ex);
            }
        }
        throw lastException;
    }

    private void sleepBeforeRetry(String workName, int attempt, CannotAcquireLockException ex) {
        long backoffMillis = Math.max(0, deadlockRetryBackoffMs) * attempt;
        log.warn(
                "Market close rollover deadlock retry: work={}, attempt={}, backoffMs={}, reason={}",
                workName,
                attempt,
                backoffMillis,
                ex.getMessage()
        );
        if (backoffMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(backoffMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during market close rollover deadlock retry", interrupted);
        }
    }

    private String closeRetryLabel(String symbol) {
        return symbol == null ? "market-close-rollover:all" : "market-close-rollover:" + symbol;
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        return symbol.trim().toUpperCase();
    }

    private record SellReservationKey(long accountId, String symbol) {
    }
}
