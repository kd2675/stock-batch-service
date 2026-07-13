package stock.batch.service.execution.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.batch.common.support.StockPriceRedisPublisher;
import stock.batch.service.batch.execution.model.OrderBookHoldingRow;
import stock.batch.service.batch.execution.model.OrderBookMatchCandidate;
import stock.batch.service.batch.execution.model.OrderBookOrderRow;
import stock.batch.service.batch.execution.reader.OrderBookExecutionReader;
import stock.batch.service.batch.execution.writer.OrderBookExecutionWriter;
import stock.batch.service.batch.execution.writer.OrderBookPriceWriter;
import stock.batch.service.execution.lock.OrderBookSymbolLock;
import stock.batch.service.execution.queue.OrderBookReadySymbolQueue;
import stock.batch.service.simulation.SimulationClockService;

@Slf4j
@Service
public class InternalOrderBookExecutionService {

    private final ExecutionCostCalculator executionCostCalculator;
    private final OrderBookExecutionReader orderBookExecutionReader;
    private final OrderBookExecutionWriter orderBookExecutionWriter;
    private final OrderBookPriceWriter orderBookPriceWriter;
    private final StockPriceRedisPublisher priceRedisPublisher;
    private final SimulationClockService simulationClockService;
    private final TransactionTemplate transactionTemplate;
    private final OrderBookSymbolLock orderBookSymbolLock;
    private final OrderBookReadySymbolQueue readySymbolQueue;

    public InternalOrderBookExecutionService(
            ExecutionCostCalculator executionCostCalculator,
            OrderBookExecutionReader orderBookExecutionReader,
            OrderBookExecutionWriter orderBookExecutionWriter,
            OrderBookPriceWriter orderBookPriceWriter,
            StockPriceRedisPublisher priceRedisPublisher,
            SimulationClockService simulationClockService,
            TransactionTemplate transactionTemplate,
            OrderBookSymbolLock orderBookSymbolLock,
            OrderBookReadySymbolQueue readySymbolQueue
    ) {
        this.executionCostCalculator = executionCostCalculator;
        this.orderBookExecutionReader = orderBookExecutionReader;
        this.orderBookExecutionWriter = orderBookExecutionWriter;
        this.orderBookPriceWriter = orderBookPriceWriter;
        this.priceRedisPublisher = priceRedisPublisher;
        this.simulationClockService = simulationClockService;
        this.transactionTemplate = transactionTemplate;
        this.orderBookSymbolLock = orderBookSymbolLock;
        this.readySymbolQueue = readySymbolQueue;
    }

    @Value("${stock.batch.execution.scan-limit:300}")
    private int scanLimit;

    @Value("${stock.batch.execution.buy-candidate-scan-limit:20}")
    private int buyCandidateScanLimit;

    @Value("${stock.batch.execution.symbol-chunk-limit:50}")
    private int symbolChunkLimit;

    @Value("${stock.batch.execution.symbol-chunk-max-duration-ms:500}")
    private long symbolChunkMaxDurationMillis;

    @Value("${stock.batch.execution.ready-symbol-fallback-scan-limit:8}")
    private int readySymbolFallbackScanLimit;

    @Value("${stock.batch.execution.deadlock-retry-max-attempts:3}")
    private int deadlockRetryMaxAttempts;

    @Value("${stock.batch.execution.deadlock-retry-backoff-ms:50}")
    private long deadlockRetryBackoffMillis;

    @Value("${stock.batch.execution.slow-symbol-log-threshold-ms:1000}")
    private long slowSymbolLogThresholdMillis;

    public int executeEligibleOrders() {
        return executeEligibleOrders(true);
    }

    public int executeReadyOrders() {
        return executeEligibleOrders(false);
    }

    private int executeEligibleOrders(boolean allowDatabaseFallback) {
        int maxMatches = Math.max(0, scanLimit);
        if (maxMatches <= 0) {
            return 0;
        }
        AtomicInteger remainingMatches = new AtomicInteger(maxMatches);
        return executeNextReadySymbol(remainingMatches, allowDatabaseFallback);
    }

