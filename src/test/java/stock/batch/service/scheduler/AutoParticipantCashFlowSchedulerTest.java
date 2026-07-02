package stock.batch.service.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import stock.batch.service.automarket.biz.AutoParticipantCashFlowRuntimeControl;
import stock.batch.service.batch.automarket.job.AutoMarketJob;
import stock.batch.service.batch.common.policy.BatchJobRuntimeControl;
import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.batch.corporateaction.job.CorporateActionJob;
import stock.batch.service.batch.execution.job.OrderBookExecutionJob;
import stock.batch.service.batch.execution.job.VirtualPriceExecutionJob;
import stock.batch.service.batch.marketclose.job.MarketCloseRolloverJob;
import stock.batch.service.batch.marketdata.job.MarketDataRefreshJob;
import stock.batch.service.batch.settlement.job.PortfolioSettlementJob;
import stock.batch.service.common.vo.StockBatchJobRunResponse;
import stock.batch.service.simulation.SimulationClockService;
import stock.batch.service.testsupport.BatchTestDatabaseFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoParticipantCashFlowSchedulerTest {

    private final StockBatchJobLauncher stockBatchJobLauncher = mock(StockBatchJobLauncher.class);
    private final JdbcTemplate jdbcTemplate = createJdbcTemplate();
    private final SimulationClockService simulationClockService = mock(SimulationClockService.class);
    private final BatchJobRuntimeControl batchJobRuntimeControl = new BatchJobRuntimeControl(jdbcTemplate);
    private final StockBatchScheduledJobGuard scheduledJobGuard = new StockBatchScheduledJobGuard(batchJobRuntimeControl);
    private final AutoParticipantCashFlowRuntimeControl runtimeControl =
            new AutoParticipantCashFlowRuntimeControl(batchJobRuntimeControl, true);
    private final AutoParticipantCashFlowScheduler scheduler =
            createAutoParticipantCashFlowScheduler();

    @Test
    void fundAutoParticipants_runtimeEnabled_runsJob() {
        scheduler.fundAutoParticipants();

        verify(stockBatchJobLauncher).fundAutoParticipants();
    }

    @Test
    void fundAutoParticipants_runtimeDisabled_skipsJob() {
        runtimeControl.update(false, "stock-admin");

        scheduler.fundAutoParticipants();

        verify(stockBatchJobLauncher, never()).fundAutoParticipants();
    }

    @Test
    void status_secondRuntimeControlReadsSharedDatabaseState() {
        runtimeControl.update(false, "stock-admin");

        AutoParticipantCashFlowRuntimeControl otherInstanceControl =
                new AutoParticipantCashFlowRuntimeControl(batchJobRuntimeControl, true);

        assertThat(otherInstanceControl.status().runtimeEnabled()).isFalse();
        assertThat(otherInstanceControl.status().effectiveEnabled()).isFalse();
        assertThat(otherInstanceControl.status().updatedBy()).isEqualTo("stock-admin");
    }

    @Test
    void autoMarketScheduler_runtimeDisabled_skipsJobThroughSharedControlTable() {
        AutoMarketScheduler autoMarketScheduler = new AutoMarketScheduler(stockBatchJobLauncher, scheduledJobGuard);
        batchJobRuntimeControl.update(AutoMarketJob.JOB_NAME, true, false, "stock-admin");

        autoMarketScheduler.runAutoMarket();

        verify(stockBatchJobLauncher, never()).runAutoMarket();
    }

    @Test
    void marketDataScheduler_runtimeDisabled_skipsJobThroughSharedControlTable() {
        MarketDataRefreshScheduler marketDataRefreshScheduler =
                new MarketDataRefreshScheduler(stockBatchJobLauncher, scheduledJobGuard);
        batchJobRuntimeControl.update(MarketDataRefreshJob.JOB_NAME, true, false, "stock-admin");

        marketDataRefreshScheduler.refreshMarketData();

        verify(stockBatchJobLauncher, never()).refreshMarketData();
    }

    @Test
    void virtualPriceExecutionScheduler_runtimeDisabled_skipsJobThroughSharedControlTable() {
        VirtualPriceExecutionScheduler virtualPriceExecutionScheduler =
                new VirtualPriceExecutionScheduler(stockBatchJobLauncher, scheduledJobGuard);
        batchJobRuntimeControl.update(VirtualPriceExecutionJob.JOB_NAME, true, false, "stock-admin");

        virtualPriceExecutionScheduler.executeVirtualPriceOrders();

        verify(stockBatchJobLauncher, never()).executeVirtualPriceOrders();
    }

    @Test
    void orderBookExecutionScheduler_runtimeDisabled_skipsJobThroughSharedControlTable() {
        OrderBookExecutionScheduler orderBookExecutionScheduler =
                new OrderBookExecutionScheduler(stockBatchJobLauncher, scheduledJobGuard);
        batchJobRuntimeControl.update(OrderBookExecutionJob.JOB_NAME, true, false, "stock-admin");

        orderBookExecutionScheduler.executeOrderBookOrders();

        verify(stockBatchJobLauncher, never()).executeOrderBookOrders();
    }

    @Test
    void corporateActionScheduler_runtimeDisabled_skipsJobThroughSharedControlTable() {
        CorporateActionScheduler corporateActionScheduler =
                new CorporateActionScheduler(stockBatchJobLauncher, scheduledJobGuard);
        batchJobRuntimeControl.update(CorporateActionJob.JOB_NAME, true, false, "stock-admin");

        corporateActionScheduler.applyCorporateActions();

        verify(stockBatchJobLauncher, never()).applyCorporateActions();
    }

    @Test
    void portfolioSettlementScheduler_marketCloseDisabled_stillRunsSettlementOnly() {
        PortfolioSettlementScheduler portfolioSettlementScheduler =
                new PortfolioSettlementScheduler(stockBatchJobLauncher, scheduledJobGuard, simulationClockService);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "marketCloseSchedulerConfigured", true);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "settlementSchedulerConfigured", true);
        batchJobRuntimeControl.update(MarketCloseRolloverJob.JOB_NAME, true, false, "stock-admin");

        portfolioSettlementScheduler.settlePortfolios();

        verify(stockBatchJobLauncher, never()).rolloverClosingPrices();
        verify(stockBatchJobLauncher).settlePortfolios();
    }

    @Test
    void portfolioSettlementScheduler_settlementDisabled_stillRunsMarketCloseOnly() {
        PortfolioSettlementScheduler portfolioSettlementScheduler =
                new PortfolioSettlementScheduler(stockBatchJobLauncher, scheduledJobGuard, simulationClockService);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "marketCloseSchedulerConfigured", true);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "settlementSchedulerConfigured", true);
        batchJobRuntimeControl.update(PortfolioSettlementJob.JOB_NAME, true, false, "stock-admin");

        portfolioSettlementScheduler.settlePortfolios();

        verify(stockBatchJobLauncher).rolloverClosingPrices();
        verify(stockBatchJobLauncher, never()).settlePortfolios();
    }

    @Test
    void portfolioSettlementScheduler_marketCloseScheduleRunsRolloverOnly() {
        PortfolioSettlementScheduler portfolioSettlementScheduler =
                new PortfolioSettlementScheduler(stockBatchJobLauncher, scheduledJobGuard, simulationClockService);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "marketCloseSchedulerConfigured", true);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "settlementSchedulerConfigured", true);

        portfolioSettlementScheduler.rolloverClosingPrices();

        verify(stockBatchJobLauncher).rolloverClosingPrices();
        verify(stockBatchJobLauncher, never()).settlePortfolios();
    }

    @Test
    void portfolioSettlementScheduler_marketCloseConfiguredOff_stillRunsSettlementOnly() {
        PortfolioSettlementScheduler portfolioSettlementScheduler =
                new PortfolioSettlementScheduler(stockBatchJobLauncher, scheduledJobGuard, simulationClockService);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "marketCloseSchedulerConfigured", false);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "settlementSchedulerConfigured", true);

        portfolioSettlementScheduler.settlePortfolios();

        verify(stockBatchJobLauncher, never()).rolloverClosingPrices();
        verify(stockBatchJobLauncher).settlePortfolios();
    }

    @Test
    void portfolioSettlementScheduler_settlementConfiguredOff_stillRunsMarketCloseOnly() {
        PortfolioSettlementScheduler portfolioSettlementScheduler =
                new PortfolioSettlementScheduler(stockBatchJobLauncher, scheduledJobGuard, simulationClockService);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "marketCloseSchedulerConfigured", true);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "settlementSchedulerConfigured", false);

        portfolioSettlementScheduler.settlePortfolios();

        verify(stockBatchJobLauncher).rolloverClosingPrices();
        verify(stockBatchJobLauncher, never()).settlePortfolios();
    }

    @Test
    void portfolioSettlementScheduler_bothConfiguredOff_skipsBothJobs() {
        PortfolioSettlementScheduler portfolioSettlementScheduler =
                new PortfolioSettlementScheduler(stockBatchJobLauncher, scheduledJobGuard, simulationClockService);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "marketCloseSchedulerConfigured", false);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "settlementSchedulerConfigured", false);

        portfolioSettlementScheduler.settlePortfolios();

        verify(stockBatchJobLauncher, never()).rolloverClosingPrices();
        verify(stockBatchJobLauncher, never()).settlePortfolios();
    }

    @Test
    void portfolioSettlementScheduler_simulationDateUnchanged_skipsRolloverAndSettlement() {
        PortfolioSettlementScheduler portfolioSettlementScheduler =
                new PortfolioSettlementScheduler(stockBatchJobLauncher, scheduledJobGuard, simulationClockService);
        when(simulationClockService.currentDate())
                .thenReturn(LocalDate.of(2026, 7, 1))
                .thenReturn(LocalDate.of(2026, 7, 1));

        portfolioSettlementScheduler.rolloverSimulationDayIfNeeded();
        portfolioSettlementScheduler.rolloverSimulationDayIfNeeded();

        verify(stockBatchJobLauncher, never()).rolloverClosingPrices();
        verify(stockBatchJobLauncher, never()).settlePortfolios();
    }

    @Test
    void portfolioSettlementScheduler_simulationDateChanged_runsRolloverAndSettlement() {
        PortfolioSettlementScheduler portfolioSettlementScheduler =
                new PortfolioSettlementScheduler(stockBatchJobLauncher, scheduledJobGuard, simulationClockService);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "marketCloseSchedulerConfigured", true);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "settlementSchedulerConfigured", true);
        when(simulationClockService.currentDate())
                .thenReturn(LocalDate.of(2026, 7, 1))
                .thenReturn(LocalDate.of(2026, 7, 2));
        when(stockBatchJobLauncher.rolloverClosingPrices())
                .thenReturn(completedResponse(MarketCloseRolloverJob.JOB_NAME));
        when(stockBatchJobLauncher.settlePortfolios())
                .thenReturn(completedResponse(PortfolioSettlementJob.JOB_NAME));

        portfolioSettlementScheduler.rolloverSimulationDayIfNeeded();
        portfolioSettlementScheduler.rolloverSimulationDayIfNeeded();

        verify(stockBatchJobLauncher).rolloverClosingPrices();
        verify(stockBatchJobLauncher).settlePortfolios();
    }

    @Test
    void portfolioSettlementScheduler_failedSettlementRetriesSameSimulationDate() {
        PortfolioSettlementScheduler portfolioSettlementScheduler =
                new PortfolioSettlementScheduler(stockBatchJobLauncher, scheduledJobGuard, simulationClockService);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "marketCloseSchedulerConfigured", true);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "settlementSchedulerConfigured", true);
        when(simulationClockService.currentDate())
                .thenReturn(LocalDate.of(2026, 7, 1))
                .thenReturn(LocalDate.of(2026, 7, 2))
                .thenReturn(LocalDate.of(2026, 7, 2));
        when(stockBatchJobLauncher.rolloverClosingPrices())
                .thenReturn(completedResponse(MarketCloseRolloverJob.JOB_NAME))
                .thenReturn(completedResponse(MarketCloseRolloverJob.JOB_NAME));
        when(stockBatchJobLauncher.settlePortfolios())
                .thenReturn(failedResponse(PortfolioSettlementJob.JOB_NAME))
                .thenReturn(completedResponse(PortfolioSettlementJob.JOB_NAME));

        portfolioSettlementScheduler.rolloverSimulationDayIfNeeded();
        portfolioSettlementScheduler.rolloverSimulationDayIfNeeded();
        portfolioSettlementScheduler.rolloverSimulationDayIfNeeded();

        verify(stockBatchJobLauncher, times(2)).rolloverClosingPrices();
        verify(stockBatchJobLauncher, times(2)).settlePortfolios();
    }

    private AutoParticipantCashFlowScheduler createAutoParticipantCashFlowScheduler() {
        return new AutoParticipantCashFlowScheduler(stockBatchJobLauncher, scheduledJobGuard);
    }

    private JdbcTemplate createJdbcTemplate() {
        return BatchTestDatabaseFactory.createJobControlJdbcTemplate("auto_participant_cash_flow_scheduler_test");
    }

    private StockBatchJobRunResponse completedResponse(String jobName) {
        LocalDateTime now = LocalDateTime.now();
        return new StockBatchJobRunResponse(jobName, "COMPLETED", "test", 1, "completed", now, now);
    }

    private StockBatchJobRunResponse failedResponse(String jobName) {
        LocalDateTime now = LocalDateTime.now();
        return new StockBatchJobRunResponse(jobName, "FAILED", "test", 0, "failed", now, now);
    }
}
