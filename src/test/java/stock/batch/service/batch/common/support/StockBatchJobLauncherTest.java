package stock.batch.service.batch.common.support;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import web.common.core.simulation.SimulationMarketSession;

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
import stock.batch.service.marketclose.model.PostCloseCycleStatus;
import stock.batch.service.marketclose.model.PostClosePhase;
import stock.batch.service.marketclose.model.PostCloseScopeType;
import stock.batch.service.simulation.SimulationClockService;
import stock.batch.service.simulation.SimulationMarketSessionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockBatchJobLauncherTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 7, 14);
    private static final LocalDateTime SIMULATION_NOW = LocalDateTime.of(2026, 7, 14, 19, 7, 42);

    private final StockBatchJobRunner runner = mock(StockBatchJobRunner.class);
    private final AutoParticipantCashFlowRuntimeControl cashFlowRuntimeControl =
            mock(AutoParticipantCashFlowRuntimeControl.class);
    private final SimulationClockService simulationClockService = mock(SimulationClockService.class);
    private final SimulationMarketSessionService sessionService = mock(SimulationMarketSessionService.class);
    private final MarketSessionFenceService marketSessionFenceService = mock(MarketSessionFenceService.class);
    private final PostCloseCycleService postCloseCycleService = mock(PostCloseCycleService.class);
    private final MarketDataRefreshJob marketDataRefreshTask = mock(MarketDataRefreshJob.class);
    private final OrderBookExecutionJob orderBookExecutionTask = mock(OrderBookExecutionJob.class);
    private final AutoMarketProfileQueueReconcileJob reconcileTask = mock(AutoMarketProfileQueueReconcileJob.class);
    private final AutoMarketPreOpenProfileQueueReconcileJob preOpenReconcileTask =
            mock(AutoMarketPreOpenProfileQueueReconcileJob.class);
    private final AutoMarketJob autoMarketTask = mock(AutoMarketJob.class);
    private final AutoMarketOrderExpiryJob expiryTask = mock(AutoMarketOrderExpiryJob.class);
    private final ListingAutoMarketJob listingTask = mock(ListingAutoMarketJob.class);
    private final HoldingCleanupJob holdingCleanupTask = mock(HoldingCleanupJob.class);
    private final BatchMetadataRetentionJob metadataRetentionTask = mock(BatchMetadataRetentionJob.class);
    private final Job cashFlowJob = job(AutoParticipantCashFlowJob.JOB_NAME);
    private final Job dailyRegimeJob = job(AutoMarketDailyRegimePreCreateJob.JOB_NAME);
    private final Job settlementJob = job(PortfolioSettlementJob.JOB_NAME);
    private final Job marketCloseJob = job(MarketCloseRolloverJob.JOB_NAME);
    private final Job corporateActionJob = job(CorporateActionJob.JOB_NAME);
    private final Job postCloseReportAggregationJob = job(PostCloseReportAggregationJob.JOB_NAME);
    private final Job marketOpenReadinessJob = job(MarketOpenReadinessJob.JOB_NAME);

    private StockBatchJobLauncher launcher;

    @BeforeEach
    void setUp() {
        when(simulationClockService.currentDate()).thenReturn(BUSINESS_DATE);
        when(simulationClockService.currentMarketDateTime()).thenReturn(SIMULATION_NOW);
        when(marketSessionFenceService.activeBusinessDate()).thenReturn(BUSINESS_DATE);
        when(sessionService.closeTime()).thenReturn(LocalTime.of(18, 0));
        when(sessionService.currentSession()).thenReturn(SimulationMarketSession.AFTER_CLOSE);
        when(sessionService.isAfterCloseSession()).thenReturn(true);
        when(sessionService.isRegularSession()).thenReturn(false);
        when(cashFlowRuntimeControl.canRunManualCashFlow()).thenReturn(true);
        when(postCloseCycleService.ensureFullMarketCycle(eq(BUSINESS_DATE), any(LocalDateTime.class)))
                .thenReturn(cycle(501L, PostCloseScopeType.FULL_MARKET, "ALL"));
        when(postCloseCycleService.ensureSymbolCycle(eq(BUSINESS_DATE), any(), any(LocalDateTime.class)))
                .thenReturn(cycle(502L, PostCloseScopeType.SYMBOL, "DEMO002"));
        when(postCloseCycleService.findFullMarketCycle(BUSINESS_DATE))
                .thenReturn(Optional.of(cycle(501L, PostCloseScopeType.FULL_MARKET, "ALL")));
        when(postCloseCycleService.findById(501L))
                .thenReturn(Optional.of(cycle(501L, PostCloseScopeType.FULL_MARKET, "ALL")));
        when(postCloseCycleService.isPhaseClaimEligible(any(PostCloseCycle.class), any(LocalDateTime.class)))
                .thenReturn(true);
        launcher = launcher(false);
    }

    private StockBatchJobLauncher launcher(boolean postCloseCoordinatorEnabled) {
        return new StockBatchJobLauncher(
                runner,
                cashFlowRuntimeControl,
                simulationClockService,
                sessionService,
                marketSessionFenceService,
                postCloseCycleService,
                marketDataRefreshTask,
                orderBookExecutionTask,
                reconcileTask,
                preOpenReconcileTask,
                autoMarketTask,
                expiryTask,
                listingTask,
                holdingCleanupTask,
                metadataRetentionTask,
                cashFlowJob,
                dailyRegimeJob,
                settlementJob,
                marketCloseJob,
                corporateActionJob,
                postCloseReportAggregationJob,
                marketOpenReadinessJob,
                postCloseCoordinatorEnabled
        );
    }

    @Test
    void lightweightMethods_routeWithoutNativeJobMetadata() {
        launcher.refreshMarketData();
        launcher.executeOrderBookOrders();
        launcher.reconcileAutoMarketProfileQueue();
        launcher.runAutoMarket();
        launcher.expireAutoMarketOrders();
        launcher.runListingAutoMarket();
        launcher.cleanupEmptyHoldings();

        verify(runner).run(marketDataRefreshTask);
        verify(runner).run(orderBookExecutionTask);
        verify(runner).run(reconcileTask);
        verify(runner).run(autoMarketTask);
        verify(runner).run(expiryTask);
        verify(runner).run(listingTask);
        verify(runner).run(holdingCleanupTask);
    }

    @Test
    void coordinatorEnabled_legacyMaintenanceEndpointsSkipBeforeMetadataAndBusinessTables() {
        launcher = launcher(true);

        StockBatchJobRunResponse marketDataResponse = launcher.refreshMarketData();
        StockBatchJobRunResponse corporateActionResponse = launcher.applyCorporateActions();
        StockBatchJobRunResponse scheduledCorporateActionResponse = launcher.applyCorporateActionsScheduled();
        StockBatchJobRunResponse recurringCashResponse = launcher.fundAutoParticipants();
        StockBatchJobRunResponse dailyRegimeResponse = launcher.preCreateAutoMarketDailyRegimes();
        StockBatchJobRunResponse profileQueueResponse = launcher.reconcileAutoMarketProfileQueue();
        StockBatchJobRunResponse holdingCleanupResponse = launcher.cleanupEmptyHoldings();

        assertThat(marketDataResponse.status()).isEqualTo("SKIPPED");
        assertThat(corporateActionResponse.status()).isEqualTo("SKIPPED");
        assertThat(scheduledCorporateActionResponse.status()).isEqualTo("SKIPPED");
        assertThat(recurringCashResponse.status()).isEqualTo("SKIPPED");
        assertThat(dailyRegimeResponse.status()).isEqualTo("SKIPPED");
        assertThat(profileQueueResponse.status()).isEqualTo("SKIPPED");
        assertThat(holdingCleanupResponse.status()).isEqualTo("SKIPPED");
        assertThat(corporateActionResponse.message()).contains("coordinator");
        verify(runner, never()).run(marketDataRefreshTask);
        verify(runner, never()).run(reconcileTask);
        verify(runner, never()).run(holdingCleanupTask);
        verify(runner, never()).run(eq(cashFlowJob), any(), any());
        verify(runner, never()).run(eq(dailyRegimeJob), any(), any());
        verify(runner, never()).run(eq(corporateActionJob), any(), any());
    }

    @Test
    void coordinatorEnabled_regularOpenMarket_allowsBoundedProfileQueueRecovery() {
        launcher = launcher(true);
        when(sessionService.isRegularSession()).thenReturn(true);
        when(marketSessionFenceService.hasOpenOrderBookMarket()).thenReturn(true);

        launcher.reconcileAutoMarketProfileQueue();

        verify(runner).run(reconcileTask);
    }

    @Test
    void postCloseLightweightMethods_carryCycleLeaseIdentity() {
        when(postCloseCycleService.findById(501L)).thenReturn(
                Optional.of(cycle(
                        501L,
                        PostCloseScopeType.FULL_MARKET,
                        "ALL",
                        PostClosePhase.PREOPEN_SECURITY_TRANSFORMS_APPLIED
                )),
                Optional.of(cycle(
                        501L,
                        PostCloseScopeType.FULL_MARKET,
                        "ALL",
                        PostClosePhase.REPORTS_AGGREGATED
                )),
                Optional.of(cycle(
                        501L,
                        PostCloseScopeType.FULL_MARKET,
                        "ALL",
                        PostClosePhase.REPORTS_AGGREGATED
                )),
                Optional.of(cycle(
                        501L,
                        PostCloseScopeType.FULL_MARKET,
                        "ALL",
                        PostClosePhase.MARKET_DATA_PREPARED
                ))
        );

        launcher.refreshMarketDataForPostClose(501L);
        launcher.cleanupEmptyHoldingsForPostClose(501L);
        launcher.retainBatchMetadataForPostClose(501L);
        launcher.reconcileAutoMarketProfileQueueForPreOpen(501L);

        verify(runner).run(marketDataRefreshTask, 501L);
        verify(runner).run(holdingCleanupTask, 501L);
        verify(runner).run(metadataRetentionTask, 501L);
        verify(runner).run(preOpenReconcileTask, 501L);
    }

    @Test
    void preCreateDailyRegime_usesOneJobInstancePerBusinessDate() {
        launcher.preCreateAutoMarketDailyRegimes();

        JobParameters parameters = captureParameters(dailyRegimeJob, "daily-regime");
        assertThat(parameters.getLocalDate(StockBatchJobParameters.BUSINESS_DATE)).isEqualTo(BUSINESS_DATE);
        assertThat(parameters.getParameter(StockBatchJobParameters.BUSINESS_DATE).identifying()).isTrue();
        assertThat(parameters.getParameter(StockBatchJobParameters.TRIGGERED_AT).identifying()).isFalse();
        assertThat(parameters.getParameter("runId")).isNull();
    }

    @Test
    void scheduledCashFlow_usesSimulationMinuteAsRestartableWindow() {
        launcher.fundAutoParticipants();

        JobParameters parameters = captureParameters(cashFlowJob, "recurring-cash");
        assertThat(parameters.getString(StockBatchJobParameters.OPERATION))
                .isEqualTo(AutoParticipantCashFlowJob.OPERATION_SCHEDULED);
        assertThat(parameters.getLocalDateTime(StockBatchJobParameters.SWEEP_AT))
                .isEqualTo(LocalDateTime.of(2026, 7, 14, 19, 7));
        assertThat(parameters.getParameter(StockBatchJobParameters.SWEEP_AT).identifying()).isTrue();
    }

    @Test
    void postCloseCashFlow_whenAutomaticDisabled_completesPhaseWithoutMetadataOrPayment() {
        when(simulationClockService.currentMarketDateTime())
                .thenReturn(BUSINESS_DATE.plusDays(1).atStartOfDay());
        when(cashFlowRuntimeControl.shouldRunScheduledJob()).thenReturn(false);
        when(postCloseCycleService.findById(501L)).thenReturn(Optional.of(cycle(
                501L,
                PostCloseScopeType.FULL_MARKET,
                "ALL",
                PostClosePhase.CORPORATE_CASH_APPLIED
        )));

        StockBatchJobRunResponse response = launcher.fundAutoParticipantsForPostClose(501L);

        assertThat(response)
                .extracting(StockBatchJobRunResponse::status, StockBatchJobRunResponse::processedCount)
                .containsExactly("COMPLETED", 0);
        verify(runner, never()).run(eq(cashFlowJob), any(), any());
    }

    @Test
    void postCloseCashFlow_whenAutomaticEnabled_usesStableCycleIdentity() {
        when(simulationClockService.currentMarketDateTime())
                .thenReturn(BUSINESS_DATE.plusDays(1).atStartOfDay());
        when(cashFlowRuntimeControl.shouldRunScheduledJob()).thenReturn(true);
        when(postCloseCycleService.findById(501L)).thenReturn(Optional.of(cycle(
                501L,
                PostCloseScopeType.FULL_MARKET,
                "ALL",
                PostClosePhase.CORPORATE_CASH_APPLIED
        )));

        launcher.fundAutoParticipantsForPostClose(501L);

        JobParameters parameters = captureParameters(cashFlowJob, "post-close-recurring-cash");
        assertThat(parameters.getLong(StockBatchJobParameters.CYCLE_ID)).isEqualTo(501L);
        assertThat(parameters.getParameter(StockBatchJobParameters.CYCLE_ID).identifying()).isTrue();
        assertThat(parameters.getParameter(StockBatchJobParameters.SWEEP_AT).identifying()).isFalse();
    }

    @Test
    void postCloseCashFlow_beforeMidnight_defersWithoutMetadataOrPayment() {
        when(postCloseCycleService.findById(501L)).thenReturn(Optional.of(cycle(
                501L,
                PostCloseScopeType.FULL_MARKET,
                "ALL",
                PostClosePhase.CORPORATE_CASH_APPLIED
        )));

        StockBatchJobRunResponse response = launcher.fundAutoParticipantsForPostClose(501L);

        assertThat(response.status()).isEqualTo("SKIPPED");
        assertThat(response.message()).contains("2026-07-15T00:00");
        verify(runner, never()).run(eq(cashFlowJob), any(), any());
    }

    @Test
    void postClosePhaseMismatch_rejectsBeforeStartingHeavyTask() {
        assertThatThrownBy(() -> launcher.refreshMarketDataForPostClose(501L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires PREOPEN_SECURITY_TRANSFORMS_APPLIED");

        verify(runner, never()).run(marketDataRefreshTask, 501L);
    }

    @Test
    void manualCashFlowSignal_keepsSignalIdAsNonIdentifyingAuditParameter() {
        prepareManualCashFlowOvernight(BUSINESS_DATE, 501L);

        launcher.fundAutoParticipantsManually(41L);

        JobParameters parameters = captureParameters(cashFlowJob, "manual-recurring-cash");
        assertThat(parameters.getLong(StockBatchJobParameters.SIGNAL_ID)).isEqualTo(41L);
        assertThat(parameters.getParameter(StockBatchJobParameters.SIGNAL_ID).identifying()).isFalse();
        assertThat(parameters.getString(StockBatchJobParameters.REQUEST_ID)).isEqualTo("db-signal:41");
        assertThat(parameters.getParameter(StockBatchJobParameters.REQUEST_ID).identifying()).isFalse();
    }

    @Test
    void manualCashFlowSignal_rawDateAdvanced_keepsRequestedBusinessDate() {
        LocalDate requestedBusinessDate = BUSINESS_DATE.minusDays(1);
        when(marketSessionFenceService.activeBusinessDate()).thenReturn(requestedBusinessDate);
        when(simulationClockService.currentMarketDateTime())
                .thenReturn(requestedBusinessDate.plusDays(1).atTime(0, 5));
        when(sessionService.currentSession()).thenReturn(SimulationMarketSession.PRE_OPEN);
        PostCloseCycle requestedCycle = cycle(
                490L,
                requestedBusinessDate,
                PostCloseScopeType.FULL_MARKET,
                "ALL",
                PostClosePhase.CORPORATE_CASH_APPLIED
        );
        when(postCloseCycleService.findFullMarketCycle(requestedBusinessDate))
                .thenReturn(Optional.of(requestedCycle));

        launcher.fundAutoParticipantsManually(requestedBusinessDate, 490L, 41L);

        JobParameters parameters = captureParameters(cashFlowJob, "manual-recurring-cash");
        assertThat(parameters.getLocalDate(StockBatchJobParameters.BUSINESS_DATE))
                .isEqualTo(requestedBusinessDate);
    }

    @Test
    void manualCashFlowSignal_activeDateAdvanced_rejectsBeforeCycleAndMetadata() {
        LocalDate requestedBusinessDate = BUSINESS_DATE.minusDays(1);

        assertThatThrownBy(() -> launcher.fundAutoParticipantsManually(requestedBusinessDate, 490L, 41L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is stale");

        verify(postCloseCycleService, never()).findFullMarketCycle(requestedBusinessDate);
        verify(runner, never()).run(eq(cashFlowJob), any(), any());
    }

    @Test
    void manualCashFlowSignal_expectedCycleMismatch_rejectsBeforeMetadata() {
        when(simulationClockService.currentMarketDateTime())
                .thenReturn(BUSINESS_DATE.plusDays(1).atTime(0, 5));
        when(postCloseCycleService.findFullMarketCycle(BUSINESS_DATE))
                .thenReturn(Optional.of(cycle(
                        501L,
                        PostCloseScopeType.FULL_MARKET,
                        "ALL",
                        PostClosePhase.PORTFOLIO_SETTLED
                )));

        assertThatThrownBy(() -> launcher.fundAutoParticipantsManually(BUSINESS_DATE, 999L, 41L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");

        verify(runner, never()).run(eq(cashFlowJob), any(), any());
    }

    @Test
    void manualCashFlow_whenAutomaticEnabled_skipsBeforeCreatingMetadata() {
        when(cashFlowRuntimeControl.canRunManualCashFlow()).thenReturn(false);

        StockBatchJobRunResponse response = launcher.fundAutoParticipantsManually(41L);

        assertThat(response.status()).isEqualTo("SKIPPED");
        verify(marketSessionFenceService, never()).activeBusinessDate();
        verify(runner, never()).run(eq(cashFlowJob), any(), any());
    }

    @Test
    void manualCashFlow_beforeClose_skipsBeforeCreatingMetadata() {
        when(sessionService.currentSession()).thenReturn(SimulationMarketSession.REGULAR);

        StockBatchJobRunResponse response = launcher.fundAutoParticipantsManually(41L);

        assertThat(response.status()).isEqualTo("SKIPPED");
        verify(marketSessionFenceService, never()).activeBusinessDate();
        verify(runner, never()).run(eq(cashFlowJob), any(), any());
    }

    @Test
    void manualCashFlow_afterCloseBeforeMidnight_defersWithoutCycleOrMetadata() {
        StockBatchJobRunResponse response = launcher.fundAutoParticipantsManually(41L);

        assertThat(response.status()).isEqualTo("SKIPPED");
        assertThat(response.message()).contains("overnight window", "2026-07-15T00:00");
        verify(postCloseCycleService, never()).findFullMarketCycle(BUSINESS_DATE);
        verify(runner, never()).run(eq(cashFlowJob), any(), any());
    }

    @Test
    void manualCashFlow_afterMidnightBeforeCorporateCash_defersWithoutMetadata() {
        when(simulationClockService.currentMarketDateTime())
                .thenReturn(BUSINESS_DATE.plusDays(1).atTime(0, 5));
        when(sessionService.currentSession()).thenReturn(SimulationMarketSession.PRE_OPEN);
        when(postCloseCycleService.findFullMarketCycle(BUSINESS_DATE)).thenReturn(Optional.of(cycle(
                501L,
                PostCloseScopeType.FULL_MARKET,
                "ALL",
                PostClosePhase.PORTFOLIO_SETTLED
        )));

        StockBatchJobRunResponse response = launcher.fundAutoParticipantsManually(41L);

        assertThat(response.status()).isEqualTo("SKIPPED");
        assertThat(response.message()).contains("corporate cash actions");
        verify(runner, never()).run(eq(cashFlowJob), any(), any());
    }

    @Test
    void manualCashFlow_preOpen_runsQueuedOvernightSignal() {
        prepareManualCashFlowOvernight(BUSINESS_DATE, 501L);

        launcher.fundAutoParticipantsManually(41L);

        JobParameters parameters = captureParameters(cashFlowJob, "manual-recurring-cash");
        assertThat(parameters.getLong(StockBatchJobParameters.SIGNAL_ID)).isEqualTo(41L);
    }

    @Test
    void marketCloseSignal_carriesScopeSymbolAndSignalIdentity() {
        launcher.rolloverClosingPrices(" demo002 ", 77L);

        JobParameters parameters = captureParameters(marketCloseJob, "price-limit-base:symbol");
        assertThat(parameters.getString(StockBatchJobParameters.SYMBOL)).isEqualTo("DEMO002");
        assertThat(parameters.getLong(StockBatchJobParameters.SIGNAL_ID)).isEqualTo(77L);
        assertThat(parameters.getLong(StockBatchJobParameters.CYCLE_ID)).isEqualTo(502L);
        assertThat(parameters.getParameter(StockBatchJobParameters.CYCLE_ID).identifying()).isTrue();
        assertThat(parameters.getParameter(StockBatchJobParameters.SIGNAL_ID).identifying()).isFalse();
        assertThat(parameters.getString(StockBatchJobParameters.OPERATION))
                .isEqualTo(MarketCloseRolloverJob.OPERATION_SYMBOL);
    }

    @Test
    void marketCloseCycleBackoff_skipsBeforeSpringBatchMetadata() {
        PostCloseCycle backedOff = cycle(502L, PostCloseScopeType.SYMBOL, "DEMO002");
        when(postCloseCycleService.ensureSymbolCycle(eq(BUSINESS_DATE), eq("DEMO002"), any(LocalDateTime.class)))
                .thenReturn(backedOff);
        when(postCloseCycleService.isPhaseClaimEligible(eq(backedOff), any(LocalDateTime.class)))
                .thenReturn(false);

        StockBatchJobRunResponse response = launcher.rolloverClosingPrices("DEMO002", 77L);

        assertThat(response.status()).isEqualTo("SKIPPED");
        verify(runner, never()).run(eq(marketCloseJob), any(), any(JobParameters.class));
    }

    @Test
    void rolloverClosingPrices_currentRegularSession_rejectsBeforeCycleAndMetadata() {
        when(sessionService.isAfterCloseSession()).thenReturn(false);

        assertThatThrownBy(launcher::rolloverClosingPrices)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only run after the regular session");

        verify(postCloseCycleService, never()).ensureFullMarketCycle(eq(BUSINESS_DATE), any(LocalDateTime.class));
        verify(runner, never()).run(eq(marketCloseJob), any(), any());
    }

    @Test
    void rolloverClosingPrices_signalDuringRegularSession_rejectsBeforeCycleAndMetadata() {
        when(sessionService.isRegularSession()).thenReturn(true);

        assertThatThrownBy(() -> launcher.rolloverClosingPrices(BUSINESS_DATE, 501L, 7L, 77L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot run during the regular session");

        verify(postCloseCycleService, never()).ensureFullMarketCycle(eq(BUSINESS_DATE), any(LocalDateTime.class));
        verify(runner, never()).run(eq(marketCloseJob), any(), any());
    }

    @Test
    void rolloverClosingPrices_signalDuringPreOpen_allowsDeferredRecovery() {
        when(sessionService.currentSession()).thenReturn(SimulationMarketSession.PRE_OPEN);
        when(sessionService.isRegularSession()).thenReturn(false);

        launcher.rolloverClosingPrices(BUSINESS_DATE, 501L, 7L, 77L);

        JobParameters parameters = captureParameters(marketCloseJob, "price-limit-base:full");
        assertThat(parameters.getLocalDate(StockBatchJobParameters.BUSINESS_DATE)).isEqualTo(BUSINESS_DATE);
        assertThat(parameters.getLong(StockBatchJobParameters.CYCLE_ID)).isEqualTo(501L);
        assertThat(parameters.getLong(StockBatchJobParameters.SESSION_EPOCH)).isEqualTo(7L);
        assertThat(parameters.getLong(StockBatchJobParameters.SIGNAL_ID)).isEqualTo(77L);
    }

    @Test
    void settlement_usesExecutionTimeAndStableCycleIdentity() {
        when(postCloseCycleService.findFullMarketCycle(BUSINESS_DATE)).thenReturn(
                Optional.of(cycle(
                        501L,
                        PostCloseScopeType.FULL_MARKET,
                        "ALL",
                        PostClosePhase.LEDGER_FROZEN
                ))
        );

        launcher.settlePortfolios();

        JobParameters parameters = captureParameters(settlementJob, "portfolio-snapshot");
        assertThat(parameters.getLocalDateTime(StockBatchJobParameters.SNAPSHOT_AT))
                .isEqualTo(SIMULATION_NOW);
        assertThat(parameters.getLong(StockBatchJobParameters.CYCLE_ID)).isEqualTo(501L);
        assertThat(parameters.getParameter(StockBatchJobParameters.CYCLE_ID).identifying()).isTrue();
        assertThat(parameters.getParameter(StockBatchJobParameters.SNAPSHOT_AT).identifying()).isFalse();
        assertThat(parameters.getParameter("runVersion")).isNull();
    }

    @Test
    void settlement_rawDateAdvanced_usesActiveBusinessDate() {
        LocalDate rawDate = BUSINESS_DATE.plusDays(2);
        when(simulationClockService.currentDate()).thenReturn(rawDate);
        when(postCloseCycleService.findFullMarketCycle(BUSINESS_DATE)).thenReturn(
                Optional.of(cycle(
                        501L,
                        PostCloseScopeType.FULL_MARKET,
                        "ALL",
                        PostClosePhase.LEDGER_FROZEN
                ))
        );

        launcher.settlePortfolios();

        JobParameters parameters = captureParameters(settlementJob, "portfolio-snapshot");
        assertThat(parameters.getLocalDate(StockBatchJobParameters.BUSINESS_DATE))
                .isEqualTo(BUSINESS_DATE);
    }

    @Test
    void settlement_cycleBackoff_skipsBeforeSpringBatchMetadata() {
        PostCloseCycle backedOff = cycle(
                501L,
                PostCloseScopeType.FULL_MARKET,
                "ALL",
                PostClosePhase.LEDGER_FROZEN
        );
        when(postCloseCycleService.findFullMarketCycle(BUSINESS_DATE)).thenReturn(Optional.of(backedOff));
        when(postCloseCycleService.isPhaseClaimEligible(eq(backedOff), any(LocalDateTime.class)))
                .thenReturn(false);

        StockBatchJobRunResponse response = launcher.settlePortfolios();

        assertThat(response.status()).isEqualTo("SKIPPED");
        verify(runner, never()).run(eq(settlementJob), any(), any(JobParameters.class));
    }

    @Test
    void settlement_beforeLedgerFrozen_rejectsBeforeStartingJobMetadata() {
        when(postCloseCycleService.findFullMarketCycle(BUSINESS_DATE)).thenReturn(
                Optional.of(cycle(
                        501L,
                        PostCloseScopeType.FULL_MARKET,
                        "ALL",
                        PostClosePhase.ORDER_ENTRY_CLOSED
                ))
        );

        assertThatThrownBy(launcher::settlePortfolios)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires LEDGER_FROZEN");

        verify(runner, never()).run(eq(settlementJob), any(), any());
    }

    @Test
    void corporateActionScheduled_usesMinuteSweepAndSessionIdentity() {
        launcher.applyCorporateActionsScheduled();

        JobParameters parameters = captureParameters(corporateActionJob, "order-book");
        assertThat(parameters.getString(StockBatchJobParameters.SESSION)).isEqualTo("AFTER_CLOSE");
        assertThat(parameters.getLocalDateTime(StockBatchJobParameters.SWEEP_AT))
                .isEqualTo(LocalDateTime.of(2026, 7, 14, 19, 7));
    }

    @Test
    void corporateCash_restartedAfterMidnight_keepsClosedBusinessDateForFinalDayRecovery() {
        when(simulationClockService.currentMarketDateTime())
                .thenReturn(BUSINESS_DATE.plusDays(1).atTime(0, 5));
        when(postCloseCycleService.findById(501L)).thenReturn(Optional.of(cycle(
                501L,
                PostCloseScopeType.FULL_MARKET,
                "ALL",
                PostClosePhase.PORTFOLIO_SETTLED
        )));

        launcher.applyCorporateCashActions(501L);

        JobParameters parameters = captureParameters(corporateActionJob, "corporate-cash");
        assertThat(parameters.getLocalDate(StockBatchJobParameters.BUSINESS_DATE))
                .isEqualTo(BUSINESS_DATE);
        assertThat(parameters.getString(StockBatchJobParameters.OPERATION))
                .isEqualTo(CorporateActionJob.OPERATION_CASH);
        assertThat(parameters.getLocalDateTime(StockBatchJobParameters.SWEEP_AT))
                .isEqualTo(BUSINESS_DATE.plusDays(1).atTime(0, 5));
    }

    @Test
    void corporateCash_beforeMidnight_defersWithoutMetadataOrCashMutation() {
        when(postCloseCycleService.findById(501L)).thenReturn(Optional.of(cycle(
                501L,
                PostCloseScopeType.FULL_MARKET,
                "ALL",
                PostClosePhase.PORTFOLIO_SETTLED
        )));

        StockBatchJobRunResponse response = launcher.applyCorporateCashActions(501L);

        assertThat(response.status()).isEqualTo("SKIPPED");
        assertThat(response.message()).contains("2026-07-15T00:00");
        verify(runner, never()).run(eq(corporateActionJob), any(), any());
    }

    @Test
    void postCloseReports_retryKeepsStableJobInstanceIdentity() {
        LocalDateTime firstAggregatedAt = BUSINESS_DATE.plusDays(1).atTime(0, 10);
        LocalDateTime retryAggregatedAt = firstAggregatedAt.plusMinutes(15);
        when(postCloseCycleService.findById(501L)).thenReturn(Optional.of(cycle(
                501L,
                PostCloseScopeType.FULL_MARKET,
                "ALL",
                PostClosePhase.OVERNIGHT_CASH_APPLIED
        )));

        launcher.aggregatePostCloseReports(501L, firstAggregatedAt);
        launcher.aggregatePostCloseReports(501L, retryAggregatedAt);

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(runner, times(2)).run(
                eq(postCloseReportAggregationJob),
                eq("post-close-reports"),
                captor.capture()
        );
        JobParameters first = captor.getAllValues().get(0);
        JobParameters retry = captor.getAllValues().get(1);
        assertThat(List.of(
                first.getLong(StockBatchJobParameters.CYCLE_ID),
                retry.getLong(StockBatchJobParameters.CYCLE_ID),
                first.getLong(StockBatchJobParameters.PHASE_REVISION),
                retry.getLong(StockBatchJobParameters.PHASE_REVISION),
                first.getParameter(StockBatchJobParameters.CYCLE_ID).identifying(),
                first.getParameter(StockBatchJobParameters.PHASE_REVISION).identifying(),
                first.getParameter(StockBatchJobParameters.SNAPSHOT_AT).identifying(),
                first.getLocalDateTime(StockBatchJobParameters.SNAPSHOT_AT),
                retry.getLocalDateTime(StockBatchJobParameters.SNAPSHOT_AT)
        )).containsExactly(
                501L,
                501L,
                1L,
                1L,
                true,
                true,
                false,
                firstAggregatedAt,
                retryAggregatedAt
        );
    }

    @Test
    void preOpenTransforms_separatesPreparingDateFromRequiredCloseDate() {
        LocalDate preparingBusinessDate = BUSINESS_DATE.plusDays(1);
        when(simulationClockService.currentMarketDateTime())
                .thenReturn(preparingBusinessDate.atTime(4, 30));
        when(postCloseCycleService.findById(501L)).thenReturn(Optional.of(cycle(
                501L,
                PostCloseScopeType.FULL_MARKET,
                "ALL",
                PostClosePhase.REPORTS_AGGREGATED
        )));

        launcher.applyPreOpenSecurityTransforms(501L, preparingBusinessDate);

        JobParameters parameters = captureParameters(corporateActionJob, "preopen-security-transforms");
        assertThat(parameters.getLocalDate(StockBatchJobParameters.BUSINESS_DATE))
                .isEqualTo(preparingBusinessDate);
        assertThat(parameters.getLocalDate(StockBatchJobParameters.REQUIRED_CLOSE_DATE))
                .isEqualTo(BUSINESS_DATE);
        assertThat(parameters.getString(StockBatchJobParameters.OPERATION))
                .isEqualTo(CorporateActionJob.OPERATION_PREOPEN_SECURITY_TRANSFORMS);
    }

    private JobParameters captureParameters(Job job, String mode) {
        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(runner).run(eq(job), eq(mode), captor.capture());
        return captor.getValue();
    }

    private void prepareManualCashFlowOvernight(LocalDate businessDate, long cycleId) {
        when(simulationClockService.currentMarketDateTime())
                .thenReturn(businessDate.plusDays(1).atTime(0, 5));
        when(sessionService.currentSession()).thenReturn(SimulationMarketSession.PRE_OPEN);
        when(postCloseCycleService.findFullMarketCycle(businessDate)).thenReturn(Optional.of(cycle(
                cycleId,
                businessDate,
                PostCloseScopeType.FULL_MARKET,
                "ALL",
                PostClosePhase.CORPORATE_CASH_APPLIED
        )));
    }

    private static Job job(String name) {
        Job job = mock(Job.class);
        when(job.getName()).thenReturn(name);
        return job;
    }

    private static PostCloseCycle cycle(long id, PostCloseScopeType scopeType, String scopeKey) {
        return cycle(id, scopeType, scopeKey, PostClosePhase.CLOSE_REQUESTED);
    }

    private static PostCloseCycle cycle(
            long id,
            PostCloseScopeType scopeType,
            String scopeKey,
            PostClosePhase phase
    ) {
        return cycle(id, BUSINESS_DATE, scopeType, scopeKey, phase);
    }

    private static PostCloseCycle cycle(
            long id,
            LocalDate businessDate,
            PostCloseScopeType scopeType,
            String scopeKey,
            PostClosePhase phase
    ) {
        return new PostCloseCycle(
                id,
                businessDate,
                scopeType,
                scopeKey,
                stock.batch.service.marketclose.model.PostCloseCycleKind.TRADING,
                null,
                phase,
                PostCloseCycleStatus.PENDING,
                1,
                0,
                null,
                null,
                0,
                null,
                null,
                null
        );
    }
}
