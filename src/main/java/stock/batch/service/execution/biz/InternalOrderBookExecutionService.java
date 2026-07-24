package stock.batch.service.execution.biz;

import jakarta.annotation.PostConstruct;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.batch.common.support.StockPriceRedisPublisher;
import stock.batch.service.automarket.biz.AutoParticipantFundingBudgetService;
import stock.batch.service.batch.execution.model.OrderBookHoldingRow;
import stock.batch.service.batch.execution.model.OrderBookMatchCandidate;
import stock.batch.service.batch.execution.model.OrderBookOrderRow;
import stock.batch.service.batch.execution.reader.OrderBookExecutionReader;
import stock.batch.service.batch.execution.writer.OrderBookExecutionWriter;
import stock.batch.service.batch.execution.writer.OrderBookPriceWriter;
import stock.batch.service.execution.lock.OrderBookSymbolLock;
import stock.batch.service.execution.queue.OrderBookReadySymbolQueue;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;

@Slf4j
@Service
public class InternalOrderBookExecutionService {

    private static final int MAX_SCAN_LIMIT = 5_000;
    private static final int MAX_BUY_CANDIDATE_SCAN_LIMIT = 100;
    private static final int MAX_SYMBOL_CHUNK_LIMIT = 50;
    private static final long MAX_SYMBOL_CHUNK_DURATION_MILLIS = 1_000L;
    private static final int MAX_READY_SYMBOL_FALLBACK_SCAN_LIMIT = 100;
    private static final int MAX_DEADLOCK_RETRY_ATTEMPTS = 10;
    private static final long MAX_DEADLOCK_RETRY_BACKOFF_MILLIS = 1_000L;
    private static final long MAX_SLOW_SYMBOL_LOG_THRESHOLD_MILLIS = 60_000L;

    private final ExecutionCostCalculator executionCostCalculator;
    private final OrderBookExecutionReader orderBookExecutionReader;
    private final OrderBookExecutionWriter orderBookExecutionWriter;
    private final OrderBookPriceWriter orderBookPriceWriter;
    private final StockPriceRedisPublisher priceRedisPublisher;
    private final MarketSessionFenceService marketSessionFenceService;
    private final TransactionTemplate transactionTemplate;
    private final OrderBookSymbolLock orderBookSymbolLock;
    private final OrderBookReadySymbolQueue readySymbolQueue;
    private final ExecutionAccountDaySummaryAccumulator executionAccountDaySummaryAccumulator;
    private final AutoParticipantFundingBudgetService fundingBudgetService;
    private stock.batch.service.automarket.biz.RecentMarketActivityTracker recentMarketActivityTracker;
    private stock.batch.service.automarket.biz.AutoParticipantPositionActivityTracker positionActivityTracker;

    @Autowired(required = false)
    void setRecentMarketActivityTracker(
            stock.batch.service.automarket.biz.RecentMarketActivityTracker recentMarketActivityTracker
    ) {
        this.recentMarketActivityTracker = recentMarketActivityTracker;
    }

    @Autowired(required = false)
    void setPositionActivityTracker(
            stock.batch.service.automarket.biz.AutoParticipantPositionActivityTracker positionActivityTracker
    ) {
        this.positionActivityTracker = positionActivityTracker;
    }

    public InternalOrderBookExecutionService(
            ExecutionCostCalculator executionCostCalculator,
            OrderBookExecutionReader orderBookExecutionReader,
            OrderBookExecutionWriter orderBookExecutionWriter,
            OrderBookPriceWriter orderBookPriceWriter,
            StockPriceRedisPublisher priceRedisPublisher,
            MarketSessionFenceService marketSessionFenceService,
            TransactionTemplate transactionTemplate,
            OrderBookSymbolLock orderBookSymbolLock,
            OrderBookReadySymbolQueue readySymbolQueue,
            ExecutionAccountDaySummaryAccumulator executionAccountDaySummaryAccumulator,
            AutoParticipantFundingBudgetService fundingBudgetService
    ) {
        this.executionCostCalculator = executionCostCalculator;
        this.orderBookExecutionReader = orderBookExecutionReader;
        this.orderBookExecutionWriter = orderBookExecutionWriter;
        this.orderBookPriceWriter = orderBookPriceWriter;
        this.priceRedisPublisher = priceRedisPublisher;
        this.marketSessionFenceService = marketSessionFenceService;
        this.transactionTemplate = transactionTemplate;
        this.orderBookSymbolLock = orderBookSymbolLock;
        this.readySymbolQueue = readySymbolQueue;
        this.executionAccountDaySummaryAccumulator = executionAccountDaySummaryAccumulator;
        this.fundingBudgetService = fundingBudgetService;
    }

    @Value("${stock.batch.execution.scan-limit:300}")
    private int scanLimit;

    @Value("${stock.batch.execution.buy-candidate-scan-limit:20}")
    private int buyCandidateScanLimit;

    @Value("${stock.batch.execution.symbol-chunk-limit:5}")
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

