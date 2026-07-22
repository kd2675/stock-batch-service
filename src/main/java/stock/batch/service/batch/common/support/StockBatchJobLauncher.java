package stock.batch.service.batch.common.support;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import stock.batch.service.automarket.biz.AutoParticipantCashFlowRuntimeControl;
import stock.batch.service.batch.automarket.job.AutoMarketDailyRegimePreCreateJob;
import stock.batch.service.batch.automarket.job.AutoMarketJob;
import stock.batch.service.batch.automarket.job.AutoMarketOrderExpiryJob;
import stock.batch.service.batch.automarket.job.AutoMarketProfileQueueReconcileJob;
import stock.batch.service.batch.automarket.job.AutoMarketPreOpenProfileQueueReconcileJob;
import stock.batch.service.batch.automarket.job.AutoParticipantCashFlowJob;
import stock.batch.service.batch.automarket.job.ListingAutoMarketJob;
import stock.batch.service.batch.corporateaction.job.CorporateActionJob;
import stock.batch.service.batch.execution.job.OrderBookExecutionJob;
import stock.batch.service.batch.holdingcleanup.job.HoldingCleanupJob;
import stock.batch.service.batch.marketclose.job.MarketCloseRolloverJob;
import stock.batch.service.batch.marketclose.job.MarketOpenReadinessJob;
import stock.batch.service.batch.marketdata.job.MarketDataRefreshJob;
import stock.batch.service.batch.metadata.job.BatchMetadataRetentionJob;
import stock.batch.service.batch.report.job.PostCloseReportAggregationJob;
import stock.batch.service.batch.settlement.job.PortfolioSettlementJob;
import stock.batch.service.common.vo.StockBatchJobRunResponse;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;
import stock.batch.service.marketclose.biz.PostCloseCycleService;
import stock.batch.service.marketclose.model.PostCloseCycle;
import stock.batch.service.marketclose.model.PostClosePhase;
import stock.batch.service.marketclose.model.PostCloseScopeType;
import stock.batch.service.simulation.SimulationClockService;
import stock.batch.service.simulation.SimulationMarketSessionService;
import web.common.core.simulation.SimulationMarketSession;

@Service
public class StockBatchJobLauncher {

    private static final String TRIGGER_INTERNAL_API = "internal-api";
    private static final String TRIGGER_SCHEDULER = "scheduler";
    private static final String TRIGGER_SIGNAL = "db-signal";
    private static final String MODE_RECURRING_CASH = "recurring-cash";
    private static final String MODE_MANUAL_RECURRING_CASH = "manual-recurring-cash";
    private static final String MODE_DAILY_REGIME = "daily-regime";
    private static final String MODE_POST_CLOSE_CASH = "post-close-recurring-cash";
    private static final String MODE_POST_CLOSE_DAILY_REGIME = "post-close-daily-regime";
    private static final String MODE_CORPORATE_ACTION = "order-book";
    private static final String MODE_CORPORATE_CASH = "corporate-cash";
    private static final String MODE_PREOPEN_SECURITY_TRANSFORMS = "preopen-security-transforms";
    private static final String MODE_PORTFOLIO_SETTLEMENT = "portfolio-snapshot";
    private static final String MODE_MARKET_CLOSE_FULL = "price-limit-base:full";
    private static final String MODE_MARKET_CLOSE_SYMBOL = "price-limit-base:symbol";
    private static final String MODE_POST_CLOSE_REPORTS = "post-close-reports";
    private static final String MODE_MARKET_OPEN_READINESS = "market-open-readiness";
    private static final String MODE_CANCEL_OPEN_ORDERS = "halt-open-order-cancel";

    private final StockBatchJobRunner stockBatchJobRunner;
    private final AutoParticipantCashFlowRuntimeControl autoParticipantCashFlowRuntimeControl;
    private final SimulationClockService simulationClockService;
    private final SimulationMarketSessionService simulationMarketSessionService;
    private final MarketSessionFenceService marketSessionFenceService;
    private final PostCloseCycleService postCloseCycleService;
    private final MarketDataRefreshJob marketDataRefreshTask;
    private final OrderBookExecutionJob orderBookExecutionTask;
    private final AutoMarketProfileQueueReconcileJob autoMarketProfileQueueReconcileTask;
    private final AutoMarketPreOpenProfileQueueReconcileJob autoMarketPreOpenProfileQueueReconcileTask;
    private final AutoMarketJob autoMarketTask;
    private final AutoMarketOrderExpiryJob autoMarketOrderExpiryTask;
    private final ListingAutoMarketJob listingAutoMarketTask;
    private final HoldingCleanupJob holdingCleanupTask;
    private final BatchMetadataRetentionJob batchMetadataRetentionTask;
    private final Job autoParticipantCashFlowJob;
    private final Job autoMarketDailyRegimePreCreateJob;
    private final Job portfolioSettlementJob;
    private final Job marketCloseRolloverJob;
    private final Job corporateActionJob;
    private final Job postCloseReportAggregationJob;
    private final Job marketOpenReadinessJob;
    private final boolean postCloseCoordinatorEnabled;