    private int executeNextReadySymbol(AtomicInteger remainingMatches, boolean allowDatabaseFallback) {
        Set<String> attemptedSymbols = new LinkedHashSet<>();
        while (remainingMatches.get() > 0) {
            String symbol = findNextReadySymbol(allowDatabaseFallback);
            if (symbol == null || !attemptedSymbols.add(symbol)) {
                return 0;
            }
            int matchCount = executePolledSymbol(symbol, remainingMatches);
            if (matchCount > 0) {
                return matchCount;
            }
        }
        return 0;
    }

    private String findNextReadySymbol(boolean allowDatabaseFallback) {
        Optional<String> queuedSymbol = readySymbolQueue.poll();
        if (queuedSymbol.isPresent() || !allowDatabaseFallback) {
            return queuedSymbol.orElse(null);
        }
        return Optional.ofNullable(
                orderBookExecutionReader.findExecutableSymbolCandidates(
                        Math.max(1, readySymbolFallbackScanLimit)
                )
        )
                .filter(candidates -> !candidates.isEmpty())
                .flatMap(candidates -> {
                    readySymbolQueue.enqueueAll(candidates);
                    return readySymbolQueue.poll().or(() -> Optional.of(candidates.getFirst()));
                })
                .orElse(null);
    }

    private int executePolledSymbol(String symbol, AtomicInteger remainingMatches) {
        return orderBookSymbolLock.tryLock(symbol)
                .map(lock -> {
                    int matchCount;
                    try (lock) {
                        matchCount = executeLockedSymbolChunk(symbol, remainingMatches);
                    } catch (CannotAcquireLockException ex) {
                        readySymbolQueue.enqueue(symbol);
                        log.warn(
                                "Order book symbol execution skipped after lock retry exhaustion: symbol={}, reason={}",
                                symbol,
                                ex.getMessage()
                        );
                        return 0;
                    } catch (RuntimeException ex) {
                        readySymbolQueue.enqueue(symbol);
                        throw ex;
                    }
                    requeueIfStillExecutable(symbol);
                    return matchCount;
                })
                .orElseGet(() -> {
                    readySymbolQueue.enqueue(symbol);
                    return 0;
                });
    }

    private void requeueIfStillExecutable(String symbol) {
        try {
            if (orderBookExecutionReader.hasExecutablePair(symbol)) {
                readySymbolQueue.enqueue(symbol);
            }
        } catch (RuntimeException ex) {
            log.warn(
                    "Order book ready symbol requeue check failed: symbol={}, reason={}",
                    symbol,
                    ex.getMessage()
            );
        }
    }

    private int executeLockedSymbolChunk(String symbol, AtomicInteger remainingMatches) {
        long startedNanos = System.nanoTime();
        int localMatchCount = 0;
        int maxSymbolMatches = Math.max(1, symbolChunkLimit);
        long maxDurationNanos = Math.max(1L, symbolChunkMaxDurationMillis) * 1_000_000L;
        try {
            while (localMatchCount < maxSymbolMatches
                    && System.nanoTime() - startedNanos < maxDurationNanos
                    && reserveMatchSlot(remainingMatches)) {
                try {
                    if (!matchNextWithRetry(symbol)) {
                        remainingMatches.incrementAndGet();
                        return localMatchCount;
                    }
                    localMatchCount++;
                } catch (RuntimeException ex) {
                    remainingMatches.incrementAndGet();
                    throw ex;
                }
            }
            return localMatchCount;
        } finally {
            logSlowSymbolChunk(symbol, localMatchCount, startedNanos);
        }
    }