    @PostConstruct
    void validateVolumeConfiguration() {
        validateRange("scan-limit", scanLimit, 1, MAX_SCAN_LIMIT);
        validateRange("buy-candidate-scan-limit", buyCandidateScanLimit, 1, MAX_BUY_CANDIDATE_SCAN_LIMIT);
        validateRange("symbol-chunk-limit", symbolChunkLimit, 1, MAX_SYMBOL_CHUNK_LIMIT);
        validateRange(
                "symbol-chunk-max-duration-ms",
                symbolChunkMaxDurationMillis,
                1L,
                MAX_SYMBOL_CHUNK_DURATION_MILLIS
        );
        validateRange(
                "ready-symbol-fallback-scan-limit",
                readySymbolFallbackScanLimit,
                1,
                MAX_READY_SYMBOL_FALLBACK_SCAN_LIMIT
        );
        validateRange("deadlock-retry-max-attempts", deadlockRetryMaxAttempts, 1, MAX_DEADLOCK_RETRY_ATTEMPTS);
        validateRange(
                "deadlock-retry-backoff-ms",
                deadlockRetryBackoffMillis,
                0L,
                MAX_DEADLOCK_RETRY_BACKOFF_MILLIS
        );
        validateRange(
                "slow-symbol-log-threshold-ms",
                slowSymbolLogThresholdMillis,
                1L,
                MAX_SLOW_SYMBOL_LOG_THRESHOLD_MILLIS
        );
    }

    public int executeEligibleOrders() {
        return executeEligibleOrders(true);
    }

    public int executeReadyOrders() {
        return executeEligibleOrders(false);
    }

    private int executeEligibleOrders(boolean allowDatabaseFallback) {
        int maxMatches = scanLimit;
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
                    // A zero-match chunk already proved that its lock-free candidate was absent
                    // or became invalid under exact locks. Rechecking the same symbol immediately
                    // would duplicate the candidate query and can keep an unmatchable symbol hot
                    // in Redis. Successful chunks alone need a follow-up check to drain more pairs.
                    if (matchCount > 0) {
                        requeueIfStillExecutable(symbol);
                    }
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
                Optional<OrderBookMatchCandidate> candidate = findMatchCandidate(symbol);
                if (candidate.isEmpty()) {
                    return false;
                }
                Boolean matched = transactionTemplate.execute(
                        status -> matchSelectedCandidate(symbol, candidate.get())
                );
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

    private Optional<OrderBookMatchCandidate> findMatchCandidate(String symbol) {
        int candidateLimit = Math.max(1, Math.min(scanLimit, buyCandidateScanLimit));
        // Keep the overwhelmingly common no-match probe outside a business transaction. This
        // avoids both a session-fence lookup and an otherwise empty COMMIT when prices do not
        // cross. The selected pair is stale-safe because matchSelectedCandidate locks and
        // revalidates every exact row before applying any mutation.
        return orderBookExecutionReader.findBestMatchCandidate(symbol, candidateLimit);
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

    private boolean matchSelectedCandidate(String symbol, OrderBookMatchCandidate selectedCandidate) {
        Optional<MarketSessionFenceService.MarketSessionApproval> sessionApproval =
                marketSessionFenceService.lockOpenOrderBookFences(List.of(symbol));
        if (sessionApproval.isEmpty()) {
            return false;
        }
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
        return matchOrders(
                buyOrder,
                sellOrder,
                sellHolding,
                sessionApproval.get().businessEffectiveAt()
        );
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
            OrderBookHoldingRow sellHolding,
            LocalDateTime executedAt
    ) {
        long quantity = Math.min(buyOrder.remainingQuantity(), sellOrder.remainingQuantity());
        if (quantity <= 0) {
            return false;
        }

        BigDecimal executionPrice = resolveExecutionPrice(buyOrder, sellOrder);
        if (executionPrice == null) {
            return false;
        }
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
        runAfterCommit(() -> {
            priceRedisPublisher.publish(buyOrder.symbol(), executionPrice, executedAt, OrderBookPriceWriter.PROVIDER);
            executionAccountDaySummaryAccumulator.recordBuy(
                    buyOrder.accountId(),
                    quantity,
                    buyAmounts,
                    executedAt
            );
            executionAccountDaySummaryAccumulator.recordSell(
                    sellOrder.accountId(),
                    quantity,
                    sellAmounts,
                    executedAt
            );
            if (recentMarketActivityTracker != null) {
                recentMarketActivityTracker.record(
                        buyOrder.symbol(),
                        quantity,
                        buyOrder.accountId(),
                        sellOrder.accountId(),
                        executedAt
                );
            }
            if (positionActivityTracker != null) {
                positionActivityTracker.record(
                        buyOrder.symbol(),
                        quantity,
                        buyOrder.accountId(),
                        sellOrder.accountId(),
                        executedAt
                );
            }
        });
        return true;
    }

    private void runAfterCommit(Runnable action) {
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
        if (buyOrder.limitPrice() == null) {
            return sellOrder.limitPrice();
        }
        if (sellOrder.limitPrice() == null) {
            return buyOrder.limitPrice();
        }
        int receivedTimeOrder = buyOrder.createdAt().compareTo(sellOrder.createdAt());
        if (receivedTimeOrder < 0) {
            return buyOrder.limitPrice();
        }
        if (receivedTimeOrder > 0) {
            return sellOrder.limitPrice();
        }
        return buyOrder.id() < sellOrder.id() ? buyOrder.limitPrice() : sellOrder.limitPrice();
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
        if (order.fundingBudgetType() != null) {
            fundingBudgetService.consumeOrderBudget(
                    order.id(),
                    reservedForMatchedQuantity,
                    actualCost,
                    executedAt
            );
        }
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
        if (order.fundingBudgetType() != null) {
            fundingBudgetService.releaseCancelledOrderBudgets(List.of(order.id()), rejectedAt);
        }
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

    private void validateRange(String propertyName, long value, long minimum, long maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalStateException(
                    "stock.batch.execution.%s must be between %d and %d: %d"
                            .formatted(propertyName, minimum, maximum, value)
            );
        }
    }

}