    public StockBatchJobLauncher(
            StockBatchJobRunner stockBatchJobRunner,
            AutoParticipantCashFlowRuntimeControl autoParticipantCashFlowRuntimeControl,
            SimulationClockService simulationClockService,
            SimulationMarketSessionService simulationMarketSessionService,
            MarketSessionFenceService marketSessionFenceService,
            PostCloseCycleService postCloseCycleService,
            MarketDataRefreshJob marketDataRefreshTask,
            OrderBookExecutionJob orderBookExecutionTask,
            AutoMarketProfileQueueReconcileJob autoMarketProfileQueueReconcileTask,
            AutoMarketPreOpenProfileQueueReconcileJob autoMarketPreOpenProfileQueueReconcileTask,
            AutoMarketJob autoMarketTask,
            AutoMarketOrderExpiryJob autoMarketOrderExpiryTask,
            ListingAutoMarketJob listingAutoMarketTask,
            HoldingCleanupJob holdingCleanupTask,
            BatchMetadataRetentionJob batchMetadataRetentionTask,
            @Qualifier(AutoParticipantCashFlowJob.JOB_NAME) Job autoParticipantCashFlowJob,
            @Qualifier(AutoMarketDailyRegimePreCreateJob.JOB_NAME) Job autoMarketDailyRegimePreCreateJob,
            @Qualifier(PortfolioSettlementJob.JOB_NAME) Job portfolioSettlementJob,
            @Qualifier(MarketCloseRolloverJob.JOB_NAME) Job marketCloseRolloverJob,
            @Qualifier(CorporateActionJob.JOB_NAME) Job corporateActionJob,
            @Qualifier(PostCloseReportAggregationJob.JOB_NAME) Job postCloseReportAggregationJob,
            @Qualifier(MarketOpenReadinessJob.JOB_NAME) Job marketOpenReadinessJob,
            @Value("${stock.batch.post-close.coordinator.enabled:true}") boolean postCloseCoordinatorEnabled
    ) {
        this.stockBatchJobRunner = stockBatchJobRunner;
        this.autoParticipantCashFlowRuntimeControl = autoParticipantCashFlowRuntimeControl;
        this.simulationClockService = simulationClockService;
        this.simulationMarketSessionService = simulationMarketSessionService;
        this.marketSessionFenceService = marketSessionFenceService;
        this.postCloseCycleService = postCloseCycleService;
        this.marketDataRefreshTask = marketDataRefreshTask;
        this.orderBookExecutionTask = orderBookExecutionTask;
        this.autoMarketProfileQueueReconcileTask = autoMarketProfileQueueReconcileTask;
        this.autoMarketPreOpenProfileQueueReconcileTask = autoMarketPreOpenProfileQueueReconcileTask;
        this.autoMarketTask = autoMarketTask;
        this.autoMarketOrderExpiryTask = autoMarketOrderExpiryTask;
        this.listingAutoMarketTask = listingAutoMarketTask;
        this.holdingCleanupTask = holdingCleanupTask;
        this.batchMetadataRetentionTask = batchMetadataRetentionTask;
        this.autoParticipantCashFlowJob = autoParticipantCashFlowJob;
        this.autoMarketDailyRegimePreCreateJob = autoMarketDailyRegimePreCreateJob;
        this.portfolioSettlementJob = portfolioSettlementJob;
        this.marketCloseRolloverJob = marketCloseRolloverJob;
        this.corporateActionJob = corporateActionJob;
        this.postCloseReportAggregationJob = postCloseReportAggregationJob;
        this.marketOpenReadinessJob = marketOpenReadinessJob;
        this.postCloseCoordinatorEnabled = postCloseCoordinatorEnabled;
    }

    public StockBatchJobRunResponse refreshMarketData() {
        if (postCloseCoordinatorEnabled) {
            return StockBatchJobRunResponses.coordinatorManaged(
                    MarketDataRefreshJob.JOB_NAME,
                    marketDataRefreshTask.executionMode(),
                    LocalDateTime.now()
            );
        }
        return stockBatchJobRunner.run(marketDataRefreshTask);
    }

    public StockBatchJobRunResponse refreshMarketDataForPostClose(long closeCycleId) {
        requireCyclePhase(closeCycleId, PostClosePhase.PREOPEN_SECURITY_TRANSFORMS_APPLIED);
        return stockBatchJobRunner.run(marketDataRefreshTask, closeCycleId);
    }

    public StockBatchJobRunResponse executeOrderBookOrders() {
        return stockBatchJobRunner.run(orderBookExecutionTask);
    }

    public StockBatchJobRunResponse fundAutoParticipants() {
        if (postCloseCoordinatorEnabled) {
            return StockBatchJobRunResponses.coordinatorManaged(
                    AutoParticipantCashFlowJob.JOB_NAME,
                    MODE_RECURRING_CASH,
                    LocalDateTime.now()
            );
        }
        LocalDateTime now = simulationClockService.currentMarketDateTime();
        JobParametersBuilder parameters = baseParameters(
                MODE_RECURRING_CASH,
                TRIGGER_SCHEDULER,
                scheduledRequestId(AutoParticipantCashFlowJob.JOB_NAME, now),
                now
        )
                .addString(StockBatchJobParameters.OPERATION, AutoParticipantCashFlowJob.OPERATION_SCHEDULED, true)
                .addLocalDateTime(StockBatchJobParameters.SWEEP_AT, minuteBucket(now), true);
        return stockBatchJobRunner.run(autoParticipantCashFlowJob, MODE_RECURRING_CASH, parameters.toJobParameters());
    }

    public StockBatchJobRunResponse fundAutoParticipantsManually() {
        return fundAutoParticipantsManually(null, null, null);
    }

    public StockBatchJobRunResponse fundAutoParticipantsManually(Long signalId) {
        return fundAutoParticipantsManually(null, null, signalId);
    }

