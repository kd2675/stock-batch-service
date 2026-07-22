package stock.batch.service.scheduler;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.batch.corporateaction.job.CorporateActionJob;
import stock.batch.service.batch.holdingcleanup.job.HoldingCleanupJob;
import stock.batch.service.batch.metadata.job.BatchMetadataRetentionJob;
import stock.batch.service.common.vo.StockBatchJobRunResponse;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;
import stock.batch.service.marketclose.biz.PostCloseCycleService;
import stock.batch.service.marketclose.biz.PostClosePhaseExecutionService;
import stock.batch.service.marketclose.biz.SkippedBusinessDateRecoveryService;
import stock.batch.service.marketclose.model.PostCloseCycle;
import stock.batch.service.marketclose.model.PostCloseCycleKind;
import stock.batch.service.marketclose.model.PostCloseCycleStatus;
import stock.batch.service.marketclose.model.PostClosePhase;
import stock.batch.service.marketclose.model.PostCloseScopeType;
import stock.batch.service.simulation.SimulationMarketSessionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostCloseCoordinatorSchedulerTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 7, 15);

    @Mock
    private StockBatchJobLauncher stockBatchJobLauncher;

    @Mock
    private StockBatchScheduledJobGuard scheduledJobGuard;

    @Mock
    private SimulationMarketSessionService simulationMarketSessionService;

    @Mock
    private MarketSessionFenceService marketSessionFenceService;

    @Mock
    private PostCloseCycleService postCloseCycleService;

    @Mock
    private PostClosePhaseExecutionService phaseExecutionService;

    @Mock
    private SkippedBusinessDateRecoveryService skippedBusinessDateRecoveryService;

    private PostCloseCoordinatorScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new PostCloseCoordinatorScheduler(
                stockBatchJobLauncher,
                scheduledJobGuard,
                simulationMarketSessionService,
                marketSessionFenceService,
                postCloseCycleService,
                phaseExecutionService,
                skippedBusinessDateRecoveryService,
                "04:30",
                "05:30",
                "05:30",
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true
        );
    }

    @Test
    void advanceOnePhase_marketOpen_defersHeavyOvernightWork() {
        PostCloseCycle cycle = cycle(PostClosePhase.OVERNIGHT_CASH_APPLIED);
        when(marketSessionFenceService.hasOpenMarket()).thenReturn(true);

        boolean advanced = scheduler.advanceOnePhase(
                cycle,
                BUSINESS_DATE.plusDays(1).atTime(0, 1)
        );

        assertThat(advanced).isFalse();
        verifyNoInteractions(stockBatchJobLauncher, phaseExecutionService);
    }

    @Test
    void advanceOnePhase_beforeMidnight_doesNotClaimCorporateCashPhase() {
        boolean advanced = scheduler.advanceOnePhase(
                cycle(PostClosePhase.PORTFOLIO_SETTLED),
                BUSINESS_DATE.atTime(23, 59, 59)
        );

        assertThat(advanced).isFalse();
        verifyNoInteractions(stockBatchJobLauncher, phaseExecutionService, marketSessionFenceService);
    }

    @Test
    void advanceOnePhase_atMidnight_appliesCorporateCashBeforeNextDayFunding() {
        PostCloseCycle cycle = cycle(PostClosePhase.PORTFOLIO_SETTLED);
        LocalDateTime simulationNow = BUSINESS_DATE.plusDays(1).atStartOfDay();
        StockBatchJobRunResponse completed = response(
                CorporateActionJob.JOB_NAME,
                "COMPLETED",
                "corporate-cash",
                simulationNow
        );
        when(marketSessionFenceService.hasOpenMarket()).thenReturn(false);
        when(stockBatchJobLauncher.applyCorporateCashActions(cycle.id())).thenReturn(completed);
        doAnswer(invocation -> ((Supplier<StockBatchJobRunResponse>) invocation.getArgument(2)).get())
                .when(scheduledJobGuard)
                .runBatchIfEnabled(eq(CorporateActionJob.JOB_NAME), eq(true), any());
        doAnswer(invocation -> {
            Supplier<StockBatchJobRunResponse> action = invocation.getArgument(3);
            assertThat(action.get()).isSameAs(completed);
            return true;
        }).when(phaseExecutionService).execute(
                eq(cycle),
                eq(PostClosePhase.PORTFOLIO_SETTLED),
                eq(PostClosePhase.CORPORATE_CASH_APPLIED),
                any()
        );

        assertThat(scheduler.advanceOnePhase(cycle, simulationNow)).isTrue();
    }

    @Test
    void advanceOnePhase_optionalMaintenanceFailures_doNotBlockPreOpenTransforms() {
        PostCloseCycle cycle = cycle(PostClosePhase.REPORTS_AGGREGATED);
        LocalDateTime simulationNow = BUSINESS_DATE.plusDays(1).atTime(4, 30);
        StockBatchJobRunResponse failedRetention = response(
                BatchMetadataRetentionJob.JOB_NAME,
                "FAILED",
                "metadata unavailable",
                simulationNow
        );
        StockBatchJobRunResponse failedCleanup = response(
                HoldingCleanupJob.JOB_NAME,
                "FAILED",
                "cleanup unavailable",
                simulationNow
        );
        StockBatchJobRunResponse completedTransforms = response(
                CorporateActionJob.JOB_NAME,
                "COMPLETED",
                "transforms completed",
                simulationNow
        );
        when(simulationMarketSessionService.currentSimulationDateTime()).thenReturn(simulationNow);
        when(marketSessionFenceService.hasOpenMarket()).thenReturn(false);
        when(stockBatchJobLauncher.retainBatchMetadataForPostClose(cycle.id())).thenReturn(failedRetention);
        when(stockBatchJobLauncher.cleanupEmptyHoldingsForPostClose(cycle.id())).thenReturn(failedCleanup);
        when(stockBatchJobLauncher.applyPreOpenSecurityTransforms(cycle.id(), BUSINESS_DATE.plusDays(1)))
                .thenReturn(completedTransforms);
        doAnswer(invocation -> ((Supplier<StockBatchJobRunResponse>) invocation.getArgument(2)).get())
                .when(scheduledJobGuard)
                .runOptionalBatchIfEnabled(eq(BatchMetadataRetentionJob.JOB_NAME), eq(true), any());
        doAnswer(invocation -> ((Supplier<StockBatchJobRunResponse>) invocation.getArgument(2)).get())
                .when(scheduledJobGuard)
                .runOptionalBatchIfEnabled(eq(HoldingCleanupJob.JOB_NAME), eq(true), any());
        doAnswer(invocation -> ((Supplier<StockBatchJobRunResponse>) invocation.getArgument(2)).get())
                .when(scheduledJobGuard)
                .runBatchIfEnabled(eq(CorporateActionJob.JOB_NAME), eq(true), any());
        doAnswer(invocation -> {
            Supplier<StockBatchJobRunResponse> action = invocation.getArgument(3);
            assertThat(action.get().status()).isEqualTo("COMPLETED");
            return true;
        }).when(phaseExecutionService).execute(
                eq(cycle),
                eq(PostClosePhase.REPORTS_AGGREGATED),
                eq(PostClosePhase.PREOPEN_SECURITY_TRANSFORMS_APPLIED),
                any()
        );

        boolean advanced = scheduler.advanceOnePhase(cycle, simulationNow);

        assertThat(advanced).isTrue();
        verify(stockBatchJobLauncher).applyPreOpenSecurityTransforms(cycle.id(), BUSINESS_DATE.plusDays(1));
    }

    @Test
    void advanceOnePhase_optionalCleanupSkipped_doesNotRunRequiredTransforms() {
        PostCloseCycle cycle = cycle(PostClosePhase.REPORTS_AGGREGATED);
        LocalDateTime simulationNow = BUSINESS_DATE.plusDays(1).atTime(4, 30);
        StockBatchJobRunResponse completedRetention = response(
                BatchMetadataRetentionJob.JOB_NAME,
                "COMPLETED",
                "retention completed",
                simulationNow
        );
        StockBatchJobRunResponse skippedCleanup = response(
                HoldingCleanupJob.JOB_NAME,
                "SKIPPED",
                "Batch service is shutting down",
                simulationNow
        );
        when(simulationMarketSessionService.currentSimulationDateTime()).thenReturn(simulationNow);
        when(marketSessionFenceService.hasOpenMarket()).thenReturn(false);
        when(stockBatchJobLauncher.retainBatchMetadataForPostClose(cycle.id()))
                .thenReturn(completedRetention);
        when(stockBatchJobLauncher.cleanupEmptyHoldingsForPostClose(cycle.id()))
                .thenReturn(skippedCleanup);
        doAnswer(invocation -> ((Supplier<StockBatchJobRunResponse>) invocation.getArgument(2)).get())
                .when(scheduledJobGuard)
                .runOptionalBatchIfEnabled(eq(BatchMetadataRetentionJob.JOB_NAME), eq(true), any());
        doAnswer(invocation -> ((Supplier<StockBatchJobRunResponse>) invocation.getArgument(2)).get())
                .when(scheduledJobGuard)
                .runOptionalBatchIfEnabled(eq(HoldingCleanupJob.JOB_NAME), eq(true), any());
        doAnswer(invocation -> ((Supplier<StockBatchJobRunResponse>) invocation.getArgument(2)).get())
                .when(scheduledJobGuard)
                .runBatchIfEnabled(eq(CorporateActionJob.JOB_NAME), eq(true), any());
        doAnswer(invocation -> {
            Supplier<StockBatchJobRunResponse> action = invocation.getArgument(3);
            assertThat(action.get().status()).isEqualTo("SKIPPED");
            return false;
        }).when(phaseExecutionService).execute(
                eq(cycle),
                eq(PostClosePhase.REPORTS_AGGREGATED),
                eq(PostClosePhase.PREOPEN_SECURITY_TRANSFORMS_APPLIED),
                any()
        );

        boolean advanced = scheduler.advanceOnePhase(cycle, simulationNow);

        assertThat(advanced).isFalse();
        verify(stockBatchJobLauncher, never())
                .applyPreOpenSecurityTransforms(anyLong(), any(LocalDate.class));
    }

    @Test
    void advanceOnePhase_metadataRetentionSkipped_doesNotStartMoreMaintenanceOrTransforms() {
        PostCloseCycle cycle = cycle(PostClosePhase.REPORTS_AGGREGATED);
        LocalDateTime simulationNow = BUSINESS_DATE.plusDays(1).atTime(4, 30);
        StockBatchJobRunResponse skippedRetention = response(
                BatchMetadataRetentionJob.JOB_NAME,
                "SKIPPED",
                "Batch service is shutting down",
                simulationNow
        );
        when(marketSessionFenceService.hasOpenMarket()).thenReturn(false);
        when(stockBatchJobLauncher.retainBatchMetadataForPostClose(cycle.id()))
                .thenReturn(skippedRetention);
        doAnswer(invocation -> ((Supplier<StockBatchJobRunResponse>) invocation.getArgument(2)).get())
                .when(scheduledJobGuard)
                .runOptionalBatchIfEnabled(eq(BatchMetadataRetentionJob.JOB_NAME), eq(true), any());
        doAnswer(invocation -> ((Supplier<StockBatchJobRunResponse>) invocation.getArgument(2)).get())
                .when(scheduledJobGuard)
                .runBatchIfEnabled(eq(CorporateActionJob.JOB_NAME), eq(true), any());
        doAnswer(invocation -> {
            Supplier<StockBatchJobRunResponse> action = invocation.getArgument(3);
            assertThat(action.get().status()).isEqualTo("SKIPPED");
            return false;
        }).when(phaseExecutionService).execute(
                eq(cycle),
                eq(PostClosePhase.REPORTS_AGGREGATED),
                eq(PostClosePhase.PREOPEN_SECURITY_TRANSFORMS_APPLIED),
                any()
        );

        boolean advanced = scheduler.advanceOnePhase(cycle, simulationNow);

        assertThat(advanced).isFalse();
        verify(scheduledJobGuard, never()).runOptionalBatchIfEnabled(
                eq(HoldingCleanupJob.JOB_NAME),
                eq(true),
                any()
        );
        verify(stockBatchJobLauncher, never())
                .applyPreOpenSecurityTransforms(anyLong(), any(LocalDate.class));
    }

    @Test
    void advanceOnePhase_metadataRetentionCrossesCutoff_skipsCleanupAndRunsRequiredTransforms() {
        PostCloseCycle cycle = cycle(PostClosePhase.REPORTS_AGGREGATED);
        LocalDateTime simulationNow = BUSINESS_DATE.plusDays(1).atTime(4, 30);
        LocalDateTime afterRetention = BUSINESS_DATE.plusDays(1).atTime(5, 0);
        StockBatchJobRunResponse completedRetention = response(
                BatchMetadataRetentionJob.JOB_NAME,
                "COMPLETED",
                "retention completed",
                afterRetention
        );
        StockBatchJobRunResponse completedTransforms = response(
                CorporateActionJob.JOB_NAME,
                "COMPLETED",
                "transforms completed",
                afterRetention
        );
        when(simulationMarketSessionService.currentSimulationDateTime()).thenReturn(afterRetention);
        when(marketSessionFenceService.hasOpenMarket()).thenReturn(false);
        when(stockBatchJobLauncher.retainBatchMetadataForPostClose(cycle.id()))
                .thenReturn(completedRetention);
        when(stockBatchJobLauncher.applyPreOpenSecurityTransforms(cycle.id(), BUSINESS_DATE.plusDays(1)))
                .thenReturn(completedTransforms);
        doAnswer(invocation -> ((Supplier<StockBatchJobRunResponse>) invocation.getArgument(2)).get())
                .when(scheduledJobGuard)
                .runOptionalBatchIfEnabled(eq(BatchMetadataRetentionJob.JOB_NAME), eq(true), any());
        doAnswer(invocation -> ((Supplier<StockBatchJobRunResponse>) invocation.getArgument(2)).get())
                .when(scheduledJobGuard)
                .runBatchIfEnabled(eq(CorporateActionJob.JOB_NAME), eq(true), any());
        doAnswer(invocation -> {
            Supplier<StockBatchJobRunResponse> action = invocation.getArgument(3);
            assertThat(action.get().status()).isEqualTo("COMPLETED");
            return true;
        }).when(phaseExecutionService).execute(
                eq(cycle),
                eq(PostClosePhase.REPORTS_AGGREGATED),
                eq(PostClosePhase.PREOPEN_SECURITY_TRANSFORMS_APPLIED),
                any()
        );

        boolean advanced = scheduler.advanceOnePhase(cycle, simulationNow);

        assertThat(advanced).isTrue();
        verify(scheduledJobGuard, never()).runOptionalBatchIfEnabled(
                eq(HoldingCleanupJob.JOB_NAME),
                eq(true),
                any()
        );
        verify(stockBatchJobLauncher).applyPreOpenSecurityTransforms(cycle.id(), BUSINESS_DATE.plusDays(1));
    }

    @Test
    void advanceOnePhase_afterOptionalMaintenanceCutoff_skipsMaintenanceAndRunsRequiredTransforms() {
        PostCloseCycle cycle = cycle(PostClosePhase.REPORTS_AGGREGATED);
        LocalDateTime simulationNow = BUSINESS_DATE.plusDays(1).atTime(5, 0);
        StockBatchJobRunResponse completedTransforms = response(
                CorporateActionJob.JOB_NAME,
                "COMPLETED",
                "transforms completed",
                simulationNow
        );
        when(marketSessionFenceService.hasOpenMarket()).thenReturn(false);
        when(stockBatchJobLauncher.applyPreOpenSecurityTransforms(cycle.id(), BUSINESS_DATE.plusDays(1)))
                .thenReturn(completedTransforms);
        doAnswer(invocation -> ((Supplier<StockBatchJobRunResponse>) invocation.getArgument(2)).get())
                .when(scheduledJobGuard)
                .runBatchIfEnabled(eq(CorporateActionJob.JOB_NAME), eq(true), any());
        doAnswer(invocation -> {
            Supplier<StockBatchJobRunResponse> action = invocation.getArgument(3);
            action.get();
            return true;
        }).when(phaseExecutionService).execute(
                eq(cycle),
                eq(PostClosePhase.REPORTS_AGGREGATED),
                eq(PostClosePhase.PREOPEN_SECURITY_TRANSFORMS_APPLIED),
                any()
        );

        boolean advanced = scheduler.advanceOnePhase(cycle, simulationNow);

        assertThat(advanced).isTrue();
        verify(scheduledJobGuard, never()).runOptionalBatchIfEnabled(
                eq(BatchMetadataRetentionJob.JOB_NAME),
                eq(true),
                any()
        );
        verify(scheduledJobGuard, never()).runOptionalBatchIfEnabled(
                eq(HoldingCleanupJob.JOB_NAME),
                eq(true),
                any()
        );
        verify(stockBatchJobLauncher).applyPreOpenSecurityTransforms(cycle.id(), BUSINESS_DATE.plusDays(1));
    }

    @Test
    void advanceOnePhase_cashPolicyPhase_bypassesRuntimeGuardAndDelegatesNoOpDecisionToLauncher() {
        PostCloseCycle cycle = cycle(PostClosePhase.CORPORATE_CASH_APPLIED);
        LocalDateTime simulationNow = BUSINESS_DATE.plusDays(1).atTime(0, 1);
        StockBatchJobRunResponse completed = new StockBatchJobRunResponse(
                "auto-participant-cash-flow",
                "COMPLETED",
                "post-close-recurring-cash",
                0,
                "Automatic recurring cash is disabled",
                simulationNow,
                simulationNow
        );
        when(marketSessionFenceService.hasOpenMarket()).thenReturn(false);
        when(stockBatchJobLauncher.fundAutoParticipantsForPostClose(cycle.id())).thenReturn(completed);
        doAnswer(invocation -> {
            Supplier<StockBatchJobRunResponse> action = invocation.getArgument(3);
            assertThat(action.get()).isSameAs(completed);
            return true;
        }).when(phaseExecutionService).execute(
                eq(cycle),
                eq(PostClosePhase.CORPORATE_CASH_APPLIED),
                eq(PostClosePhase.OVERNIGHT_CASH_APPLIED),
                any()
        );

        assertThat(scheduler.advanceOnePhase(cycle, simulationNow)).isTrue();
        verifyNoInteractions(scheduledJobGuard);
    }

    @Test
    void advanceOnePhase_readyDateAlreadyMissed_recordsSkipWithoutOpeningMarket() {
        PostCloseCycle cycle = cycle(PostClosePhase.READY_TO_OPEN);
        LocalDate skippedDate = BUSINESS_DATE.plusDays(1);
        LocalDateTime simulationNow = skippedDate.plusDays(1).atTime(2, 0);
        when(simulationMarketSessionService.closeTime()).thenReturn(LocalTime.of(18, 0));
        when(skippedBusinessDateRecoveryService.shouldSkip(
                skippedDate,
                simulationNow,
                LocalTime.of(18, 0)
        )).thenReturn(true);
        doAnswer(invocation -> {
            Supplier<StockBatchJobRunResponse> action = invocation.getArgument(3);
            action.get();
            return true;
        }).when(phaseExecutionService).execute(
                eq(cycle),
                eq(PostClosePhase.READY_TO_OPEN),
                eq(PostClosePhase.COMPLETED),
                any()
        );

        boolean advanced = scheduler.advanceOnePhase(cycle, simulationNow);

        assertThat(advanced).isTrue();
        verify(skippedBusinessDateRecoveryService).skipPreparedBusinessDate(
                BUSINESS_DATE,
                skippedDate,
                simulationNow,
                LocalTime.of(18, 0)
        );
        verify(marketSessionFenceService, never()).isRegularSessionOpen(any());
    }

    @Test
    void advanceOldestCycle_noIncompleteCycle_recoversAtMostOneMissedDate() {
        LocalDateTime simulationNow = BUSINESS_DATE.plusDays(3).atTime(5, 0);
        when(simulationMarketSessionService.currentSimulationDateTime()).thenReturn(simulationNow);
        when(simulationMarketSessionService.closeTime()).thenReturn(LocalTime.of(18, 0));
        when(postCloseCycleService.findOldestIncompleteFullMarketCycle()).thenReturn(Optional.empty());
        when(skippedBusinessDateRecoveryService.recoverNextMissedBusinessDate(
                simulationNow,
                LocalTime.of(18, 0)
        )).thenReturn(Optional.of(BUSINESS_DATE.plusDays(1)));

        scheduler.advanceOldestCycle();

        verify(skippedBusinessDateRecoveryService).recoverNextMissedBusinessDate(
                simulationNow,
                LocalTime.of(18, 0)
        );
        verifyNoInteractions(stockBatchJobLauncher, phaseExecutionService);
    }

    @Test
    void advanceOldestCycle_marketOpenWithoutIncompleteCycle_doesNotRecoverMissedDate() {
        when(marketSessionFenceService.hasOpenMarket()).thenReturn(true);
        when(simulationMarketSessionService.currentSimulationDateTime())
                .thenReturn(BUSINESS_DATE.plusDays(1).atTime(7, 0));
        when(postCloseCycleService.findOldestIncompleteFullMarketCycle()).thenReturn(Optional.empty());

        scheduler.advanceOldestCycle();

        verify(postCloseCycleService).findOldestIncompleteFullMarketCycle();
        verifyNoInteractions(skippedBusinessDateRecoveryService, stockBatchJobLauncher, phaseExecutionService);
    }

    @Test
    void advanceOldestCycle_marketOpenReadyCycle_completesCycle() {
        PostCloseCycle cycle = cycle(PostClosePhase.READY_TO_OPEN);
        LocalDate preparingDate = BUSINESS_DATE.plusDays(1);
        LocalDateTime simulationNow = preparingDate.atTime(7, 0);
        when(simulationMarketSessionService.currentSimulationDateTime()).thenReturn(simulationNow);
        when(simulationMarketSessionService.currentSession()).thenReturn(
                web.common.core.simulation.SimulationMarketSession.REGULAR
        );
        when(simulationMarketSessionService.closeTime()).thenReturn(LocalTime.of(18, 0));
        when(postCloseCycleService.findOldestIncompleteFullMarketCycle()).thenReturn(Optional.of(cycle));
        when(marketSessionFenceService.hasOpenMarket()).thenReturn(true);
        when(marketSessionFenceService.isRegularSessionOpen(preparingDate)).thenReturn(true);

        scheduler.advanceOldestCycle();

        verify(phaseExecutionService).execute(
                eq(cycle),
                eq(PostClosePhase.READY_TO_OPEN),
                eq(PostClosePhase.COMPLETED),
                any()
        );
    }

    private PostCloseCycle cycle(PostClosePhase phase) {
        return new PostCloseCycle(
                10L,
                BUSINESS_DATE,
                PostCloseScopeType.FULL_MARKET,
                "ALL",
                PostCloseCycleKind.TRADING,
                null,
                phase,
                PostCloseCycleStatus.PENDING,
                1,
                0L,
                20L,
                BUSINESS_DATE.atTime(18, 10),
                0,
                null,
                null,
                null
        );
    }

    private StockBatchJobRunResponse response(
            String job,
            String status,
            String message,
            LocalDateTime now
    ) {
        return new StockBatchJobRunResponse(job, status, "test", 0, message, now, now);
    }
}
