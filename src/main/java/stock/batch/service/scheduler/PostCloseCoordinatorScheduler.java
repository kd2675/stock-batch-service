package stock.batch.service.scheduler;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.automarket.job.AutoMarketDailyRegimePreCreateJob;
import stock.batch.service.batch.automarket.job.AutoMarketPreOpenProfileQueueReconcileJob;
import stock.batch.service.batch.automarket.job.AutoParticipantCashFlowJob;
import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.batch.common.support.StockBatchJobRunResponses;
import stock.batch.service.batch.corporateaction.job.CorporateActionJob;
import stock.batch.service.batch.holdingcleanup.job.HoldingCleanupJob;
import stock.batch.service.batch.marketclose.job.MarketOpenReadinessJob;
import stock.batch.service.batch.marketdata.job.MarketDataRefreshJob;
import stock.batch.service.batch.metadata.job.BatchMetadataRetentionJob;
import stock.batch.service.batch.report.job.PostCloseReportAggregationJob;
import stock.batch.service.common.vo.StockBatchJobRunResponse;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;
import stock.batch.service.marketclose.biz.PostCloseCycleService;
import stock.batch.service.marketclose.biz.PostClosePhaseExecutionService;
import stock.batch.service.marketclose.biz.SkippedBusinessDateRecoveryService;
import stock.batch.service.marketclose.model.PostCloseCycle;
import stock.batch.service.marketclose.model.PostClosePhase;
import stock.batch.service.simulation.SimulationMarketSessionService;
import web.common.core.simulation.SimulationMarketSession;