    public StockBatchJobRunResponse fundAutoParticipantsManually(
            LocalDate requestedBusinessDate,
            Long expectedCycleId,
            Long signalId
    ) {
        if (!autoParticipantCashFlowRuntimeControl.canRunManualCashFlow()) {
            return StockBatchJobRunResponses.manualCashFlowAutoEnabled(LocalDateTime.now());
        }
        if (simulationMarketSessionService.currentSession() == SimulationMarketSession.REGULAR) {
            return StockBatchJobRunResponses.manualCashFlowBeforeMarketClose(LocalDateTime.now());
        }
        LocalDate activeBusinessDate = requireActiveBusinessDate();
        LocalDate executionBusinessDate = requestedBusinessDate == null
                ? activeBusinessDate
                : requestedBusinessDate;
        if (!activeBusinessDate.equals(executionBusinessDate)) {
            throw new IllegalArgumentException(
                    "Requested business date is stale: requested=%s, active=%s"
                            .formatted(executionBusinessDate, activeBusinessDate)
            );
        }
        LocalDateTime simulationNow = simulationClockService.currentMarketDateTime();
        LocalDateTime overnightEligibleAt = executionBusinessDate.plusDays(1).atStartOfDay();
        if (simulationNow.isBefore(overnightEligibleAt)) {
            return StockBatchJobRunResponses.manualCashFlowOvernightDeferred(
                    overnightEligibleAt,
                    LocalDateTime.now()
            );
        }
        PostCloseCycle cycle = postCloseCycleService.findFullMarketCycle(executionBusinessDate)
                .orElse(null);
        if (cycle == null) {
            return StockBatchJobRunResponses.manualCashFlowOvernightDeferred(
                    overnightEligibleAt,
                    LocalDateTime.now()
            );
        }
        requireExpectedCycle(expectedCycleId, cycle);
        if (cycle.phase().ordinal() < PostClosePhase.CORPORATE_CASH_APPLIED.ordinal()) {
            return StockBatchJobRunResponses.manualCashFlowOvernightDeferred(
                    overnightEligibleAt,
                    LocalDateTime.now()
            );
        }
        String triggeredBy = signalId == null ? TRIGGER_INTERNAL_API : TRIGGER_SIGNAL;
        String requestId = signalId == null
                ? directRequestId(
                        AutoParticipantCashFlowJob.JOB_NAME,
                        executionBusinessDate,
                        MODE_MANUAL_RECURRING_CASH
                )
                : signalRequestId(signalId);
        JobParametersBuilder parameters = StockBatchJobParameters.base(
                executionBusinessDate,
                MODE_MANUAL_RECURRING_CASH,
                triggeredBy,
                requestId,
                LocalDateTime.now()
        ).addString(StockBatchJobParameters.OPERATION, AutoParticipantCashFlowJob.OPERATION_MANUAL, true);
        addSignalId(parameters, signalId);
        return stockBatchJobRunner.run(
                autoParticipantCashFlowJob,
                MODE_MANUAL_RECURRING_CASH,
                parameters.toJobParameters()
        );
    }

    public StockBatchJobRunResponse fundAutoParticipantsForPostClose(long closeCycleId) {
        PostCloseCycle cycle = requireCyclePhase(closeCycleId, PostClosePhase.CORPORATE_CASH_APPLIED);
        if (!autoParticipantCashFlowRuntimeControl.shouldRunScheduledJob()) {
            return StockBatchJobRunResponses.completedWithoutWork(
                    AutoParticipantCashFlowJob.JOB_NAME,
                    MODE_POST_CLOSE_CASH,
                    "Automatic recurring cash is disabled; overnight cash phase completed without payment",
                    LocalDateTime.now()
            );
        }
        LocalDateTime now = simulationClockService.currentMarketDateTime();
        JobParametersBuilder parameters = StockBatchJobParameters.base(
                cycle.businessDate(),
                MODE_POST_CLOSE_CASH,
                TRIGGER_SCHEDULER,
                requestId(
                        TRIGGER_SCHEDULER,
                        AutoParticipantCashFlowJob.JOB_NAME,
                        closeCycleId + ":post-close"
                ),
                LocalDateTime.now()
        )
                .addString(StockBatchJobParameters.OPERATION, AutoParticipantCashFlowJob.OPERATION_SCHEDULED, true)
                .addLong(StockBatchJobParameters.CYCLE_ID, closeCycleId, true)
                .addString(StockBatchJobParameters.SCOPE_KEY, cycle.scopeKey(), true)
                .addLong(StockBatchJobParameters.PHASE_REVISION, (long) cycle.phaseRevision(), true)
                .addLocalDateTime(StockBatchJobParameters.SWEEP_AT, now, false);
        return stockBatchJobRunner.run(
                autoParticipantCashFlowJob,
                MODE_POST_CLOSE_CASH,
                parameters.toJobParameters()
        );
    }

    public StockBatchJobRunResponse preCreateAutoMarketDailyRegimes() {
        if (postCloseCoordinatorEnabled) {
            return StockBatchJobRunResponses.coordinatorManaged(
                    AutoMarketDailyRegimePreCreateJob.JOB_NAME,
                    MODE_DAILY_REGIME,
                    LocalDateTime.now()
            );
        }
        LocalDateTime now = simulationClockService.currentMarketDateTime();
        JobParametersBuilder parameters = baseParameters(
                MODE_DAILY_REGIME,
                TRIGGER_SCHEDULER,
                scheduledRequestId(AutoMarketDailyRegimePreCreateJob.JOB_NAME, now),
                now
        ).addString(
                StockBatchJobParameters.OPERATION,
                AutoMarketDailyRegimePreCreateJob.OPERATION_SCHEDULED,
                true
        );
        return stockBatchJobRunner.run(
                autoMarketDailyRegimePreCreateJob,
                MODE_DAILY_REGIME,
                parameters.toJobParameters()
        );
    }

