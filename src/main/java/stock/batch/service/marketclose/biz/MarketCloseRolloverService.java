package stock.batch.service.marketclose.biz;

import jakarta.annotation.PostConstruct;
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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import stock.batch.service.batch.marketclose.model.MarketCloseOrderRow;
import stock.batch.service.batch.marketclose.writer.MarketCloseRolloverWriter;
import stock.batch.service.execution.lock.OrderBookSymbolLock;
import stock.batch.service.marketclose.model.PostCloseCycle;
import stock.batch.service.marketclose.model.PostCloseCycleStatus;
import stock.batch.service.marketclose.model.PostClosePhase;
import stock.batch.service.marketclose.model.PostClosePhaseClaim;
import stock.batch.service.simulation.SimulationClockService;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketCloseRolloverService {

    private static final String BUY = "BUY";
    private static final String SELL = "SELL";
    private static final List<String> OPEN_ORDER_STATUSES = List.of("PENDING", "PARTIALLY_FILLED");
    private static final int MAX_ORDER_CAPTURE_CHUNK_SIZE = 10_000;
    private static final int MAX_ORDER_CANCEL_CHUNK_SIZE = 5_000;
    private static final int MAX_ACCOUNT_CHUNK_SIZE = 2_000;
    private static final int MAX_DEADLOCK_RETRY_ATTEMPTS = 10;
    private static final long MAX_DEADLOCK_RETRY_BACKOFF_MILLIS = 1_000L;
    private static final long MAX_SETTLEMENT_DELAY_SIMULATION_MINUTES = 1_440L;

    private final MarketCloseRolloverWriter writer;
    private final SimulationClockService simulationClockService;
    private final OrderBookSymbolLock orderBookSymbolLock;
    private final TransactionTemplate transactionTemplate;
    private final MarketSessionFenceService marketSessionFenceService;
    private final PostCloseCycleService postCloseCycleService;

    @Value("${stock.batch.market-close.deadlock-retry-max-attempts:5}")
    private int deadlockRetryMaxAttempts = 5;

    @Value("${stock.batch.market-close.deadlock-retry-backoff-ms:50}")
    private long deadlockRetryBackoffMs = 50;

    @Value("${stock.batch.market-close.order-cancel-chunk-size:500}")
    private int orderCancelChunkSize = 500;

    @Value("${stock.batch.market-close.order-capture-chunk-size:1000}")
    private int orderCaptureChunkSize = 1000;

    @Value("${stock.batch.market-close.holding-snapshot-account-chunk-size:500}")
    private int holdingSnapshotAccountChunkSize = 500;

    @Value("${stock.batch.market-close.account-snapshot-chunk-size:500}")
    private int accountSnapshotChunkSize = 500;

    @Value("${stock.batch.market-close.reconciliation-account-chunk-size:500}")
    private int reconciliationAccountChunkSize = 500;

    @Value("${stock.batch.market-close.settlement-delay-simulation-minutes:10}")
    private long settlementDelaySimulationMinutes = 10;

    @PostConstruct
    void validateVolumeConfiguration() {
        validateChunkSize(
                "order-capture-chunk-size",
                orderCaptureChunkSize,
                MAX_ORDER_CAPTURE_CHUNK_SIZE
        );
        validateChunkSize(
                "order-cancel-chunk-size",
                orderCancelChunkSize,
                MAX_ORDER_CANCEL_CHUNK_SIZE
        );
        validateChunkSize(
                "holding-snapshot-account-chunk-size",
                holdingSnapshotAccountChunkSize,
                MAX_ACCOUNT_CHUNK_SIZE
        );
        validateChunkSize("account-snapshot-chunk-size", accountSnapshotChunkSize, MAX_ACCOUNT_CHUNK_SIZE);
        validateChunkSize(
                "reconciliation-account-chunk-size",
                reconciliationAccountChunkSize,
                MAX_ACCOUNT_CHUNK_SIZE
        );
        if (deadlockRetryMaxAttempts < 1 || deadlockRetryMaxAttempts > MAX_DEADLOCK_RETRY_ATTEMPTS) {
            throw new IllegalStateException(
                    "stock.batch.market-close.deadlock-retry-max-attempts must be between 1 and %d: %d"
                            .formatted(MAX_DEADLOCK_RETRY_ATTEMPTS, deadlockRetryMaxAttempts)
            );
        }
        if (deadlockRetryBackoffMs < 0 || deadlockRetryBackoffMs > MAX_DEADLOCK_RETRY_BACKOFF_MILLIS) {
            throw new IllegalStateException(
                    "stock.batch.market-close.deadlock-retry-backoff-ms must be between 0 and %d: %d"
                            .formatted(MAX_DEADLOCK_RETRY_BACKOFF_MILLIS, deadlockRetryBackoffMs)
            );
        }
        if (settlementDelaySimulationMinutes < 0
                || settlementDelaySimulationMinutes > MAX_SETTLEMENT_DELAY_SIMULATION_MINUTES) {
            throw new IllegalStateException(
                    "stock.batch.market-close.settlement-delay-simulation-minutes must be between 0 and %d: %d"
                            .formatted(MAX_SETTLEMENT_DELAY_SIMULATION_MINUTES, settlementDelaySimulationMinutes)
            );
        }
    }

    public int rolloverClosingPrices() {
        LocalDate simulationTradeDate = simulationClockService.currentDate();
        LocalDateTime closedAt = simulationClockService.currentMarketDateTime();
        return rolloverClosingPricesInternal(null, simulationTradeDate, closedAt, null);
    }

    public int rolloverClosingPrices(String symbol) {
        return rolloverClosingPricesInternal(
                symbol,
                simulationClockService.currentDate(),
                simulationClockService.currentMarketDateTime(),
                null
        );
    }

    public int rolloverClosingPrices(String symbol, LocalDate simulationTradeDate, LocalDateTime closedAt) {
        return rolloverClosingPricesInternal(symbol, simulationTradeDate, closedAt, null);
    }

    public int rolloverClosingPrices(
            String symbol,
            LocalDate simulationTradeDate,
            LocalDateTime closedAt,
            Long batchJobExecutionId
    ) {
        return rolloverClosingPricesInternal(symbol, simulationTradeDate, closedAt, batchJobExecutionId);
    }

    public int rolloverClosingPrices(LocalDate simulationTradeDate, LocalDateTime closedAt) {
        return rolloverClosingPricesInternal(null, simulationTradeDate, closedAt, null);
    }

    public int rolloverClosingPrices(
            LocalDate simulationTradeDate,
            LocalDateTime closedAt,
            Long batchJobExecutionId
    ) {
        return rolloverClosingPricesInternal(null, simulationTradeDate, closedAt, batchJobExecutionId);
    }

    public boolean hasCompletedFullCloseRun(LocalDate businessDate) {
        if (businessDate == null) {
            return false;
        }
        return writer.hasCompletedFullCloseRun(businessDate);
    }

    private int rolloverClosingPricesInternal(
            String symbol,
            LocalDate simulationTradeDate,
            LocalDateTime closedAt,
            Long batchJobExecutionId
    ) {
        if (simulationTradeDate == null) {
            throw new IllegalArgumentException("simulationTradeDate is required");
        }
        if (closedAt == null) {
            throw new IllegalArgumentException("closedAt is required");
        }
        String normalizedSymbol = normalizeSymbol(symbol);
        if (normalizedSymbol != null && writer.isOrderBookMarketOpen(normalizedSymbol)) {
            throw new IllegalStateException(
                    "Symbol market close rollover requires the market to be halted or closed: "
                            + normalizedSymbol
            );
        }
        LocalDateTime claimTime = LocalDateTime.now();
        PostCloseCycle cycle = normalizedSymbol == null
                ? postCloseCycleService.ensureFullMarketCycle(simulationTradeDate, claimTime)
                : postCloseCycleService.ensureSymbolCycle(simulationTradeDate, normalizedSymbol, claimTime);
        if (cycle.status() == PostCloseCycleStatus.COMPLETED || !isCloseFreezePhase(cycle.phase())) {
            return 0;
        }
        PostClosePhaseClaim claim = postCloseCycleService.tryClaim(
                        cycle.id(),
                        cycle.phase(),
                        claimTime
                )
                .orElseThrow(() -> new CannotAcquireLockException(
                        "Post-close cycle phase is already claimed: cycleId=" + cycle.id()
                ));
        postCloseCycleService.linkBatchJobExecution(claim, batchJobExecutionId, LocalDateTime.now());
        try {
            marketSessionFenceService.beginClose(simulationTradeDate, closedAt, normalizedSymbol);
            List<OrderBookSymbolLock.LockHandle> locks = acquireCloseLocks(normalizedSymbol);
            if (locks == null) {
                throw new CannotAcquireLockException("Market close rollover skipped because order-book symbol lock is busy");
            }
            try {
                return rolloverClosingPricesWithBoundedTransactions(
                        normalizedSymbol,
                        simulationTradeDate,
                        closedAt,
                        cycle,
                        claim
                );
            } finally {
                releaseLocks(locks);
            }
        } catch (RuntimeException ex) {
            postCloseCycleService.failPhase(claim, ex, LocalDateTime.now());
            throw ex;
        }
    }

    private boolean isCloseFreezePhase(PostClosePhase phase) {
        return phase == PostClosePhase.CLOSE_REQUESTED
                || phase == PostClosePhase.ORDER_ENTRY_CLOSED
                || phase == PostClosePhase.EXECUTION_DRAINED;
    }

    private int rolloverClosingPricesWithBoundedTransactions(
            String normalizedSymbol,
            LocalDate simulationTradeDate,
            LocalDateTime closedAt,
            PostCloseCycle cycle,
            PostClosePhaseClaim claim
    ) {
        long closeRunId = ensureCloseRun(
                normalizedSymbol,
                simulationTradeDate,
                closedAt,
                cycle,
                claim
        );
        captureOpenOrdersInChunks(claim.cycleId(), closeRunId, normalizedSymbol, closedAt);
        ensureOpenOrderSummary(claim.cycleId(), closeRunId, normalizedSymbol, closedAt);
        snapshotHoldingsInChunks(claim.cycleId(), closeRunId, normalizedSymbol, closedAt);
        if (normalizedSymbol == null) {
            ensureAccountSnapshots(claim.cycleId(), closeRunId, closedAt);
            ensurePriceSnapshots(claim.cycleId(), closeRunId, closedAt);
        }
        cancelCapturedOrdersInChunks(claim.cycleId(), closedAt);
        reconcileSnapshotsInChunks(claim.cycleId());

        long capturedOrderCount = writer.countCapturedOrders(claim.cycleId());
        if (writer.countRemainingCapturedOrders(claim.cycleId()) != 0L) {
            throw new IllegalStateException("Open orders remain after market-close cancellation");
        }
        long reconciliationMismatchCount = writer.countSnapshotReconciliationMismatches(claim.cycleId());
        if (reconciliationMismatchCount != 0L) {
            throw new IllegalStateException(
                    "Market-close snapshot reconciliation failed: mismatches=" + reconciliationMismatchCount
            );
        }

        int cancelledOrderCount = Math.toIntExact(capturedOrderCount);
        int holdingSnapshotCount = Math.toIntExact(writer.countHoldingSnapshots(claim.cycleId()));
        int accountSnapshotCount = normalizedSymbol == null
                ? Math.toIntExact(writer.countAccountSnapshots(claim.cycleId()))
                : 0;
        int priceSnapshotCount = normalizedSymbol == null
                ? Math.toIntExact(writer.countPriceSnapshots(claim.cycleId()))
                : 0;
        int openOrderSummaryCount = Math.toIntExact(writer.countOpenOrderSummaries(claim.cycleId()));
        MarketCloseRolloverWriter.ReleasedReservationTotals releasedReservations =
                writer.sumReleasedReservations(claim.cycleId());
        long settlementTargetAccountCount = normalizedSymbol == null
                ? writer.countSettlementTargetAccounts(claim.cycleId())
                : 0L;
        return runInTransactionWithDeadlockRetry(
                closeRetryLabel(normalizedSymbol) + ":complete",
                () -> completeFrozenClose(
                        normalizedSymbol,
                        simulationTradeDate,
                        closedAt,
                        claim,
                        closeRunId,
                        cancelledOrderCount,
                        holdingSnapshotCount,
                        accountSnapshotCount,
                        priceSnapshotCount,
                        openOrderSummaryCount,
                        releasedReservations,
                        settlementTargetAccountCount
                )
        );
    }

    private long ensureCloseRun(
            String symbol,
            LocalDate businessDate,
            LocalDateTime closedAt,
            PostCloseCycle cycle,
            PostClosePhaseClaim claim
    ) {
        if (cycle.closeRunId() != null) {
            return cycle.closeRunId();
        }
        return runInTransactionWithDeadlockRetry(
                closeRetryLabel(symbol) + ":create-run",
                () -> {
                    PostCloseCycle current = postCloseCycleService.findById(claim.cycleId()).orElseThrow();
                    if (current.closeRunId() != null) {
                        return current.closeRunId();
                    }
                    long closeRunId = writer.createCloseRun(symbol, businessDate, closedAt);
                    postCloseCycleService.linkCloseRun(claim, closeRunId, LocalDateTime.now());
                    return closeRunId;
                }
        );
    }

    private void captureOpenOrdersInChunks(
            long closeCycleId,
            long closeRunId,
            String symbol,
            LocalDateTime capturedAt
    ) {
        int chunkSize = Math.max(1, orderCaptureChunkSize);
        List<String> captureSymbols = symbol == null
                ? writer.findOpenOrderCaptureSymbols()
                : List.of(symbol);
        for (String captureSymbol : captureSymbols) {
            for (String sourceOrderStatus : OPEN_ORDER_STATUSES) {
                captureOpenOrderStream(
                        closeCycleId,
                        closeRunId,
                        captureSymbol,
                        sourceOrderStatus,
                        capturedAt,
                        chunkSize
                );
            }
        }
    }

    private void captureOpenOrderStream(
            long closeCycleId,
            long closeRunId,
            String symbol,
            String sourceOrderStatus,
            LocalDateTime capturedAt,
            int chunkSize
    ) {
        long afterOrderId = writer.findLastCapturedOrderId(closeCycleId, symbol, sourceOrderStatus);
        while (true) {
            long checkpoint = afterOrderId;
            int inserted = runIntInTransactionWithDeadlockRetry(
                    closeRetryLabel(symbol) + ":capture-orders:" + sourceOrderStatus,
                    () -> writer.captureOpenOrdersChunk(
                            closeCycleId,
                            closeRunId,
                            symbol,
                            sourceOrderStatus,
                            capturedAt,
                            checkpoint,
                            chunkSize
                    )
            );
            if (inserted == 0) {
                return;
            }
            long nextCheckpoint = writer.findLastCapturedOrderId(
                    closeCycleId,
                    symbol,
                    sourceOrderStatus
            );
            if (nextCheckpoint <= afterOrderId) {
                throw new IllegalStateException(
                        "Close-order capture checkpoint did not advance: symbol=%s, status=%s"
                                .formatted(symbol, sourceOrderStatus)
                );
            }
            afterOrderId = nextCheckpoint;
        }
    }

    private void ensureOpenOrderSummary(
            long closeCycleId,
            long closeRunId,
            String symbol,
            LocalDateTime snapshotAt
    ) {
        if (writer.countOpenOrderSummaries(closeCycleId) > 0L) {
            return;
        }
        runIntInTransactionWithDeadlockRetry(
                closeRetryLabel(symbol) + ":open-order-summary",
                () -> writer.snapshotOpenOrderSummary(closeCycleId, closeRunId, symbol, snapshotAt)
        );
    }

    private void snapshotHoldingsInChunks(
            long closeCycleId,
            long closeRunId,
            String symbol,
            LocalDateTime snapshotAt
    ) {
        int chunkSize = Math.max(1, holdingSnapshotAccountChunkSize);
        long afterAccountId = writer.findLastHoldingSnapshotAccountId(closeCycleId);
        while (true) {
            List<Long> accountIds = writer.findHoldingSnapshotAccountCandidates(
                    symbol,
                    afterAccountId,
                    chunkSize
            );
            if (accountIds.isEmpty()) {
                return;
            }
            runIntInTransactionWithDeadlockRetry(
                    closeRetryLabel(symbol) + ":holding-snapshot",
                    () -> writer.snapshotHoldingsForAccounts(
                            closeCycleId,
                            closeRunId,
                            symbol,
                            snapshotAt,
                            accountIds
                    )
            );
            long nextCheckpoint = accountIds.getLast();
            if (nextCheckpoint <= afterAccountId) {
                throw new IllegalStateException("Holding snapshot checkpoint did not advance");
            }
            afterAccountId = nextCheckpoint;
        }
    }

    private void ensureAccountSnapshots(long closeCycleId, long closeRunId, LocalDateTime snapshotAt) {
        Long previousCloseCycleId = writer.findPreviousFrozenFullMarketCycleId(closeCycleId);
        long previousCashFlowWatermarkId = previousCloseCycleId == null
                ? 0L
                : writer.findCashFlowWatermark(previousCloseCycleId);
        long currentCashFlowWatermarkId = writer.countAccountSnapshots(closeCycleId) > 0L
                ? writer.findCashFlowWatermark(closeCycleId)
                : writer.findCurrentCashFlowWatermark();
        int chunkSize = Math.max(1, accountSnapshotChunkSize);
        long afterAccountId = writer.findLastAccountSnapshotId(closeCycleId);
        while (true) {
            List<Long> accountIds = writer.findAccountSnapshotCandidates(afterAccountId, chunkSize);
            if (accountIds.isEmpty()) {
                return;
            }
            runIntInTransactionWithDeadlockRetry(
                    "market-close-rollover:all:account-snapshot",
                    () -> writer.snapshotAccountsForAccounts(
                            closeCycleId,
                            closeRunId,
                            snapshotAt,
                            previousCloseCycleId,
                            previousCashFlowWatermarkId,
                            currentCashFlowWatermarkId,
                            accountIds
                    )
            );
            long nextCheckpoint = accountIds.getLast();
            if (nextCheckpoint <= afterAccountId) {
                throw new IllegalStateException("Account snapshot checkpoint did not advance");
            }
            afterAccountId = nextCheckpoint;
        }
    }

    private void ensurePriceSnapshots(long closeCycleId, long closeRunId, LocalDateTime snapshotAt) {
        if (writer.countPriceSnapshots(closeCycleId) > 0L) {
            return;
        }
        runIntInTransactionWithDeadlockRetry(
                "market-close-rollover:all:price-snapshot",
                () -> writer.snapshotPrices(closeCycleId, closeRunId, snapshotAt)
        );
    }

    private void cancelCapturedOrdersInChunks(long closeCycleId, LocalDateTime closedAt) {
        int chunkSize = Math.max(1, orderCancelChunkSize);
        while (true) {
            int processed = runIntInTransactionWithDeadlockRetry(
                    "market-close-rollover:cancel-captured-orders",
                    () -> cancelCapturedOrderChunk(closeCycleId, closedAt, chunkSize)
            );
            if (processed == 0) {
                return;
            }
        }
    }

    private int cancelCapturedOrderChunk(long closeCycleId, LocalDateTime closedAt, int chunkSize) {
        List<MarketCloseOrderRow> candidates = writer.findUnreleasedCapturedOrderCandidates(
                closeCycleId,
                chunkSize
        );
        if (candidates.isEmpty()) {
            return 0;
        }
        writer.lockAccountsForUpdate(candidates);
        writer.lockSellHoldingsForUpdate(candidates);
        List<MarketCloseOrderRow> lockedOrders = writer.lockOpenOrderBookOrdersForUpdate(candidates);
        if (lockedOrders.size() != candidates.size()) {
            throw new IllegalStateException(
                    "Captured close orders changed before exact-PK lock: expected=%d, actual=%d"
                            .formatted(candidates.size(), lockedOrders.size())
            );
        }
        List<Long> orderIds = lockedOrders.stream().map(MarketCloseOrderRow::id).toList();
        int cancelled = writer.cancelCapturedOrders(closeCycleId, orderIds, closedAt);
        if (cancelled != orderIds.size()) {
            throw new IllegalStateException(
                    "Captured close-order cancellation count mismatch: expected=%d, actual=%d"
                            .formatted(orderIds.size(), cancelled)
            );
        }
        Map<Long, BigDecimal> reservedCashByAccount = new TreeMap<>();
        Map<MarketCloseRolloverWriter.HoldingReservationKey, Long> reservedQuantityByHolding =
                new TreeMap<>(java.util.Comparator
                        .comparingLong(MarketCloseRolloverWriter.HoldingReservationKey::accountId)
                        .thenComparing(MarketCloseRolloverWriter.HoldingReservationKey::symbol));
        for (MarketCloseOrderRow order : lockedOrders) {
            if (BUY.equals(order.side()) && order.reservedCash().compareTo(BigDecimal.ZERO) > 0) {
                reservedCashByAccount.merge(order.accountId(), order.reservedCash(), BigDecimal::add);
            }
            if (SELL.equals(order.side()) && order.remainingQuantity() > 0) {
                reservedQuantityByHolding.merge(
                        new MarketCloseRolloverWriter.HoldingReservationKey(
                                order.accountId(),
                                order.symbol()
                        ),
                        order.remainingQuantity(),
                        Long::sum
                );
            }
        }
        int creditedBuyAccountCount = writer.creditCashChunk(reservedCashByAccount, closedAt);
        if (creditedBuyAccountCount != reservedCashByAccount.size()) {
            throw new IllegalStateException(
                    "Captured buy-reservation release count mismatch: expected=%d, actual=%d"
                            .formatted(reservedCashByAccount.size(), creditedBuyAccountCount)
            );
        }
        int releasedSellHoldingCount = writer.releaseReservedSellQuantityChunk(
                reservedQuantityByHolding,
                closedAt
        );
        if (releasedSellHoldingCount != reservedQuantityByHolding.size()) {
            throw new IllegalStateException(
                    "Captured sell-reservation release count mismatch: expected=%d, actual=%d"
                            .formatted(reservedQuantityByHolding.size(), releasedSellHoldingCount)
            );
        }
        int released = writer.markCapturedOrdersReleased(closeCycleId, orderIds, closedAt);
        if (released != orderIds.size()) {
            throw new IllegalStateException(
                    "Captured close-order release count mismatch: expected=%d, actual=%d"
                            .formatted(orderIds.size(), released)
            );
        }
        return released;
    }

    private void reconcileSnapshotsInChunks(long closeCycleId) {
        int chunkSize = Math.max(1, reconciliationAccountChunkSize);
        long afterAccountId = 0L;
        while (true) {
            List<Long> accountIds = writer.findPendingAccountReconciliationCandidates(
                    closeCycleId,
                    afterAccountId,
                    chunkSize
            );
            if (accountIds.isEmpty()) {
                break;
            }
            runIntInTransactionWithDeadlockRetry(
                    "market-close-rollover:account-reconciliation",
                    () -> writer.completeAccountSnapshotReconciliation(closeCycleId, accountIds)
            );
            afterAccountId = accountIds.getLast();
        }
        runIntInTransactionWithDeadlockRetry(
                "market-close-rollover:order-summary-reconciliation",
                () -> writer.completeOpenOrderSummaryReconciliation(closeCycleId)
        );
    }

    private static void validateChunkSize(String propertyName, int value, int maximum) {
        if (value < 1 || value > maximum) {
            throw new IllegalStateException(
                    "stock.batch.market-close.%s must be between 1 and %d: %d"
                            .formatted(propertyName, maximum, value)
            );
        }
    }

    private int completeFrozenClose(
            String symbol,
            LocalDate businessDate,
            LocalDateTime closedAt,
            PostClosePhaseClaim claim,
            long closeRunId,
            int cancelledOrderCount,
            int holdingSnapshotCount,
            int accountSnapshotCount,
            int priceSnapshotCount,
            int openOrderSummaryCount,
            MarketCloseRolloverWriter.ReleasedReservationTotals releasedReservations,
            long settlementTargetAccountCount
    ) {
        int priceRolloverCount = writer.rolloverClosingPrices(symbol);
        LocalDateTime completedAt = simulationClockService.currentMarketDateTime();
        writer.completeCloseRun(
                closeRunId,
                cancelledOrderCount,
                holdingSnapshotCount,
                priceRolloverCount,
                completedAt
        );
        writer.insertCycleMetrics(
                claim.cycleId(),
                closeRunId,
                cancelledOrderCount,
                cancelledOrderCount,
                releasedReservations.buyCash(),
                releasedReservations.sellQuantity(),
                settlementTargetAccountCount,
                accountSnapshotCount,
                holdingSnapshotCount,
                priceSnapshotCount,
                openOrderSummaryCount,
                0L,
                completedAt
        );
        marketSessionFenceService.completeOrderBookClose(symbol, businessDate, completedAt);
        PostClosePhase nextPhase = symbol == null
                ? PostClosePhase.LEDGER_FROZEN
                : PostClosePhase.COMPLETED;
        LocalDateTime settlementEligibleAt = symbol == null
                ? laterOf(
                        completedAt,
                        closedAt.plusMinutes(Math.max(0, settlementDelaySimulationMinutes))
                )
                : null;
        postCloseCycleService.completePhase(
                claim,
                nextPhase,
                closeRunId,
                settlementEligibleAt,
                LocalDateTime.now()
        );
        return cancelledOrderCount + priceRolloverCount + holdingSnapshotCount;
    }

    public int cancelOpenOrderBookOrders(String symbol) {
        return cancelOpenOrderBookOrders(
                symbol,
                simulationClockService.currentDate(),
                simulationClockService.currentMarketDateTime()
        );
    }

    public int cancelOpenOrderBookOrders(String symbol, LocalDate businessDate, LocalDateTime cancelledAt) {
        String normalizedSymbol = normalizeSymbol(symbol);
        if (normalizedSymbol == null) {
            throw new IllegalArgumentException("symbol is required");
        }
        if (businessDate == null || cancelledAt == null) {
            throw new IllegalArgumentException("businessDate and cancelledAt are required");
        }
        if (writer.isOrderBookMarketOpen(normalizedSymbol)) {
            throw new IllegalStateException(
                    "Open-order bulk cancellation requires the symbol market to be halted or closed: "
                            + normalizedSymbol
            );
        }
        List<OrderBookSymbolLock.LockHandle> locks = acquireCloseLocks(normalizedSymbol);
        if (locks == null) {
            throw new CannotAcquireLockException(
                    "Open-order bulk cancellation deferred because the order-book symbol lock is busy: "
                            + normalizedSymbol
            );
        }
        try {
            if (writer.isOrderBookMarketOpen(normalizedSymbol)) {
                throw new IllegalStateException(
                        "Open-order bulk cancellation stopped because the symbol market reopened: "
                                + normalizedSymbol
                );
            }
            marketSessionFenceService.beginClose(businessDate, cancelledAt, normalizedSymbol);
            int cancelledOrderCount = 0;
            int chunkSize = Math.max(1, orderCancelChunkSize);
            while (true) {
                int chunkCount = runIntInTransactionWithDeadlockRetry(
                        "open-order-cancel:" + normalizedSymbol,
                        () -> cancelOpenOrderBookOrderChunkLocked(normalizedSymbol, cancelledAt, chunkSize)
                );
                if (chunkCount == 0) {
                    return cancelledOrderCount;
                }
                cancelledOrderCount += chunkCount;
            }
        } finally {
            releaseLocks(locks);
        }
    }

    private int cancelOpenOrderBookOrderChunkLocked(String symbol, LocalDateTime closedAt, int chunkSize) {
        List<MarketCloseOrderRow> candidates = writer.findOpenOrderBookOrderCandidates(symbol, chunkSize);
        if (candidates.isEmpty()) {
            return 0;
        }
        writer.lockAccountsForUpdate(candidates);
        writer.lockSellHoldingsForUpdate(symbol, candidates);
        List<MarketCloseOrderRow> orders = writer.lockOpenOrderBookOrdersForUpdate(candidates);
        if (orders.size() != candidates.size()) {
            throw new IllegalStateException(
                    "Open orders changed after exact-PK lock: expected=%d, actual=%d"
                            .formatted(candidates.size(), orders.size())
            );
        }
        int cancelledCount = writer.cancelOrders(
                orders.stream().map(MarketCloseOrderRow::id).toList(),
                closedAt
        );
        if (cancelledCount != orders.size()) {
            throw new IllegalStateException(
                    "Open-order cancellation count mismatch: expected=%d, actual=%d"
                            .formatted(orders.size(), cancelledCount)
            );
        }
        creditCancelledBuyReservations(orders, closedAt);
        releaseCancelledSellReservations(symbol, orders, closedAt);
        return cancelledCount;
    }

    private void creditCancelledBuyReservations(List<MarketCloseOrderRow> cancelledOrders, LocalDateTime closedAt) {
        Map<Long, BigDecimal> reservedCashByAccount = new TreeMap<>();
        for (MarketCloseOrderRow order : cancelledOrders) {
            if (!BUY.equals(order.side()) || order.reservedCash().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            reservedCashByAccount.merge(order.accountId(), order.reservedCash(), BigDecimal::add);
        }
        if (reservedCashByAccount.isEmpty()) {
            return;
        }
        int creditedCount = writer.creditCashChunk(reservedCashByAccount, closedAt);
        if (creditedCount != reservedCashByAccount.size()) {
            throw new IllegalStateException(
                    "Open-order buy reservation release count mismatch: expected=%d, actual=%d"
                            .formatted(reservedCashByAccount.size(), creditedCount)
            );
        }
    }

    private void releaseCancelledSellReservations(
            String symbol,
            List<MarketCloseOrderRow> cancelledOrders,
            LocalDateTime closedAt
    ) {
        Map<Long, Long> reservedQuantityByAccount = new TreeMap<>();
        for (MarketCloseOrderRow order : cancelledOrders) {
            if (!SELL.equals(order.side()) || order.remainingQuantity() <= 0) {
                continue;
            }
            reservedQuantityByAccount.merge(order.accountId(), order.remainingQuantity(), Long::sum);
        }
        if (reservedQuantityByAccount.isEmpty()) {
            return;
        }
        int releasedCount = writer.releaseReservedSellQuantityChunk(
                symbol,
                reservedQuantityByAccount,
                closedAt
        );
        if (releasedCount != reservedQuantityByAccount.size()) {
            throw new IllegalStateException(
                    "Open-order sell reservation release count mismatch: expected=%d, actual=%d"
                            .formatted(reservedQuantityByAccount.size(), releasedCount)
            );
        }
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

    private int runIntInTransactionWithDeadlockRetry(String workName, Supplier<Integer> action) {
        Integer result = runInTransactionWithDeadlockRetry(workName, action);
        return result == null ? 0 : result;
    }

    private <T> T runInTransactionWithDeadlockRetry(String workName, Supplier<T> action) {
        int attempts = Math.max(1, deadlockRetryMaxAttempts);
        CannotAcquireLockException lastException = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return transactionTemplate.execute(status -> action.get());
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

    private LocalDateTime laterOf(LocalDateTime first, LocalDateTime second) {
        return first.isAfter(second) ? first : second;
    }

}