@Component
@ConditionalOnProperty(
        prefix = "stock.batch.post-close.coordinator",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@Slf4j
public class PostCloseCoordinatorScheduler {

    private static final String COORDINATOR_JOB = "post-close-coordinator";

    private final StockBatchJobLauncher stockBatchJobLauncher;
    private final StockBatchScheduledJobGuard scheduledJobGuard;
    private final SimulationMarketSessionService simulationMarketSessionService;
    private final MarketSessionFenceService marketSessionFenceService;
    private final PostCloseCycleService postCloseCycleService;
    private final PostClosePhaseExecutionService phaseExecutionService;
    private final SkippedBusinessDateRecoveryService skippedBusinessDateRecoveryService;
    private final LocalTime preOpenTransformTime;
    private final LocalTime autoMarketPreparationTime;
    private final LocalTime readinessTime;
    private final boolean cashFlowConfigured;
    private final boolean corporateActionsConfigured;
    private final boolean reportsConfigured;
    private final boolean holdingCleanupConfigured;
    private final boolean metadataRetentionConfigured;
    private final boolean marketDataConfigured;
    private final boolean dailyRegimeConfigured;
    private final boolean profileQueueConfigured;
    private final boolean readinessConfigured;

    public PostCloseCoordinatorScheduler(
            StockBatchJobLauncher stockBatchJobLauncher,
            StockBatchScheduledJobGuard scheduledJobGuard,
            SimulationMarketSessionService simulationMarketSessionService,
            MarketSessionFenceService marketSessionFenceService,
            PostCloseCycleService postCloseCycleService,
            PostClosePhaseExecutionService phaseExecutionService,
            SkippedBusinessDateRecoveryService skippedBusinessDateRecoveryService,
            @Value("${stock.batch.post-close.preopen-transform-time:04:30}") String preOpenTransformTime,
            @Value("${stock.batch.post-close.auto-market-preparation-time:05:30}") String autoMarketPreparationTime,
            @Value("${stock.batch.post-close.readiness-time:05:30}") String readinessTime,
            @Value("${stock.batch.auto-participant-cash-flow.enabled:true}") boolean cashFlowConfigured,
            @Value("${stock.batch.corporate-actions.enabled:true}") boolean corporateActionsConfigured,
            @Value("${stock.batch.post-close.report-aggregation.enabled:true}") boolean reportsConfigured,
            @Value("${stock.batch.holding-cleanup.enabled:true}") boolean holdingCleanupConfigured,
            @Value("${stock.batch.metadata-retention.enabled:false}") boolean metadataRetentionConfigured,
            @Value("${stock.batch.market-data.enabled:true}") boolean marketDataConfigured,
            @Value("${stock.batch.auto-market.daily-regime.enabled:true}") boolean dailyRegimeConfigured,
            @Value("${stock.batch.auto-market.profile-queue.reconcile-enabled:true}") boolean profileQueueConfigured,
            @Value("${stock.batch.post-close.readiness.enabled:true}") boolean readinessConfigured
    ) {
        this.stockBatchJobLauncher = stockBatchJobLauncher;
        this.scheduledJobGuard = scheduledJobGuard;
        this.simulationMarketSessionService = simulationMarketSessionService;
        this.marketSessionFenceService = marketSessionFenceService;
        this.postCloseCycleService = postCloseCycleService;
        this.phaseExecutionService = phaseExecutionService;
        this.skippedBusinessDateRecoveryService = skippedBusinessDateRecoveryService;
        this.preOpenTransformTime = parseTime(preOpenTransformTime, LocalTime.of(4, 30));
        this.autoMarketPreparationTime = parseTime(autoMarketPreparationTime, LocalTime.of(5, 30));
        this.readinessTime = parseTime(readinessTime, LocalTime.of(5, 30));
        this.cashFlowConfigured = cashFlowConfigured;
        this.corporateActionsConfigured = corporateActionsConfigured;
        this.reportsConfigured = reportsConfigured;
        this.holdingCleanupConfigured = holdingCleanupConfigured;
        this.metadataRetentionConfigured = metadataRetentionConfigured;
        this.marketDataConfigured = marketDataConfigured;
        this.dailyRegimeConfigured = dailyRegimeConfigured;
        this.profileQueueConfigured = profileQueueConfigured;
        this.readinessConfigured = readinessConfigured;
    }

    @Scheduled(
            scheduler = StockBatchSchedulerNames.POST_CLOSE,
            fixedDelayString = "${stock.batch.post-close.coordinator.poll-fixed-delay-ms:10000}"
    )
    public void advanceOldestCycle() {
        LocalDateTime simulationNow = simulationMarketSessionService.currentSimulationDateTime();
        PostCloseCycle cycle = postCloseCycleService.findOldestIncompleteFullMarketCycle().orElse(null);
        if (cycle == null) {
            if (marketSessionFenceService.hasOpenMarket()) {
                return;
            }
            skippedBusinessDateRecoveryService.recoverNextMissedBusinessDate(
                            simulationNow,
                            simulationMarketSessionService.closeTime()
                    )
                    .ifPresent(skippedDate -> log.warn(
                            "Missed stock business date recorded as SKIPPED while market remained closed: businessDate={}",
                            skippedDate
                    ));
            return;
        }
        if (marketSessionFenceService.hasOpenMarket() && cycle.phase() != PostClosePhase.READY_TO_OPEN) {
            return;
        }
        try {
            advanceOnePhase(cycle, simulationNow);
        } catch (RuntimeException failure) {
            log.warn(
                    "Post-close coordinator poll failed: cycleId={}, businessDate={}, phase={}, reason={}",
                    cycle.id(),
                    cycle.businessDate(),
                    cycle.phase(),
                    failure.getMessage(),
                    failure
            );
        }
    }

    boolean advanceOnePhase(PostCloseCycle cycle, LocalDateTime simulationNow) {
        LocalDate preparingBusinessDate = cycle.businessDate().plusDays(1);
        return switch (cycle.phase()) {
            case OPEN, CLOSE_REQUESTED, ORDER_ENTRY_CLOSED, EXECUTION_DRAINED, LEDGER_FROZEN -> false;
            case PORTFOLIO_SETTLED -> runClosedMarketPhase(
                    cycle,
                    PostClosePhase.PORTFOLIO_SETTLED,
                    PostClosePhase.CORPORATE_CASH_APPLIED,
                    CorporateActionJob.JOB_NAME,
                    corporateActionsConfigured,
                    () -> stockBatchJobLauncher.applyCorporateCashActions(cycle.id())
            );
            case CORPORATE_CASH_APPLIED -> runAfter(
                    cycle,
                    simulationNow,
                    preparingBusinessDate.atStartOfDay(),
                    PostClosePhase.CORPORATE_CASH_APPLIED,
                    PostClosePhase.OVERNIGHT_CASH_APPLIED,
                    () -> cashFlowConfigured
                            ? stockBatchJobLauncher.fundAutoParticipantsForPostClose(cycle.id())
                            : StockBatchJobRunResponses.completedWithoutWork(
                                    AutoParticipantCashFlowJob.JOB_NAME,
                                    "post-close-recurring-cash",
                                    "Automatic recurring cash is not configured; overnight cash phase completed without payment",
                                    LocalDateTime.now()
                            )
            );
            case OVERNIGHT_CASH_APPLIED -> runClosedMarketPhase(
                    cycle,
                    PostClosePhase.OVERNIGHT_CASH_APPLIED,
                    PostClosePhase.REPORTS_AGGREGATED,
                    PostCloseReportAggregationJob.JOB_NAME,
                    reportsConfigured,
                    () -> stockBatchJobLauncher.aggregatePostCloseReports(cycle.id(), simulationNow)
            );
            case REPORTS_AGGREGATED -> runAfter(
                    cycle,
                    simulationNow,
                    preparingBusinessDate.atTime(preOpenTransformTime),
                    PostClosePhase.REPORTS_AGGREGATED,
                    PostClosePhase.PREOPEN_SECURITY_TRANSFORMS_APPLIED,
                    CorporateActionJob.JOB_NAME,
                    corporateActionsConfigured,
                    () -> cleanupAndApplyPreOpenTransforms(cycle, preparingBusinessDate, simulationNow)
            );
            case PREOPEN_SECURITY_TRANSFORMS_APPLIED -> runClosedMarketPhase(
                    cycle,
                    PostClosePhase.PREOPEN_SECURITY_TRANSFORMS_APPLIED,
                    PostClosePhase.MARKET_DATA_PREPARED,
                    MarketDataRefreshJob.JOB_NAME,
                    marketDataConfigured,
                    () -> stockBatchJobLauncher.refreshMarketDataForPostClose(cycle.id())
            );
            case MARKET_DATA_PREPARED -> runAfter(
                    cycle,
                    simulationNow,
                    preparingBusinessDate.atTime(autoMarketPreparationTime),
                    PostClosePhase.MARKET_DATA_PREPARED,
                    PostClosePhase.AUTO_MARKET_PREPARED,
                    AutoMarketDailyRegimePreCreateJob.JOB_NAME,
                    dailyRegimeConfigured,
                    () -> prepareAutoMarket(cycle, preparingBusinessDate)
            );
            case AUTO_MARKET_PREPARED -> runAfter(
                    cycle,
                    simulationNow,
                    preparingBusinessDate.atTime(readinessTime),
                    PostClosePhase.AUTO_MARKET_PREPARED,
                    PostClosePhase.READY_TO_OPEN,
                    MarketOpenReadinessJob.JOB_NAME,
                    readinessConfigured,
                    () -> stockBatchJobLauncher.validateMarketOpenReadiness(
                            cycle.id(),
                            preparingBusinessDate
                    )
            );
            case READY_TO_OPEN -> resolveReadyCycle(cycle, preparingBusinessDate, simulationNow);
            case COMPLETED -> true;
        };
    }

    private boolean runAfter(
            PostCloseCycle cycle,
            LocalDateTime simulationNow,
            LocalDateTime eligibleAt,
            PostClosePhase expectedPhase,
            PostClosePhase nextPhase,
            java.util.function.Supplier<StockBatchJobRunResponse> action
    ) {
        if (simulationNow.isBefore(eligibleAt)) {
            return false;
        }
        if (marketSessionFenceService.hasOpenMarket()) {
            log.info(
                    "Post-close policy phase deferred to protect regular order traffic: cycleId={}, phase={}",
                    cycle.id(),
                    expectedPhase
            );
            return false;
        }
        return phaseExecutionService.execute(cycle, expectedPhase, nextPhase, action);
    }

    private boolean runAfter(
            PostCloseCycle cycle,
            LocalDateTime simulationNow,
            LocalDateTime eligibleAt,
            PostClosePhase expectedPhase,
            PostClosePhase nextPhase,
            String jobName,
            boolean configured,
            java.util.function.Supplier<StockBatchJobRunResponse> action
    ) {
        if (simulationNow.isBefore(eligibleAt)) {
            return false;
        }
        return runClosedMarketPhase(cycle, expectedPhase, nextPhase, jobName, configured, action);
    }

    private boolean runClosedMarketPhase(
            PostCloseCycle cycle,
            PostClosePhase expectedPhase,
            PostClosePhase nextPhase,
            String jobName,
            boolean configured,
            java.util.function.Supplier<StockBatchJobRunResponse> action
    ) {
        if (marketSessionFenceService.hasOpenMarket()) {
            log.info(
                    "Post-close phase deferred to protect regular order traffic: cycleId={}, phase={}",
                    cycle.id(),
                    expectedPhase
            );
            return false;
        }
        return phaseExecutionService.execute(
                cycle,
                expectedPhase,
                nextPhase,
                () -> scheduledJobGuard.runBatchIfEnabled(jobName, configured, action)
        );
    }

    private StockBatchJobRunResponse cleanupAndApplyPreOpenTransforms(
            PostCloseCycle cycle,
            LocalDate preparingBusinessDate,
            LocalDateTime simulationNow
    ) {
        LocalDateTime optionalMaintenanceCutoff = preparingBusinessDate
                .atTime(readinessTime)
                .minusMinutes(30);
        StockBatchJobRunResponse cleanup;
        if (simulationNow.isBefore(optionalMaintenanceCutoff)) {
            StockBatchJobRunResponse metadataRetention = scheduledJobGuard.runOptionalBatchIfEnabled(
                    BatchMetadataRetentionJob.JOB_NAME,
                    metadataRetentionConfigured,
                    () -> stockBatchJobLauncher.retainBatchMetadataForPostClose(cycle.id())
            );
            if (!isSuccessful(metadataRetention)) {
                if (metadataRetention == null || !"FAILED".equals(metadataRetention.status())) {
                    return metadataRetention;
                }
                log.warn(
                        "Optional batch metadata retention failed without blocking market preparation: cycleId={}, message={}",
                        cycle.id(),
                        metadataRetention.message()
                );
            }
            LocalDateTime afterRetention = simulationMarketSessionService.currentSimulationDateTime();
            if (afterRetention.isBefore(optionalMaintenanceCutoff)) {
                cleanup = scheduledJobGuard.runOptionalBatchIfEnabled(
                        HoldingCleanupJob.JOB_NAME,
                        holdingCleanupConfigured,
                        () -> stockBatchJobLauncher.cleanupEmptyHoldingsForPostClose(cycle.id())
                );
            } else {
                log.info(
                        "Optional holding cleanup skipped after metadata retention to preserve the readiness window: "
                                + "cycleId={}, simulationNow={}, cutoff={}",
                        cycle.id(),
                        afterRetention,
                        optionalMaintenanceCutoff
                );
                cleanup = StockBatchJobRunResponses.completedWithoutWork(
                        HoldingCleanupJob.JOB_NAME,
                        "optional-post-close-maintenance",
                        "Optional holding cleanup window has closed; security transforms continue",
                        LocalDateTime.now()
                );
            }
        } else {
            log.info(
                    "Optional post-close maintenance skipped to preserve the readiness window: cycleId={}, "
                            + "simulationNow={}, cutoff={}",
                    cycle.id(),
                    simulationNow,
                    optionalMaintenanceCutoff
            );
            cleanup = StockBatchJobRunResponses.completedWithoutWork(
                    HoldingCleanupJob.JOB_NAME,
                    "optional-post-close-maintenance",
                    "Optional post-close maintenance window has closed; security transforms continue",
                    LocalDateTime.now()
            );
        }
        boolean cleanupFailed = !isSuccessful(cleanup);
        if (cleanupFailed) {
            if (cleanup == null || !"FAILED".equals(cleanup.status())) {
                return cleanup;
            }
            log.warn(
                    "Optional holding cleanup failed without blocking required security transforms: "
                            + "cycleId={}, message={}",
                    cycle.id(),
                    cleanup.message()
            );
        }
        StockBatchJobRunResponse transforms = scheduledJobGuard.runBatchIfEnabled(
                CorporateActionJob.JOB_NAME,
                corporateActionsConfigured,
                () -> stockBatchJobLauncher.applyPreOpenSecurityTransforms(
                        cycle.id(),
                        preparingBusinessDate
                )
        );
        if (cleanupFailed) {
            return transforms;
        }
        return combine("preopen-maintenance-and-security-transforms", cleanup, transforms);
    }

    private StockBatchJobRunResponse prepareAutoMarket(
            PostCloseCycle cycle,
            LocalDate preparingBusinessDate
    ) {
        StockBatchJobRunResponse regime = scheduledJobGuard.runBatchIfEnabled(
                AutoMarketDailyRegimePreCreateJob.JOB_NAME,
                dailyRegimeConfigured,
                () -> stockBatchJobLauncher.preCreateAutoMarketDailyRegimesForPostClose(
                        cycle.id(),
                        preparingBusinessDate
                )
        );
        if (!isSuccessful(regime)) {
            return regime;
        }
        StockBatchJobRunResponse queue = scheduledJobGuard.runBatchIfEnabled(
                AutoMarketPreOpenProfileQueueReconcileJob.JOB_NAME,
                profileQueueConfigured,
                () -> stockBatchJobLauncher.reconcileAutoMarketProfileQueueForPreOpen(cycle.id())
        );
        return combine("preopen-auto-market-preparation", regime, queue);
    }

    private boolean resolveReadyCycle(
            PostCloseCycle cycle,
            LocalDate preparingBusinessDate,
            LocalDateTime simulationNow
    ) {
        if (skippedBusinessDateRecoveryService.shouldSkip(
                preparingBusinessDate,
                simulationNow,
                simulationMarketSessionService.closeTime()
        )) {
            return phaseExecutionService.execute(
                    cycle,
                    PostClosePhase.READY_TO_OPEN,
                    PostClosePhase.COMPLETED,
                    () -> {
                        skippedBusinessDateRecoveryService.skipPreparedBusinessDate(
                                cycle.businessDate(),
                                preparingBusinessDate,
                                simulationNow,
                                simulationMarketSessionService.closeTime()
                        );
                        return new StockBatchJobRunResponse(
                                COORDINATOR_JOB,
                                "COMPLETED",
                                "skip-missed-business-date",
                                1,
                                "Missed business date recorded as SKIPPED without opening the market",
                                LocalDateTime.now(),
                                LocalDateTime.now()
                        );
                    }
            );
        }
        if (simulationMarketSessionService.currentSession() != SimulationMarketSession.REGULAR
                || !marketSessionFenceService.isRegularSessionOpen(preparingBusinessDate)) {
            return false;
        }
        return phaseExecutionService.execute(
                cycle,
                PostClosePhase.READY_TO_OPEN,
                PostClosePhase.COMPLETED,
                () -> new StockBatchJobRunResponse(
                        COORDINATOR_JOB,
                        "COMPLETED",
                        "complete-open-cycle",
                        1,
                        "Market session opened for prepared business date",
                        LocalDateTime.now(),
                        LocalDateTime.now()
                )
        );
    }

    private StockBatchJobRunResponse combine(
            String mode,
            StockBatchJobRunResponse first,
            StockBatchJobRunResponse second
    ) {
        if (!isSuccessful(first)) {
            return first;
        }
        if (!isSuccessful(second)) {
            return second;
        }
        LocalDateTime startedAt = first.startedAt().isBefore(second.startedAt())
                ? first.startedAt()
                : second.startedAt();
        LocalDateTime endedAt = first.completedAt().isAfter(second.completedAt())
                ? first.completedAt()
                : second.completedAt();
        return new StockBatchJobRunResponse(
                COORDINATOR_JOB,
                "COMPLETED",
                mode,
                first.processedCount() + second.processedCount(),
                "Post-close composite phase completed",
                startedAt,
                endedAt
        );
    }

    private boolean isSuccessful(StockBatchJobRunResponse response) {
        return response != null
                && (!StockBatchJobRunResponses.isFailed(response))
                && ("COMPLETED".equals(response.status())
                || StockBatchJobRunResponses.isAlreadyCompleteSkip(response));
    }

    private LocalTime parseTime(String value, LocalTime fallback) {
        return value == null || value.isBlank() ? fallback : LocalTime.parse(value);
    }
}