    public StockBatchJobRunResponse preCreateAutoMarketDailyRegimesForPostClose(
            long closeCycleId,
            LocalDate preparingBusinessDate
    ) {
        PostCloseCycle cycle = requireCyclePhase(closeCycleId, PostClosePhase.MARKET_DATA_PREPARED);
        requirePreparingBusinessDate(cycle, preparingBusinessDate);
        LocalDateTime now = simulationClockService.currentMarketDateTime();
        JobParametersBuilder parameters = StockBatchJobParameters.base(
                preparingBusinessDate,
                MODE_POST_CLOSE_DAILY_REGIME,
                TRIGGER_SCHEDULER,
                requestId(
                        TRIGGER_SCHEDULER,
                        AutoMarketDailyRegimePreCreateJob.JOB_NAME,
                        closeCycleId + ":" + preparingBusinessDate
                ),
                LocalDateTime.now()
        )
                .addString(
                        StockBatchJobParameters.OPERATION,
                        AutoMarketDailyRegimePreCreateJob.OPERATION_POST_CLOSE_PREPARE,
                        true
                )
                .addLong(StockBatchJobParameters.CYCLE_ID, closeCycleId, true)
                .addString(StockBatchJobParameters.SCOPE_KEY, cycle.scopeKey(), true)
                .addLong(StockBatchJobParameters.PHASE_REVISION, (long) cycle.phaseRevision(), true)
                .addLocalDateTime(StockBatchJobParameters.SWEEP_AT, now, false);
        return stockBatchJobRunner.run(
                autoMarketDailyRegimePreCreateJob,
                MODE_POST_CLOSE_DAILY_REGIME,
                parameters.toJobParameters()
        );
    }

    public StockBatchJobRunResponse reconcileAutoMarketProfileQueue() {
        if (postCloseCoordinatorEnabled
                && (!simulationMarketSessionService.isRegularSession()
                || !marketSessionFenceService.hasOpenOrderBookMarket())) {
            return StockBatchJobRunResponses.coordinatorManaged(
                    AutoMarketProfileQueueReconcileJob.JOB_NAME,
                    autoMarketProfileQueueReconcileTask.executionMode(),
                    LocalDateTime.now()
            );
        }
        return stockBatchJobRunner.run(autoMarketProfileQueueReconcileTask);
    }

    public StockBatchJobRunResponse reconcileAutoMarketProfileQueueForPreOpen(long closeCycleId) {
        requireCyclePhase(closeCycleId, PostClosePhase.MARKET_DATA_PREPARED);
        return stockBatchJobRunner.run(autoMarketPreOpenProfileQueueReconcileTask, closeCycleId);
    }

    public StockBatchJobRunResponse runAutoMarket() {
        return stockBatchJobRunner.run(autoMarketTask);
    }

    public StockBatchJobRunResponse expireAutoMarketOrders() {
        return stockBatchJobRunner.run(autoMarketOrderExpiryTask);
    }

    public StockBatchJobRunResponse runListingAutoMarket() {
        return stockBatchJobRunner.run(listingAutoMarketTask);
    }

    public StockBatchJobRunResponse settlePortfolios() {
        return settlePortfolios(
                requireActiveBusinessDate(),
                simulationClockService.currentMarketDateTime(),
                TRIGGER_INTERNAL_API
        );
    }

    public StockBatchJobRunResponse settlePortfoliosScheduled() {
        return settlePortfolios(
                simulationClockService.currentDate(),
                simulationClockService.currentMarketDateTime(),
                TRIGGER_SCHEDULER
        );
    }

    public StockBatchJobRunResponse settlePortfolios(LocalDate snapshotDate, LocalDateTime snapshotAt) {
        return settlePortfolios(snapshotDate, snapshotAt, TRIGGER_SCHEDULER);
    }

    public StockBatchJobRunResponse rolloverClosingPrices() {
        requireAfterCloseForCurrentFullClose();
        LocalDate businessDate = requireActiveBusinessDate();
        return rolloverClosingPrices(
                businessDate,
                businessDate.atTime(simulationMarketSessionService.closeTime()),
                TRIGGER_INTERNAL_API,
                null
        );
    }

    public StockBatchJobRunResponse rolloverClosingPricesScheduled() {
        requireAfterCloseForCurrentFullClose();
        LocalDate businessDate = simulationClockService.currentDate();
        return rolloverClosingPrices(
                businessDate,
                businessDate.atTime(simulationMarketSessionService.closeTime()),
                TRIGGER_SCHEDULER,
                null
        );
    }

    public StockBatchJobRunResponse rolloverClosingPrices(Long signalId) {
        requireOutsideRegularForFullClose();
        LocalDate businessDate = requireActiveBusinessDate();
        return rolloverClosingPrices(
                businessDate,
                businessDate.atTime(simulationMarketSessionService.closeTime()),
                TRIGGER_SIGNAL,
                signalId
        );
    }

    public StockBatchJobRunResponse rolloverClosingPrices(
            LocalDate requestedBusinessDate,
            Long expectedCycleId,
            Long requestedSessionEpoch,
            Long signalId
    ) {
        requireOutsideRegularForFullClose();
        return rolloverClosingPrices(
                requestedBusinessDate,
                requestedBusinessDate.atTime(simulationMarketSessionService.closeTime()),
                TRIGGER_SIGNAL,
                signalId,
                expectedCycleId,
                requestedSessionEpoch
        );
    }

