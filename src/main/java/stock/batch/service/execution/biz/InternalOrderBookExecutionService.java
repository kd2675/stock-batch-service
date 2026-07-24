package stock.batch.service.execution.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.automarket.biz.AutoParticipantFundingBudgetService;
import stock.batch.service.batch.common.support.StockPriceRedisPublisher;
import stock.batch.service.batch.execution.model.OrderBookHoldingRow;
import stock.batch.service.batch.execution.model.OrderBookMatchCandidate;
import stock.batch.service.batch.execution.model.OrderBookOrderFillUpdate;
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
    private static final int MAX_STALE_CANDIDATE_RETRY_LIMIT = 20;
    private static final long MIN_READY_SYMBOL_RECONCILIATION_INTERVAL_MILLIS = 100L;
    private static final long MAX_READY_SYMBOL_RECONCILIATION_INTERVAL_MILLIS = 30_000L;
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
    private final MeterRegistry meterRegistry;
    private final Timer candidateLookupTimer;
    private final Timer transactionTimer;
    private final Timer transactionBeginTimer;
    private final Timer transactionBodyTimer;
    private final Timer transactionCompletionTimer;
    private final Timer accountLockTimer;
    private final Timer holdingLockTimer;
    private final Timer orderLockTimer;
    private final Timer ledgerMutationTimer;
    private final Timer symbolChunkTimer;
    private final Timer readySymbolReconciliationScanTimer;
    private final Timer afterCommitCallbackTimer;
    private final AtomicLong nextReadySymbolReconciliationNanos = new AtomicLong();
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
            AutoParticipantFundingBudgetService fundingBudgetService,
            MeterRegistry meterRegistry
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
        this.meterRegistry = meterRegistry;
        this.candidateLookupTimer = meterRegistry.timer("stock.orderbook.execution.candidate.lookup.duration");
        this.transactionTimer = meterRegistry.timer("stock.orderbook.execution.transaction.duration");
        this.transactionBeginTimer = meterRegistry.timer(
                "stock.orderbook.execution.transaction.begin.overhead"
        );
        this.transactionBodyTimer = meterRegistry.timer("stock.orderbook.execution.transaction.body.duration");
        this.transactionCompletionTimer = meterRegistry.timer(
                "stock.orderbook.execution.transaction.completion.overhead"
        );
        this.accountLockTimer = meterRegistry.timer("stock.orderbook.execution.account.lock.duration");
        this.holdingLockTimer = meterRegistry.timer("stock.orderbook.execution.holding.lock.duration");
        this.orderLockTimer = meterRegistry.timer("stock.orderbook.execution.order.lock.duration");
        this.ledgerMutationTimer = meterRegistry.timer("stock.orderbook.execution.ledger.mutation.duration");
        this.symbolChunkTimer = meterRegistry.timer("stock.orderbook.execution.symbol.chunk.duration");
        this.readySymbolReconciliationScanTimer = meterRegistry.timer(
                "stock.orderbook.execution.ready.queue.reconciliation.scan.duration"
        );
        this.afterCommitCallbackTimer = meterRegistry.timer(
                "stock.orderbook.execution.after.commit.callback.duration"
        );
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

    @Value("${stock.batch.execution.stale-candidate-retry-limit:3}")
    private int staleCandidateRetryLimit;

    @Value("${stock.batch.execution.ready-symbol-reconciliation-enabled:true}")
    private boolean readySymbolReconciliationEnabled;

    @Value("${stock.batch.execution.ready-symbol-reconciliation-interval-ms:1000}")
    private long readySymbolReconciliationIntervalMillis;

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
        validateRange(
                "stale-candidate-retry-limit",
                staleCandidateRetryLimit,
                1,
                MAX_STALE_CANDIDATE_RETRY_LIMIT
        );
        validateRange(
                "ready-symbol-reconciliation-interval-ms",
                readySymbolReconciliationIntervalMillis,
                MIN_READY_SYMBOL_RECONCILIATION_INTERVAL_MILLIS,
                MAX_READY_SYMBOL_RECONCILIATION_INTERVAL_MILLIS
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

    public int reconcileReadySymbolsIfDue() {
        if (!readySymbolReconciliationEnabled || !claimReadySymbolReconciliationWindow()) {
            return 0;
        }
        Optional<OrderBookReadySymbolQueue.ReconciliationLease> lease =
                readySymbolQueue.tryAcquireReconciliationLease(
                        Math.max(1L, readySymbolReconciliationIntervalMillis)
                );
        if (lease.isEmpty()) {
            meterRegistry.counter(
                    "stock.orderbook.execution.ready.queue.reconciliations",
                    "result",
                    "lease-miss"
            ).increment();
            return 0;
        }
        try (OrderBookReadySymbolQueue.ReconciliationLease reconciliationLease = lease.get()) {
            int limit = Math.max(1, readySymbolFallbackScanLimit);
            String afterSymbol = reconciliationLease.cursor();
            List<String> candidates = readySymbolReconciliationScanTimer.record(
                    () -> orderBookExecutionReader.findOpenOrderBookSymbolsAfter(
                            afterSymbol,
                            limit
                    )
            );
            int enqueuedCount = readySymbolQueue.reconcileAll(candidates);
            String nextCursor = candidates.size() < limit ? "" : candidates.getLast();
            if (!reconciliationLease.updateCursor(nextCursor)) {
                meterRegistry.counter(
                        "stock.orderbook.execution.ready.queue.reconciliations",
                        "result",
                        "lease-lost"
                ).increment();
                return enqueuedCount;
            }
            meterRegistry.counter(
                    "stock.orderbook.execution.ready.queue.reconciliations",
                    "result",
                    candidates.isEmpty() ? "drained" : "completed"
            ).increment();
            meterRegistry.summary(
                    "stock.orderbook.execution.ready.queue.reconciliation.candidates"
            ).record(candidates.size());
            meterRegistry.summary(
                    "stock.orderbook.execution.ready.queue.reconciliation.enqueued"
            ).record(enqueuedCount);
            return enqueuedCount;
        } catch (RuntimeException ex) {
            meterRegistry.counter(
                    "stock.orderbook.execution.ready.queue.reconciliations",
                    "result",
                    "failure"
            ).increment();
            log.warn("Order book ready symbol reconciliation failed: reason={}", ex.getMessage(), ex);
            return 0;
        }
    }

    private boolean claimReadySymbolReconciliationWindow() {
        long now = System.nanoTime();
        while (true) {
            long nextAllowed = nextReadySymbolReconciliationNanos.get();
            if (now < nextAllowed) {
                return false;
            }
            long nextWindow = now + TimeUnit.MILLISECONDS.toNanos(
                    Math.max(1L, readySymbolReconciliationIntervalMillis)
            );
            if (nextReadySymbolReconciliationNanos.compareAndSet(nextAllowed, nextWindow)) {
                return true;
            }
        }
    }

    private int executeEligibleOrders(boolean allowDatabaseFallback) {
        int maxMatches = scanLimit;
        AtomicInteger remainingMatches = new AtomicInteger(maxMatches);
        return executeNextReadySymbol(remainingMatches, allowDatabaseFallback);
    }

    private int executeNextReadySymbol(AtomicInteger remainingMatches, boolean allowDatabaseFallback) {
        Set<String> attemptedSymbols = new LinkedHashSet<>();
        int symbolAttemptLimit = Math.max(1, readySymbolFallbackScanLimit);
        while (remainingMatches.get() > 0 && attemptedSymbols.size() < symbolAttemptLimit) {
            String symbol = findNextReadySymbol(allowDatabaseFallback);
            if (symbol == null) {
                return 0;
            }
            if (!attemptedSymbols.add(symbol)) {
                readySymbolQueue.enqueue(symbol);
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
                    SymbolChunkResult chunkResult;
                    try (lock) {
                        chunkResult = executeLockedSymbolChunk(symbol, remainingMatches);
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
                    if (chunkResult.shouldRequeue()) {
                        readySymbolQueue.enqueue(symbol);
                    }
                    return chunkResult.matchCount();
                })
                .orElseGet(() -> {
                    readySymbolQueue.enqueue(symbol);
                    return 0;
                });
    }

    private SymbolChunkResult executeLockedSymbolChunk(String symbol, AtomicInteger remainingMatches) {
        long startedNanos = System.nanoTime();
        int localMatchCount = 0;
        int progressCount = 0;
        int staleCandidateCount = 0;
        int maxSymbolMatches = Math.max(1, symbolChunkLimit);
        long maxDurationNanos = Math.max(1L, symbolChunkMaxDurationMillis) * 1_000_000L;
        SymbolChunkTerminationReason terminationReason = null;
        try {
            while (terminationReason == null) {
                if (localMatchCount >= maxSymbolMatches) {
                    terminationReason = SymbolChunkTerminationReason.MATCH_LIMIT;
                    continue;
                }
                if (System.nanoTime() - startedNanos >= maxDurationNanos) {
                    terminationReason = SymbolChunkTerminationReason.TIME_LIMIT;
                    continue;
                }
                if (!reserveMatchSlot(remainingMatches)) {
                    terminationReason = SymbolChunkTerminationReason.GLOBAL_LIMIT;
                    continue;
                }
                try {
                    MatchAttemptResult result = matchNextWithRetry(symbol);
                    recordMatchAttempt(result);
                    if (result != MatchAttemptResult.MATCHED) {
                        remainingMatches.incrementAndGet();
                    }
                    switch (result) {
                        case MATCHED -> {
                            localMatchCount++;
                            progressCount++;
                            staleCandidateCount = 0;
                        }
                        case MUTATED_WITHOUT_MATCH -> {
                            progressCount++;
                            staleCandidateCount = 0;
                        }
                        case STALE_CANDIDATE -> {
                            staleCandidateCount++;
                            if (staleCandidateCount >= Math.max(1, staleCandidateRetryLimit)) {
                                terminationReason = SymbolChunkTerminationReason.STALE_LIMIT;
                            }
                        }
                        case DRAINED -> terminationReason = SymbolChunkTerminationReason.DRAINED;
                        case SESSION_CLOSED -> terminationReason = SymbolChunkTerminationReason.SESSION_CLOSED;
                    }
                } catch (RuntimeException ex) {
                    remainingMatches.incrementAndGet();
                    throw ex;
                }
            }
            SymbolChunkResult result = new SymbolChunkResult(
                    localMatchCount,
                    progressCount,
                    terminationReason
            );
            meterRegistry.counter(
                    "stock.orderbook.execution.symbol.chunk.terminations",
                    "reason",
                    terminationReason.metricValue()
            ).increment();
            meterRegistry.summary("stock.orderbook.execution.symbol.chunk.matches").record(localMatchCount);
            meterRegistry.summary("stock.orderbook.execution.symbol.chunk.progress").record(progressCount);
            return result;
        } finally {
            symbolChunkTimer.record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
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

    private MatchAttemptResult matchNextWithRetry(String symbol) {
        int attempts = Math.max(1, deadlockRetryMaxAttempts);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                Optional<OrderBookMatchCandidate> candidate = findMatchCandidate(symbol);
                if (candidate.isEmpty()) {
                    return MatchAttemptResult.DRAINED;
                }
                return executeCandidateTransaction(symbol, candidate.get());
            } catch (CannotAcquireLockException ex) {
                meterRegistry.counter(
                        "stock.orderbook.execution.match.attempts",
                        "result",
                        "lock-retry"
                ).increment();
                if (attempt >= attempts) {
                    throw ex;
                }
                sleepBeforeRetry(symbol, attempt, ex);
            }
        }
        throw new IllegalStateException("Order-book match retry loop ended without a result");
    }

    private MatchAttemptResult executeCandidateTransaction(
            String symbol,
            OrderBookMatchCandidate candidate
    ) {
        long transactionStartedNanos = System.nanoTime();
        AtomicLong bodyStartedNanos = new AtomicLong();
        AtomicLong bodyFinishedNanos = new AtomicLong();
        try {
            MatchAttemptResult result = transactionTemplate.execute(status -> {
                long startedNanos = System.nanoTime();
                bodyStartedNanos.set(startedNanos);
                try {
                    return matchSelectedCandidate(symbol, candidate);
                } finally {
                    long finishedNanos = System.nanoTime();
                    bodyFinishedNanos.set(finishedNanos);
                    long elapsedNanos = finishedNanos - startedNanos;
                    transactionBodyTimer.record(elapsedNanos, TimeUnit.NANOSECONDS);
                }
            });
            if (result == null) {
                throw new IllegalStateException("Order-book match transaction returned no result");
            }
            return result;
        } finally {
            long transactionFinishedNanos = System.nanoTime();
            long totalNanos = transactionFinishedNanos - transactionStartedNanos;
            transactionTimer.record(totalNanos, TimeUnit.NANOSECONDS);
            long bodyStarted = bodyStartedNanos.get();
            long bodyFinished = bodyFinishedNanos.get();
            if (bodyStarted > 0L && bodyFinished >= bodyStarted) {
                transactionBeginTimer.record(
                        Math.max(0L, bodyStarted - transactionStartedNanos),
                        TimeUnit.NANOSECONDS
                );
                transactionCompletionTimer.record(
                        Math.max(0L, transactionFinishedNanos - bodyFinished),
                        TimeUnit.NANOSECONDS
                );
            } else {
                transactionBeginTimer.record(totalNanos, TimeUnit.NANOSECONDS);
            }
        }
    }

    private Optional<OrderBookMatchCandidate> findMatchCandidate(String symbol) {
        int candidateLimit = Math.max(1, Math.min(scanLimit, buyCandidateScanLimit));
        // Keep the overwhelmingly common no-match probe outside a business transaction. This
        // avoids both a session-fence lookup and an otherwise empty COMMIT when prices do not
        // cross. The selected pair is stale-safe because matchSelectedCandidate locks and
        // revalidates every exact row before applying any mutation.
        return candidateLookupTimer.record(
                () -> orderBookExecutionReader.findBestMatchCandidate(symbol, candidateLimit)
        );
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

    private MatchAttemptResult matchSelectedCandidate(
            String symbol,
            OrderBookMatchCandidate selectedCandidate
    ) {
        Optional<MarketSessionFenceService.MarketSessionApproval> sessionApproval =
                marketSessionFenceService.lockOpenOrderBookFences(List.of(symbol));
        if (sessionApproval.isEmpty()) {
            return MatchAttemptResult.SESSION_CLOSED;
        }
        accountLockTimer.record(() ->
                orderBookExecutionWriter.lockAccountsForUpdate(
                        selectedCandidate.buyAccountId(),
                        selectedCandidate.sellAccountId()
                )
        );
        OrderBookHoldingRow sellHolding = holdingLockTimer.record(() ->
                orderBookExecutionReader.findHoldingForUpdate(
                        selectedCandidate.sellAccountId(),
                        symbol
                )
        );
        List<OrderBookOrderRow> lockedOrders = orderLockTimer.record(
                () -> orderBookExecutionReader.findMatchOrdersForUpdate(selectedCandidate)
        );
        if (lockedOrders.size() != 2) {
            return MatchAttemptResult.STALE_CANDIDATE;
        }
        OrderBookOrderRow buyOrder = findLockedOrder(lockedOrders, selectedCandidate.buyOrderId(), "BUY");
        OrderBookOrderRow sellOrder = findLockedOrder(lockedOrders, selectedCandidate.sellOrderId(), "SELL");
        if (!isExecutablePair(symbol, buyOrder, sellOrder)) {
            return MatchAttemptResult.STALE_CANDIDATE;
        }
        recordSelectedCandidateState(buyOrder, sellOrder, sessionApproval.get().businessEffectiveAt());
        return ledgerMutationTimer.record(() ->
                matchOrders(
                        buyOrder,
                        sellOrder,
                        sellHolding,
                        sessionApproval.get().businessEffectiveAt()
                )
        );
    }

    private void recordSelectedCandidateState(
            OrderBookOrderRow buyOrder,
            OrderBookOrderRow sellOrder,
            LocalDateTime businessEffectiveAt
    ) {
        LocalDateTime oldestCreatedAt = buyOrder.createdAt().isBefore(sellOrder.createdAt())
                ? buyOrder.createdAt()
                : sellOrder.createdAt();
        long oldestAgeMillis = Math.max(
                0L,
                Duration.between(oldestCreatedAt, businessEffectiveAt).toMillis()
        );
        meterRegistry.summary(
                "stock.orderbook.execution.selected.candidate.oldest.age.seconds"
        ).record(oldestAgeMillis / 1_000.0d);
        meterRegistry.summary(
                "stock.orderbook.execution.selected.candidate.quantity"
        ).record(Math.min(buyOrder.remainingQuantity(), sellOrder.remainingQuantity()));
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

    private MatchAttemptResult matchOrders(
            OrderBookOrderRow buyOrder,
            OrderBookOrderRow sellOrder,
            OrderBookHoldingRow sellHolding,
            LocalDateTime executedAt
    ) {
        long quantity = Math.min(buyOrder.remainingQuantity(), sellOrder.remainingQuantity());
        if (quantity <= 0) {
            return MatchAttemptResult.STALE_CANDIDATE;
        }

        BigDecimal executionPrice = resolveExecutionPrice(buyOrder, sellOrder);
        if (executionPrice == null) {
            return MatchAttemptResult.STALE_CANDIDATE;
        }
        ExecutionCostCalculator.ExecutionAmounts buyAmounts = executionCostCalculator.buy(quantity, executionPrice);
        ExecutionCostCalculator.ExecutionAmounts sellAmounts = resolveSellAmounts(sellHolding, quantity, executionPrice);
        if (sellAmounts == null) {
            rejectSellOrder(sellOrder, executedAt);
            return MatchAttemptResult.MUTATED_WITHOUT_MATCH;
        }
        if (!hasEnoughBuyCash(buyOrder, quantity, executionPrice, buyAmounts)) {
            rejectBuyOrder(buyOrder, executedAt);
            return MatchAttemptResult.MUTATED_WITHOUT_MATCH;
        }
        int updatedHoldingRows = orderBookExecutionWriter.reduceReservedSellHolding(
                sellOrder,
                quantity,
                executedAt
        );
        if (updatedHoldingRows == 0) {
            rejectSellOrder(sellOrder, executedAt);
            return MatchAttemptResult.MUTATED_WITHOUT_MATCH;
        }
        BigDecimal reservedForMatchedQuantity = calculateReservedCashToRelease(
                buyOrder,
                quantity,
                executionPrice
        );
        BigDecimal actualCost = buyAmounts.netAmount();
        BigDecimal release = reservedForMatchedQuantity.subtract(actualCost).max(BigDecimal.ZERO);
        BigDecimal shortfall = actualCost.subtract(reservedForMatchedQuantity).max(BigDecimal.ZERO);
        orderBookExecutionWriter.adjustMatchedAccounts(
                sellOrder.accountId(),
                sellAmounts.netAmount(),
                buyOrder.accountId(),
                release.subtract(shortfall),
                executedAt
        );
        upsertHolding(buyOrder.accountId(), buyOrder.symbol(), quantity, buyAmounts.netAmount(), executedAt);
        orderBookExecutionWriter.updateOrdersAfterFill(
                toFillUpdate(sellOrder, quantity, executionPrice, BigDecimal.ZERO),
                toFillUpdate(buyOrder, quantity, executionPrice, reservedForMatchedQuantity),
                executedAt
        );
        if (buyOrder.fundingBudgetType() != null) {
            fundingBudgetService.consumeOrderBudget(
                    buyOrder.id(),
                    reservedForMatchedQuantity,
                    actualCost,
                    executedAt
            );
        }
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
        return MatchAttemptResult.MATCHED;
    }

    private void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            afterCommitCallbackTimer.record(action);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                afterCommitCallbackTimer.record(action);
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

    private OrderBookOrderFillUpdate toFillUpdate(
            OrderBookOrderRow order,
            long fillQuantity,
            BigDecimal executionPrice,
            BigDecimal reservedCashToRelease
    ) {
        long nextFilledQuantity = order.filledQuantity() + fillQuantity;
        String nextStatus = nextFilledQuantity >= order.quantity() ? "FILLED" : "PARTIALLY_FILLED";
        BigDecimal nextAverageFillPrice = calculateAverageFillPrice(order, fillQuantity, executionPrice);
        BigDecimal nextReservedCash = order.reservedCash().subtract(reservedCashToRelease).max(BigDecimal.ZERO);
        BigDecimal adjustedReservedCash = "FILLED".equals(nextStatus)
                ? BigDecimal.ZERO
                : nextReservedCash;
        return new OrderBookOrderFillUpdate(
                order.id(),
                nextStatus,
                nextFilledQuantity,
                nextAverageFillPrice,
                adjustedReservedCash
        );
    }

    private void recordMatchAttempt(MatchAttemptResult result) {
        meterRegistry.counter(
                "stock.orderbook.execution.match.attempts",
                "result",
                result.metricValue()
        ).increment();
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

    private enum MatchAttemptResult {
        MATCHED,
        MUTATED_WITHOUT_MATCH,
        STALE_CANDIDATE,
        DRAINED,
        SESSION_CLOSED;

        String metricValue() {
            return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }
    }

    private enum SymbolChunkTerminationReason {
        DRAINED(false),
        SESSION_CLOSED(false),
        MATCH_LIMIT(true),
        TIME_LIMIT(true),
        GLOBAL_LIMIT(true),
        STALE_LIMIT(true);

        private final boolean requeue;

        SymbolChunkTerminationReason(boolean requeue) {
            this.requeue = requeue;
        }

        String metricValue() {
            return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }
    }

    private record SymbolChunkResult(
            int matchCount,
            int progressCount,
            SymbolChunkTerminationReason terminationReason
    ) {

        boolean shouldRequeue() {
            return terminationReason.requeue;
        }
    }

}
