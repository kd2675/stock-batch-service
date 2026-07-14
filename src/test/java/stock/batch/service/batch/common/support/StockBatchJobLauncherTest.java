package stock.batch.service.batch.common.support;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

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
import stock.batch.service.batch.automarket.job.AutoParticipantCashFlowJob;
import stock.batch.service.batch.automarket.job.ListingAutoMarketJob;
import stock.batch.service.batch.corporateaction.job.CorporateActionJob;
import stock.batch.service.batch.execution.job.OrderBookExecutionJob;
import stock.batch.service.batch.holdingcleanup.job.HoldingCleanupJob;
import stock.batch.service.batch.marketclose.job.MarketCloseRolloverJob;
import stock.batch.service.batch.marketdata.job.MarketDataRefreshJob;
import stock.batch.service.batch.settlement.job.PortfolioSettlementJob;
import stock.batch.service.common.vo.StockBatchJobRunResponse;
import stock.batch.service.simulation.SimulationClockService;
import stock.batch.service.simulation.SimulationMarketSessionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    private final MarketDataRefreshJob marketDataRefreshTask = mock(MarketDataRefreshJob.class);
    private final OrderBookExecutionJob orderBookExecutionTask = mock(OrderBookExecutionJob.class);
    private final AutoMarketProfileQueueReconcileJob reconcileTask = mock(AutoMarketProfileQueueReconcileJob.class);
    private final AutoMarketJob autoMarketTask = mock(AutoMarketJob.class);
    private final AutoMarketOrderExpiryJob expiryTask = mock(AutoMarketOrderExpiryJob.class);
    private final ListingAutoMarketJob listingTask = mock(ListingAutoMarketJob.class);
    private final HoldingCleanupJob holdingCleanupTask = mock(HoldingCleanupJob.class);
    private final Job cashFlowJob = job(AutoParticipantCashFlowJob.JOB_NAME);
    private final Job dailyRegimeJob = job(AutoMarketDailyRegimePreCreateJob.JOB_NAME);
    private final Job settlementJob = job(PortfolioSettlementJob.JOB_NAME);
    private final Job marketCloseJob = job(MarketCloseRolloverJob.JOB_NAME);
    private final Job corporateActionJob = job(CorporateActionJob.JOB_NAME);

    private StockBatchJobLauncher launcher;

    @BeforeEach
    void setUp() {
        when(simulationClockService.currentDate()).thenReturn(BUSINESS_DATE);
        when(simulationClockService.currentMarketDateTime()).thenReturn(SIMULATION_NOW);
        when(sessionService.closeTime()).thenReturn(LocalTime.of(18, 0));
        when(sessionService.currentSession()).thenReturn(SimulationMarketSession.AFTER_CLOSE);
        when(sessionService.isAfterCloseSession()).thenReturn(true);
        when(cashFlowRuntimeControl.canRunManualCashFlow()).thenReturn(true);
        launcher = new StockBatchJobLauncher(
                runner,
                cashFlowRuntimeControl,
                simulationClockService,
                sessionService,
                marketDataRefreshTask,
                orderBookExecutionTask,
                reconcileTask,
                autoMarketTask,
                expiryTask,
                listingTask,
                holdingCleanupTask,
                cashFlowJob,
                dailyRegimeJob,
                settlementJob,
                marketCloseJob,
                corporateActionJob
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
    void manualCashFlowSignal_usesRealSignalIdAsIdentifyingParameter() {
        launcher.fundAutoParticipantsManually(41L);

        JobParameters parameters = captureParameters(cashFlowJob, "manual-recurring-cash");
        assertThat(parameters.getLong(StockBatchJobParameters.SIGNAL_ID)).isEqualTo(41L);
        assertThat(parameters.getParameter(StockBatchJobParameters.SIGNAL_ID).identifying()).isTrue();
        assertThat(parameters.getString(StockBatchJobParameters.REQUEST_ID)).isEqualTo("db-signal:41");
        assertThat(parameters.getParameter(StockBatchJobParameters.REQUEST_ID).identifying()).isFalse();
    }

    @Test
    void manualCashFlow_whenAutomaticEnabled_skipsBeforeCreatingMetadata() {
        when(cashFlowRuntimeControl.canRunManualCashFlow()).thenReturn(false);

        StockBatchJobRunResponse response = launcher.fundAutoParticipantsManually(41L);

        assertThat(response.status()).isEqualTo("SKIPPED");
        verify(runner, never()).run(eq(cashFlowJob), any(), any());
    }

    @Test
    void manualCashFlow_beforeClose_skipsBeforeCreatingMetadata() {
        when(sessionService.isAfterCloseSession()).thenReturn(false);

        StockBatchJobRunResponse response = launcher.fundAutoParticipantsManually(41L);

        assertThat(response.status()).isEqualTo("SKIPPED");
        verify(runner, never()).run(eq(cashFlowJob), any(), any());
    }

    @Test
    void marketCloseSignal_carriesScopeSymbolAndSignalIdentity() {
        launcher.rolloverClosingPrices(" demo002 ", 77L);

        JobParameters parameters = captureParameters(marketCloseJob, "price-limit-base:symbol");
        assertThat(parameters.getString(StockBatchJobParameters.SYMBOL)).isEqualTo("DEMO002");
        assertThat(parameters.getLong(StockBatchJobParameters.SIGNAL_ID)).isEqualTo(77L);
        assertThat(parameters.getString(StockBatchJobParameters.OPERATION))
                .isEqualTo(MarketCloseRolloverJob.OPERATION_SYMBOL);
    }

    @Test
    void settlement_usesDeterministicClosingSnapshotTime() {
        launcher.settlePortfolios();

        JobParameters parameters = captureParameters(settlementJob, "portfolio-snapshot");
        assertThat(parameters.getLocalDateTime(StockBatchJobParameters.SNAPSHOT_AT))
                .isEqualTo(LocalDateTime.of(2026, 7, 14, 18, 0));
        assertThat(parameters.getString(StockBatchJobParameters.ENFORCE_CLOSE)).isEqualTo("true");
    }

    @Test
    void corporateActionScheduled_usesMinuteSweepAndSessionIdentity() {
        launcher.applyCorporateActionsScheduled();

        JobParameters parameters = captureParameters(corporateActionJob, "order-book");
        assertThat(parameters.getString(StockBatchJobParameters.SESSION)).isEqualTo("AFTER_CLOSE");
        assertThat(parameters.getLocalDateTime(StockBatchJobParameters.SWEEP_AT))
                .isEqualTo(LocalDateTime.of(2026, 7, 14, 19, 7));
    }

    private JobParameters captureParameters(Job job, String mode) {
        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(runner).run(eq(job), eq(mode), captor.capture());
        return captor.getValue();
    }

    private static Job job(String name) {
        Job job = mock(Job.class);
        when(job.getName()).thenReturn(name);
        return job;
    }
}