    public StockBatchJobRunResponse rolloverClosingPrices(String symbol) {
        return rolloverClosingPrices(symbol, TRIGGER_INTERNAL_API, null);
    }

    public StockBatchJobRunResponse rolloverClosingPrices(String symbol, Long signalId) {
        return rolloverClosingPrices(symbol, TRIGGER_SIGNAL, signalId);
    }

    public StockBatchJobRunResponse rolloverClosingPrices(
            String symbol,
            LocalDate requestedBusinessDate,
            Long expectedCycleId,
            Long requestedSessionEpoch,
            Long signalId
    ) {
        return rolloverClosingPrices(
                symbol,
                requestedBusinessDate,
                TRIGGER_SIGNAL,
                signalId,
                expectedCycleId,
                requestedSessionEpoch
        );
    }

    public StockBatchJobRunResponse rolloverClosingPrices(LocalDate businessDate, LocalDateTime closedAt) {
        return rolloverClosingPrices(businessDate, closedAt, TRIGGER_SCHEDULER, null);
    }

    public StockBatchJobRunResponse cancelOpenOrderBookOrders(String symbol) {
        return cancelOpenOrderBookOrders(symbol, null);
    }

    public StockBatchJobRunResponse cancelOpenOrderBookOrders(String symbol, Long signalId) {
        return cancelOpenOrderBookOrders(
                symbol,
                requireActiveBusinessDate(),
                null,
                signalId
        );
    }

    public StockBatchJobRunResponse cancelOpenOrderBookOrders(
            String symbol,
            LocalDate requestedBusinessDate,
            Long requestedSessionEpoch,
            Long signalId
    ) {
        LocalDateTime now = simulationClockService.currentMarketDateTime();
        String normalizedSymbol = normalizeSymbol(symbol);
        String triggeredBy = signalId == null ? TRIGGER_INTERNAL_API : TRIGGER_SIGNAL;
        JobParametersBuilder parameters = StockBatchJobParameters.base(
                requestedBusinessDate,
                MODE_CANCEL_OPEN_ORDERS,
                triggeredBy,
                signalId == null
                        ? directRequestId(
                                MarketCloseRolloverJob.JOB_NAME,
                                requestedBusinessDate,
                                MODE_CANCEL_OPEN_ORDERS + ":" + normalizedSymbol
                        )
                        : signalRequestId(signalId),
                LocalDateTime.now()
        )
                .addString(StockBatchJobParameters.OPERATION, MarketCloseRolloverJob.OPERATION_CANCEL_OPEN_ORDERS, true)
                .addString(StockBatchJobParameters.SYMBOL, normalizedSymbol, true)
                .addLocalDateTime(StockBatchJobParameters.CLOSED_AT, now, false);
        if (requestedSessionEpoch != null) {
            parameters.addLong(StockBatchJobParameters.SESSION_EPOCH, requestedSessionEpoch, false);
        }
        addSignalId(parameters, signalId);
        return stockBatchJobRunner.run(marketCloseRolloverJob, MODE_CANCEL_OPEN_ORDERS, parameters.toJobParameters());
    }

    public StockBatchJobRunResponse applyCorporateActions() {
        if (postCloseCoordinatorEnabled) {
            return StockBatchJobRunResponses.coordinatorManaged(
                    CorporateActionJob.JOB_NAME,
                    MODE_CORPORATE_ACTION,
                    LocalDateTime.now()
            );
        }
        return applyCorporateActions(TRIGGER_INTERNAL_API);
    }

    public StockBatchJobRunResponse applyCorporateActionsScheduled() {
        if (postCloseCoordinatorEnabled) {
            return StockBatchJobRunResponses.coordinatorManaged(
                    CorporateActionJob.JOB_NAME,
                    MODE_CORPORATE_ACTION,
                    LocalDateTime.now()
            );
        }
        return applyCorporateActions(TRIGGER_SCHEDULER);
    }

    public StockBatchJobRunResponse applyCorporateCashActions(long closeCycleId) {
        PostCloseCycle cycle = requireCyclePhase(closeCycleId, PostClosePhase.PORTFOLIO_SETTLED);
        return applyCorporatePhase(
                cycle,
                cycle.businessDate(),
                MODE_CORPORATE_CASH,
                CorporateActionJob.OPERATION_CASH,
                null
        );
    }

    public StockBatchJobRunResponse applyPreOpenSecurityTransforms(
            long closeCycleId,
            LocalDate preparingBusinessDate
    ) {
        PostCloseCycle cycle = requireCyclePhase(closeCycleId, PostClosePhase.REPORTS_AGGREGATED);
        requirePreparingBusinessDate(cycle, preparingBusinessDate);
        return applyCorporatePhase(
                cycle,
                preparingBusinessDate,
                MODE_PREOPEN_SECURITY_TRANSFORMS,
                CorporateActionJob.OPERATION_PREOPEN_SECURITY_TRANSFORMS,
                cycle.businessDate()
        );
    }

    public StockBatchJobRunResponse cleanupEmptyHoldings() {
        if (postCloseCoordinatorEnabled) {
            return StockBatchJobRunResponses.coordinatorManaged(
                    HoldingCleanupJob.JOB_NAME,
                    holdingCleanupTask.executionMode(),
                    LocalDateTime.now()
            );
        }
        return stockBatchJobRunner.run(holdingCleanupTask);
    }