    private void logSlowSymbolChunk(String symbol, int matchCount, long startedNanos) {
        long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L;
        if (elapsedMillis < Math.max(1L, slowSymbolLogThresholdMillis)) {
            return;
        }
        log.info(
                "Order book symbol execution chunk completed: symbol={}, matchCount={}, elapsedMs={}",
                symbol,
                matchCount,
                elapsedMillis
        );
    }

    private boolean reserveMatchSlot(AtomicInteger remainingMatches) {
        while (true) {
            int current = remainingMatches.get();
            if (current <= 0) {
                return false;
            }
            if (remainingMatches.compareAndSet(current, current - 1)) {
                return true;
            }
        }
    }

    private boolean matchNextWithRetry(String symbol) {
        int attempts = Math.max(1, deadlockRetryMaxAttempts);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                Boolean matched = transactionTemplate.execute(status -> matchNext(symbol));
                return Boolean.TRUE.equals(matched);
            } catch (CannotAcquireLockException ex) {
                if (attempt >= attempts) {
                    throw ex;
                }
                sleepBeforeRetry(symbol, attempt, ex);
            }
        }
        return false;
    }

    private void sleepBeforeRetry(String symbol, int attempt, CannotAcquireLockException ex) {
        long backoffMillis = Math.max(0L, deadlockRetryBackoffMillis) * attempt;
        log.warn(
                "Order book match retry after lock acquisition failure: symbol={}, attempt={}, backoffMs={}, reason={}",
                symbol,
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
            throw new IllegalStateException("Interrupted while retrying order book match", interrupted);
        }
    }

    private boolean matchNext(String symbol) {
        int candidateLimit = Math.max(1, Math.min(scanLimit, buyCandidateScanLimit));
        Optional<OrderBookMatchCandidate> candidate = orderBookExecutionReader.findBestMatchCandidate(symbol, candidateLimit);
        if (candidate.isEmpty()) {
            return false;
        }
        OrderBookMatchCandidate selectedCandidate = candidate.get();
        orderBookExecutionWriter.lockAccountsForUpdate(
                selectedCandidate.buyAccountId(),
                selectedCandidate.sellAccountId()
        );
        OrderBookHoldingRow sellHolding = orderBookExecutionReader.findHoldingForUpdate(
                selectedCandidate.sellAccountId(),
                symbol
        );
        List<OrderBookOrderRow> lockedOrders = orderBookExecutionReader.findMatchOrdersForUpdate(selectedCandidate);
        if (lockedOrders.size() != 2) {
            return false;
        }
        OrderBookOrderRow buyOrder = findLockedOrder(lockedOrders, selectedCandidate.buyOrderId(), "BUY");
        OrderBookOrderRow sellOrder = findLockedOrder(lockedOrders, selectedCandidate.sellOrderId(), "SELL");
        if (!isExecutablePair(symbol, buyOrder, sellOrder)) {
            return false;
        }
        return matchOrders(buyOrder, sellOrder, sellHolding);
    }

    private OrderBookOrderRow findLockedOrder(List<OrderBookOrderRow> orders, long orderId, String side) {
        return orders.stream()
                .filter(order -> order.id() == orderId && side.equals(order.side()))
                .findFirst()
                .orElse(null);
    }

    private boolean isExecutablePair(String symbol, OrderBookOrderRow buyOrder, OrderBookOrderRow sellOrder) {
        if (buyOrder == null || sellOrder == null) {
            return false;
        }
        if (!symbol.equals(buyOrder.symbol()) || !symbol.equals(sellOrder.symbol())) {
            return false;
        }
        if (buyOrder.accountId() == sellOrder.accountId()) {
            return false;
        }
        if ("MARKET".equals(buyOrder.orderType())) {
            return "LIMIT".equals(sellOrder.orderType()) && sellOrder.limitPrice() != null;
        }
        if (!"LIMIT".equals(buyOrder.orderType()) || buyOrder.limitPrice() == null) {
            return false;
        }
        return "MARKET".equals(sellOrder.orderType())
                || ("LIMIT".equals(sellOrder.orderType())
                && sellOrder.limitPrice() != null
                && sellOrder.limitPrice().compareTo(buyOrder.limitPrice()) <= 0);
    }

    private boolean matchOrders(
            OrderBookOrderRow buyOrder,
            OrderBookOrderRow sellOrder,
            OrderBookHoldingRow sellHolding
    ) {
        long quantity = Math.min(buyOrder.remainingQuantity(), sellOrder.remainingQuantity());
        if (quantity <= 0) {
            return false;
        }

        BigDecimal executionPrice = resolveExecutionPrice(buyOrder, sellOrder);
        if (executionPrice == null) {
            return false;
        }
        LocalDateTime executedAt = simulationClockService.currentMarketDateTime();
        ExecutionCostCalculator.ExecutionAmounts buyAmounts = executionCostCalculator.buy(quantity, executionPrice);
        ExecutionCostCalculator.ExecutionAmounts sellAmounts = resolveSellAmounts(sellHolding, quantity, executionPrice);
        if (sellAmounts == null) {
            rejectSellOrder(sellOrder, executedAt);
            return false;
        }
        if (!hasEnoughBuyCash(buyOrder, quantity, executionPrice, buyAmounts)) {
            rejectBuyOrder(buyOrder, executedAt);
            return false;
        }
        if (!executeSell(sellOrder, quantity, executionPrice, sellAmounts, executedAt)) {
            return false;
        }
        executeBuy(buyOrder, quantity, executionPrice, buyAmounts, executedAt);
        orderBookExecutionWriter.insertExecutions(
                sellOrder,
                quantity,
                executionPrice,
                sellAmounts,
                buyOrder,
                buyAmounts,
                executedAt
        );
        orderBookPriceWriter.updateLastTradePrice(buyOrder.symbol(), executionPrice, executedAt);
        orderBookPriceWriter.insertPriceTick(buyOrder.symbol(), executionPrice, executedAt);
        publishPriceAfterCommit(buyOrder.symbol(), executionPrice, executedAt);
        return true;
    }

    private void publishPriceAfterCommit(String symbol, BigDecimal executionPrice, LocalDateTime executedAt) {
        Runnable action = () -> priceRedisPublisher.publish(symbol, executionPrice, executedAt, OrderBookPriceWriter.PROVIDER);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private BigDecimal resolveExecutionPrice(OrderBookOrderRow buyOrder, OrderBookOrderRow sellOrder) {
        if (sellOrder.limitPrice() != null) {
            return sellOrder.limitPrice();
        }
        return buyOrder.limitPrice();
    }

    private boolean hasEnoughBuyCash(
            OrderBookOrderRow order,
            long quantity,
            BigDecimal executionPrice,
            ExecutionCostCalculator.ExecutionAmounts amounts
    ) {
        BigDecimal reservedForMatchedQuantity = calculateReservedCashToRelease(order, quantity, executionPrice);
        BigDecimal shortfall = amounts.netAmount().subtract(reservedForMatchedQuantity).max(BigDecimal.ZERO);
        if (shortfall.compareTo(BigDecimal.ZERO) <= 0) {
            return true;
        }
        return orderBookExecutionReader.hasEnoughCash(order.accountId(), shortfall);
    }

    private void executeBuy(
            OrderBookOrderRow order,
            long quantity,
            BigDecimal executionPrice,
            ExecutionCostCalculator.ExecutionAmounts amounts,
            LocalDateTime executedAt
    ) {
        BigDecimal reservedForMatchedQuantity = calculateReservedCashToRelease(order, quantity, executionPrice);
        BigDecimal actualCost = amounts.netAmount();
        BigDecimal release = reservedForMatchedQuantity.subtract(actualCost).max(BigDecimal.ZERO);
        BigDecimal shortfall = actualCost.subtract(reservedForMatchedQuantity).max(BigDecimal.ZERO);
        orderBookExecutionWriter.adjustCash(order.accountId(), release.subtract(shortfall), executedAt);
        upsertHolding(order.accountId(), order.symbol(), quantity, amounts.netAmount(), executedAt);
        updateOrderAfterFill(order, quantity, executionPrice, reservedForMatchedQuantity, executedAt);
    }

    private BigDecimal calculateReservedCashToRelease(OrderBookOrderRow order, long quantity, BigDecimal executionPrice) {
        if ("MARKET".equals(order.orderType())) {
            if (order.remainingQuantity() == quantity) {
                return order.reservedCash();
            }
            BigDecimal reservedPerShare = order.reservedCash()
                    .divide(BigDecimal.valueOf(order.remainingQuantity()), 2, RoundingMode.HALF_UP);
            return reservedPerShare.multiply(BigDecimal.valueOf(quantity));
        }
        BigDecimal expectedReserve = order.limitPrice().multiply(BigDecimal.valueOf(quantity));
        return expectedReserve.min(order.reservedCash());
    }

    private ExecutionCostCalculator.ExecutionAmounts resolveSellAmounts(
            OrderBookHoldingRow holding,
            long quantity,
            BigDecimal executionPrice
    ) {
        if (holding == null || holding.quantity() < quantity || holding.reservedQuantity() < quantity) {
            return null;
        }
        return executionCostCalculator.sell(quantity, executionPrice, holding.averagePrice());
    }

    private boolean executeSell(
            OrderBookOrderRow order,
            long quantity,
            BigDecimal executionPrice,
            ExecutionCostCalculator.ExecutionAmounts amounts,
            LocalDateTime executedAt
    ) {
        int updatedRows = orderBookExecutionWriter.reduceReservedSellHolding(order, quantity, executedAt);
        if (updatedRows == 0) {
            rejectSellOrder(order, executedAt);
            return false;
        }
        orderBookExecutionWriter.creditCash(order.accountId(), amounts.netAmount(), executedAt);
        updateOrderAfterFill(order, quantity, executionPrice, BigDecimal.ZERO, executedAt);
        return true;
    }

    private void updateOrderAfterFill(
            OrderBookOrderRow order,
            long fillQuantity,
            BigDecimal executionPrice,
            BigDecimal reservedCashToRelease,
            LocalDateTime executedAt
    ) {
        long nextFilledQuantity = order.filledQuantity() + fillQuantity;
        BigDecimal nextAverageFillPrice = calculateAverageFillPrice(order, fillQuantity, executionPrice);
        BigDecimal nextReservedCash = order.reservedCash().subtract(reservedCashToRelease).max(BigDecimal.ZERO);
        orderBookExecutionWriter.updateOrderAfterFill(
                order,
                nextFilledQuantity,
                nextAverageFillPrice,
                nextReservedCash,
                executedAt
        );
    }

    private void rejectBuyOrder(OrderBookOrderRow order, LocalDateTime rejectedAt) {
        orderBookExecutionWriter.creditCash(order.accountId(), order.reservedCash(), rejectedAt);
        orderBookExecutionWriter.rejectBuyOrder(order, rejectedAt);
    }

    private void rejectSellOrder(OrderBookOrderRow order, LocalDateTime rejectedAt) {
        orderBookExecutionWriter.releaseReservedSellQuantity(order, rejectedAt);
        orderBookExecutionWriter.rejectSellOrder(order, rejectedAt);
    }

    private BigDecimal calculateAverageFillPrice(OrderBookOrderRow order, long fillQuantity, BigDecimal executionPrice) {
        return AverageFillPriceCalculator.calculate(
                order.averageFillPrice(),
                order.filledQuantity(),
                fillQuantity,
                executionPrice
        );
    }

    private void upsertHolding(long accountId, String symbol, long quantity, BigDecimal costAmount, LocalDateTime executedAt) {
        orderBookExecutionWriter.upsertHolding(
                accountId,
                symbol,
                quantity,
                costAmount,
                executedAt
        );
    }

}