    public StockBatchJobRunResponse cleanupEmptyHoldingsForPostClose(long closeCycleId) {
        requireCyclePhase(closeCycleId, PostClosePhase.REPORTS_AGGREGATED);
        return stockBatchJobRunner.run(holdingCleanupTask, closeCycleId);
    }

    public StockBatchJobRunResponse retainBatchMetadataForPostClose(long closeCycleId) {
        requireCyclePhase(closeCycleId, PostClosePhase.REPORTS_AGGREGATED);
        return stockBatchJobRunner.run(batchMetadataRetentionTask, closeCycleId);
    }

    public StockBatchJobRunResponse aggregatePostCloseReports(long closeCycleId, LocalDateTime aggregatedAt) {
        PostCloseCycle cycle = requireCyclePhase(closeCycleId, PostClosePhase.OVERNIGHT_CASH_APPLIED);
        JobParametersBuilder parameters = StockBatchJobParameters.base(
                cycle.businessDate(),
                MODE_POST_CLOSE_REPORTS,
                TRIGGER_SCHEDULER,
                requestId(TRIGGER_SCHEDULER, PostCloseReportAggregationJob.JOB_NAME, Long.toString(closeCycleId)),
                LocalDateTime.now()
        )
                .addLong(StockBatchJobParameters.CYCLE_ID, closeCycleId, true)
                .addString(StockBatchJobParameters.SCOPE_KEY, cycle.scopeKey(), true)
                .addLong(StockBatchJobParameters.PHASE_REVISION, (long) cycle.phaseRevision(), true)
                .addLocalDateTime(StockBatchJobParameters.SNAPSHOT_AT, aggregatedAt, false);
        return stockBatchJobRunner.run(
                postCloseReportAggregationJob,
                MODE_POST_CLOSE_REPORTS,
                parameters.toJobParameters()
        );
    }

    public StockBatchJobRunResponse validateMarketOpenReadiness(
            long closeCycleId,
            LocalDate preparingBusinessDate
    ) {
        PostCloseCycle cycle = requireCyclePhase(closeCycleId, PostClosePhase.AUTO_MARKET_PREPARED);
        requirePreparingBusinessDate(cycle, preparingBusinessDate);
        JobParametersBuilder parameters = StockBatchJobParameters.base(
                preparingBusinessDate,
                MODE_MARKET_OPEN_READINESS,
                TRIGGER_SCHEDULER,
                requestId(
                        TRIGGER_SCHEDULER,
                        MarketOpenReadinessJob.JOB_NAME,
                        closeCycleId + ":" + preparingBusinessDate
                ),
                LocalDateTime.now()
        )
                .addLong(StockBatchJobParameters.CYCLE_ID, closeCycleId, true)
                .addString(StockBatchJobParameters.SCOPE_KEY, cycle.scopeKey(), true)
                .addLong(StockBatchJobParameters.PHASE_REVISION, (long) cycle.phaseRevision(), true);
        return stockBatchJobRunner.run(
                marketOpenReadinessJob,
                MODE_MARKET_OPEN_READINESS,
                parameters.toJobParameters()
        );
    }

    private StockBatchJobRunResponse settlePortfolios(
            LocalDate snapshotDate,
            LocalDateTime snapshotAt,
            String triggeredBy
    ) {
        PostCloseCycle cycle = postCloseCycleService.findFullMarketCycle(snapshotDate)
                .orElseThrow(() -> new IllegalStateException(
                        "Full-market post-close cycle does not exist: businessDate=" + snapshotDate
                ));
        requireCyclePhase(cycle, PostClosePhase.LEDGER_FROZEN);
        if (!postCloseCycleService.isPhaseClaimEligible(cycle, LocalDateTime.now())) {
            return StockBatchJobRunResponses.cycleClaimDeferred(
                    PortfolioSettlementJob.JOB_NAME,
                    MODE_PORTFOLIO_SETTLEMENT,
                    nextCycleClaimAt(cycle),
                    LocalDateTime.now()
            );
        }
        JobParametersBuilder parameters = StockBatchJobParameters.base(
                snapshotDate,
                MODE_PORTFOLIO_SETTLEMENT,
                triggeredBy,
                requestId(triggeredBy, PortfolioSettlementJob.JOB_NAME, snapshotDate.toString()),
                LocalDateTime.now()
        )
                .addLong(StockBatchJobParameters.CYCLE_ID, cycle.id(), true)
                .addString(StockBatchJobParameters.SCOPE_KEY, cycle.scopeKey(), true)
                .addLong(StockBatchJobParameters.PHASE_REVISION, (long) cycle.phaseRevision(), true)
                .addLocalDateTime(StockBatchJobParameters.SNAPSHOT_AT, snapshotAt, false);
        return stockBatchJobRunner.run(
                portfolioSettlementJob,
                MODE_PORTFOLIO_SETTLEMENT,
                parameters.toJobParameters()
        );
    }

    private StockBatchJobRunResponse rolloverClosingPrices(
            LocalDate businessDate,
            LocalDateTime closedAt,
            String triggeredBy,
            Long signalId
    ) {
        return rolloverClosingPrices(businessDate, closedAt, triggeredBy, signalId, null, null);
    }

    private StockBatchJobRunResponse rolloverClosingPrices(
            LocalDate businessDate,
            LocalDateTime closedAt,
            String triggeredBy,
            Long signalId,
            Long expectedCycleId,
            Long requestedSessionEpoch
    ) {
        PostCloseCycle cycle = postCloseCycleService.ensureFullMarketCycle(businessDate, LocalDateTime.now());
        requireExpectedCycle(expectedCycleId, cycle);
        if (!postCloseCycleService.isPhaseClaimEligible(cycle, LocalDateTime.now())) {
            return StockBatchJobRunResponses.cycleClaimDeferred(
                    MarketCloseRolloverJob.JOB_NAME,
                    MODE_MARKET_CLOSE_FULL,
                    nextCycleClaimAt(cycle),
                    LocalDateTime.now()
            );
        }
        JobParametersBuilder parameters = StockBatchJobParameters.base(
                businessDate,
                MODE_MARKET_CLOSE_FULL,
                triggeredBy,
                signalId == null
                        ? requestId(triggeredBy, MarketCloseRolloverJob.JOB_NAME, businessDate.toString())
                        : signalRequestId(signalId),
                LocalDateTime.now()
        )
                .addString(StockBatchJobParameters.OPERATION, MarketCloseRolloverJob.OPERATION_FULL, true)
                .addLong(StockBatchJobParameters.CYCLE_ID, cycle.id(), true)
                .addString(StockBatchJobParameters.SCOPE_KEY, "ALL", true)
                .addLong(StockBatchJobParameters.PHASE_REVISION, (long) cycle.phaseRevision(), true)
                .addLocalDateTime(StockBatchJobParameters.CLOSED_AT, closedAt, false);
        if (requestedSessionEpoch != null) {
            parameters.addLong(StockBatchJobParameters.SESSION_EPOCH, requestedSessionEpoch, false);
        }
        addSignalId(parameters, signalId);
        return stockBatchJobRunner.run(marketCloseRolloverJob, MODE_MARKET_CLOSE_FULL, parameters.toJobParameters());
    }

    private StockBatchJobRunResponse rolloverClosingPrices(String symbol, String triggeredBy, Long signalId) {
        LocalDate businessDate = requireActiveBusinessDate();
        return rolloverClosingPrices(symbol, businessDate, triggeredBy, signalId, null, null);
    }

    private StockBatchJobRunResponse rolloverClosingPrices(
            String symbol,
            LocalDate businessDate,
            String triggeredBy,
            Long signalId,
            Long expectedCycleId,
            Long requestedSessionEpoch
    ) {
        LocalDateTime now = TRIGGER_SIGNAL.equals(triggeredBy)
                ? businessDate.atTime(simulationMarketSessionService.closeTime())
                : simulationClockService.currentMarketDateTime();
        String normalizedSymbol = normalizeSymbol(symbol);
        PostCloseCycle cycle = postCloseCycleService.ensureSymbolCycle(businessDate, normalizedSymbol, LocalDateTime.now());
        requireExpectedCycle(expectedCycleId, cycle);
        if (!postCloseCycleService.isPhaseClaimEligible(cycle, LocalDateTime.now())) {
            return StockBatchJobRunResponses.cycleClaimDeferred(
                    MarketCloseRolloverJob.JOB_NAME,
                    MODE_MARKET_CLOSE_SYMBOL,
                    nextCycleClaimAt(cycle),
                    LocalDateTime.now()
            );
        }
        JobParametersBuilder parameters = StockBatchJobParameters.base(
                businessDate,
                MODE_MARKET_CLOSE_SYMBOL,
                triggeredBy,
                signalId == null
                        ? requestId(triggeredBy, MarketCloseRolloverJob.JOB_NAME, normalizedSymbol)
                        : signalRequestId(signalId),
                LocalDateTime.now()
        )
                .addString(StockBatchJobParameters.OPERATION, MarketCloseRolloverJob.OPERATION_SYMBOL, true)
                .addString(StockBatchJobParameters.SYMBOL, normalizedSymbol, true)
                .addLong(StockBatchJobParameters.CYCLE_ID, cycle.id(), true)
                .addString(StockBatchJobParameters.SCOPE_KEY, normalizedSymbol, true)
                .addLong(StockBatchJobParameters.PHASE_REVISION, (long) cycle.phaseRevision(), true)
                .addLocalDateTime(StockBatchJobParameters.CLOSED_AT, now, false);
        if (requestedSessionEpoch != null) {
            parameters.addLong(StockBatchJobParameters.SESSION_EPOCH, requestedSessionEpoch, false);
        }
        addSignalId(parameters, signalId);
        return stockBatchJobRunner.run(marketCloseRolloverJob, MODE_MARKET_CLOSE_SYMBOL, parameters.toJobParameters());
    }

    private StockBatchJobRunResponse applyCorporateActions(String triggeredBy) {
        LocalDateTime now = simulationClockService.currentMarketDateTime();
        JobParametersBuilder parameters = baseParameters(
                MODE_CORPORATE_ACTION,
                triggeredBy,
                requestId(triggeredBy, CorporateActionJob.JOB_NAME, minuteBucket(now).toString()),
                now
        )
                .addString(
                        StockBatchJobParameters.SESSION,
                        simulationMarketSessionService.currentSession().name(),
                        true
                )
                .addLocalDateTime(StockBatchJobParameters.SWEEP_AT, minuteBucket(now), true);
        return stockBatchJobRunner.run(corporateActionJob, MODE_CORPORATE_ACTION, parameters.toJobParameters());
    }

    private StockBatchJobRunResponse applyCorporatePhase(
            PostCloseCycle cycle,
            LocalDate effectiveBusinessDate,
            String mode,
            String operation,
            LocalDate requiredCloseDate
    ) {
        LocalDateTime now = simulationClockService.currentMarketDateTime();
        JobParametersBuilder parameters = StockBatchJobParameters.base(
                effectiveBusinessDate,
                mode,
                TRIGGER_SCHEDULER,
                requestId(TRIGGER_SCHEDULER, CorporateActionJob.JOB_NAME, cycle.id() + ":" + operation),
                LocalDateTime.now()
        )
                .addString(StockBatchJobParameters.OPERATION, operation, true)
                .addLong(StockBatchJobParameters.CYCLE_ID, cycle.id(), true)
                .addString(StockBatchJobParameters.SCOPE_KEY, cycle.scopeKey(), true)
                .addLong(StockBatchJobParameters.PHASE_REVISION, (long) cycle.phaseRevision(), true)
                .addLocalDateTime(StockBatchJobParameters.SWEEP_AT, minuteBucket(now), false);
        if (requiredCloseDate != null) {
            parameters.addLocalDate(StockBatchJobParameters.REQUIRED_CLOSE_DATE, requiredCloseDate, true);
        }
        return stockBatchJobRunner.run(corporateActionJob, mode, parameters.toJobParameters());
    }

    private PostCloseCycle requireCycle(long closeCycleId) {
        return postCloseCycleService.findById(closeCycleId)
                .orElseThrow(() -> new IllegalStateException("Post-close cycle does not exist: " + closeCycleId));
    }

    private LocalDateTime nextCycleClaimAt(PostCloseCycle cycle) {
        return cycle.nextRetryAt() != null ? cycle.nextRetryAt() : cycle.leaseUntil();
    }

    private PostCloseCycle requireCyclePhase(long closeCycleId, PostClosePhase expectedPhase) {
        return requireCyclePhase(requireCycle(closeCycleId), expectedPhase);
    }

    private PostCloseCycle requireCyclePhase(PostCloseCycle cycle, PostClosePhase expectedPhase) {
        long closeCycleId = cycle.id();
        if (cycle.scopeType() != PostCloseScopeType.FULL_MARKET || !"ALL".equals(cycle.scopeKey())) {
            throw new IllegalStateException(
                    "Post-close phase job requires a full-market cycle: cycleId=%d, scope=%s:%s"
                            .formatted(closeCycleId, cycle.scopeType(), cycle.scopeKey())
            );
        }
        if (cycle.phase() != expectedPhase) {
            throw new IllegalStateException(
                    "Post-close phase job requires %s: cycleId=%d, phase=%s"
                            .formatted(expectedPhase, closeCycleId, cycle.phase())
            );
        }
        return cycle;
    }

    private void requirePreparingBusinessDate(PostCloseCycle cycle, LocalDate preparingBusinessDate) {
        LocalDate expectedDate = cycle.businessDate().plusDays(1);
        if (!expectedDate.equals(preparingBusinessDate)) {
            throw new IllegalStateException(
                    "Post-close preparing date must be the next business date: cycleId=%d, expected=%s, actual=%s"
                            .formatted(cycle.id(), expectedDate, preparingBusinessDate)
            );
        }
    }

    private JobParametersBuilder baseParameters(
            String mode,
            String triggeredBy,
            String requestId,
            LocalDateTime simulationNow
    ) {
        return StockBatchJobParameters.base(
                simulationNow.toLocalDate(),
                mode,
                triggeredBy,
                requestId,
                LocalDateTime.now()
        );
    }

    private void addSignalId(JobParametersBuilder parameters, Long signalId) {
        if (signalId != null) {
            parameters.addLong(StockBatchJobParameters.SIGNAL_ID, signalId, false);
        }
    }

    private LocalDateTime minuteBucket(LocalDateTime value) {
        return value.withSecond(0).withNano(0);
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        return symbol.trim().toUpperCase();
    }

    private String scheduledRequestId(String jobName, LocalDateTime now) {
        return requestId(TRIGGER_SCHEDULER, jobName, minuteBucket(now).toString());
    }

    private String directRequestId(String jobName, LocalDate businessDate, String scope) {
        return requestId(TRIGGER_INTERNAL_API, jobName, businessDate + ":" + scope);
    }

    private String signalRequestId(long signalId) {
        return TRIGGER_SIGNAL + ":" + signalId;
    }

    private String requestId(String trigger, String jobName, String scope) {
        return trigger + ":" + jobName + ":" + scope;
    }

    private void requireAfterCloseForCurrentFullClose() {
        if (!simulationMarketSessionService.isAfterCloseSession()) {
            throw new IllegalStateException("Full market close can only run after the regular session");
        }
    }

    private void requireOutsideRegularForFullClose() {
        if (simulationMarketSessionService.isRegularSession()) {
            throw new IllegalStateException("Full market close cannot run during the regular session");
        }
    }

    private void requireExpectedCycle(Long expectedCycleId, PostCloseCycle cycle) {
        if (expectedCycleId != null && expectedCycleId.longValue() != cycle.id()) {
            throw new IllegalArgumentException(
                    "Requested post-close cycle does not match the logical cycle: requested=%d, actual=%d"
                            .formatted(expectedCycleId, cycle.id())
            );
        }
    }

    private LocalDate requireActiveBusinessDate() {
        LocalDate activeBusinessDate = marketSessionFenceService.activeBusinessDate();
        if (activeBusinessDate == null) {
            throw new IllegalStateException("Active market business date is not initialized");
        }
        return activeBusinessDate;
    }
}
