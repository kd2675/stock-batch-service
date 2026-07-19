package stock.batch.service.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import stock.batch.service.automarket.biz.AutoMarketDailyRegimePreCreateService;
import stock.batch.service.automarket.biz.AutoMarketProfileQueueReconcileService;
import stock.batch.service.automarket.biz.AutoParticipantCashFlowRuntimeControl;
import stock.batch.service.batch.automarket.job.AutoMarketJob;
import stock.batch.service.batch.common.policy.BatchJobRuntimeControl;
import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.batch.corporateaction.job.CorporateActionJob;
import stock.batch.service.batch.execution.job.OrderBookExecutionJob;
import stock.batch.service.batch.marketclose.job.MarketCloseRolloverJob;
import stock.batch.service.batch.marketdata.job.MarketDataRefreshJob;
import stock.batch.service.batch.settlement.job.PortfolioSettlementJob;
import stock.batch.service.batch.settlement.biz.PortfolioSettlementLifecycleService;
import stock.batch.service.common.vo.StockBatchJobRunResponse;
import stock.batch.service.marketclose.biz.MarketClosePostProcessingCompletionService;
import stock.batch.service.marketclose.biz.MarketCloseRolloverService;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;
import stock.batch.service.marketclose.biz.OrderBookMarketSessionStateService;
import stock.batch.service.marketclose.biz.PostCloseCycleService;
import stock.batch.service.marketclose.model.PostCloseCycle;
import stock.batch.service.marketclose.model.PostCloseCycleStatus;
import stock.batch.service.marketclose.model.PostClosePhase;
import stock.batch.service.marketclose.model.PostCloseScopeType;
import stock.batch.service.simulation.SimulationMarketSessionService;
import stock.batch.service.testsupport.BatchTestDatabaseFactory;
import web.common.core.simulation.SimulationMarketSession;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

class AutoParticipantCashFlowSchedulerTest {

    private final StockBatchJobLauncher stockBatchJobLauncher = mock(StockBatchJobLauncher.class);
    private final JdbcTemplate jdbcTemplate = createJdbcTemplate();
    private final SimulationMarketSessionService simulationMarketSessionService = mock(SimulationMarketSessionService.class);
    private final MarketSessionFenceService marketSessionFenceService = mock(MarketSessionFenceService.class);
    private final AutoMarketDailyRegimePreCreateService autoMarketDailyRegimePreCreateService =
            mock(AutoMarketDailyRegimePreCreateService.class);
    private final OrderBookMarketSessionStateService orderBookMarketSessionStateService = mock(OrderBookMarketSessionStateService.class);
    private final MarketCloseRolloverService marketCloseRolloverService = mock(MarketCloseRolloverService.class);
    private final MarketClosePostProcessingCompletionService postProcessingCompletionService =
            mock(MarketClosePostProcessingCompletionService.class);
    private final PostCloseCycleService postCloseCycleService = mock(PostCloseCycleService.class);
    private final PortfolioSettlementLifecycleService portfolioSettlementLifecycleService =
            mock(PortfolioSettlementLifecycleService.class);
    private final BatchJobRuntimeControl batchJobRuntimeControl = new BatchJobRuntimeControl(jdbcTemplate);
    private final StockBatchScheduledJobGuard scheduledJobGuard = new StockBatchScheduledJobGuard(batchJobRuntimeControl);
    private final AutoParticipantCashFlowRuntimeControl runtimeControl =
            new AutoParticipantCashFlowRuntimeControl(batchJobRuntimeControl, true);
    private final AutoParticipantCashFlowScheduler scheduler =
            createAutoParticipantCashFlowScheduler();

    AutoParticipantCashFlowSchedulerTest() {
        when(simulationMarketSessionService.isRegularSession()).thenReturn(true);
        when(marketSessionFenceService.hasOpenOrderBookMarket()).thenReturn(true);
        when(simulationMarketSessionService.isAfterCloseSession()).thenReturn(true);
        when(simulationMarketSessionService.currentSession()).thenReturn(SimulationMarketSession.AFTER_CLOSE);
        when(simulationMarketSessionService.currentSimulationDate()).thenReturn(LocalDate.of(2026, 7, 1));
        when(simulationMarketSessionService.baseSimulationDate()).thenReturn(LocalDate.of(2026, 7, 1));
        when(simulationMarketSessionService.currentSimulationDateTime()).thenReturn(LocalDateTime.of(2026, 7, 1, 18, 30));
        when(simulationMarketSessionService.closeTime()).thenReturn(LocalTime.of(18, 0));
        when(autoMarketDailyRegimePreCreateService.shouldPreCreateDailyRegimes()).thenReturn(true);
        when(postCloseCycleService.findOldestUnsettledFullMarketCycle()).thenReturn(Optional.empty());
        when(postCloseCycleService.findFullMarketCycle(any())).thenAnswer(invocation -> {
            LocalDate businessDate = invocation.getArgument(0);
            return Optional.of(frozenCycle(businessDate));
        });
        when(postCloseCycleService.isPhaseClaimEligible(any(), any())).thenReturn(true);
        when(portfolioSettlementLifecycleService.isSettlementEligible(anyLong(), any())).thenReturn(true);
    }

    @Test
    void fundAutoParticipants_runtimeEnabled_runsJob() {
        when(simulationMarketSessionService.currentSession()).thenReturn(SimulationMarketSession.PRE_OPEN);

        scheduler.fundAutoParticipants();

        verify(stockBatchJobLauncher).fundAutoParticipants();
    }

    @Test
    void fundAutoParticipants_runtimeDisabled_skipsJob() {
        when(simulationMarketSessionService.currentSession()).thenReturn(SimulationMarketSession.PRE_OPEN);
        runtimeControl.update(false, "stock-admin");

        scheduler.fundAutoParticipants();

        verify(stockBatchJobLauncher, never()).fundAutoParticipants();
    }

    @Test
    void fundAutoParticipants_regularSession_skipsBeforeRuntimeControl() {
        when(simulationMarketSessionService.currentSession()).thenReturn(SimulationMarketSession.REGULAR);

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
        AutoMarketScheduler autoMarketScheduler = new AutoMarketScheduler(
                stockBatchJobLauncher,
                scheduledJobGuard,
                simulationMarketSessionService,
                marketSessionFenceService,
                autoMarketDailyRegimePreCreateService,
                mock(AutoMarketProfileQueueReconcileService.class),
                command -> command.run()
        );
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
    void orderBookExecutionScheduler_runtimeDisabled_skipsJobThroughSharedControlTable() {
        OrderBookExecutionScheduler orderBookExecutionScheduler =
                new OrderBookExecutionScheduler(
                        stockBatchJobLauncher,
                        scheduledJobGuard,
                        simulationMarketSessionService,
                        marketSessionFenceService
                );
        batchJobRuntimeControl.update(OrderBookExecutionJob.JOB_NAME, true, false, "stock-admin");

        orderBookExecutionScheduler.executeOrderBookOrders();

        verify(stockBatchJobLauncher, never()).executeOrderBookOrders();
    }

    @Test
    void corporateActionScheduler_runtimeDisabled_skipsJobThroughSharedControlTable() {
        CorporateActionScheduler corporateActionScheduler =
                new CorporateActionScheduler(stockBatchJobLauncher, scheduledJobGuard, simulationMarketSessionService);
        batchJobRuntimeControl.update(CorporateActionJob.JOB_NAME, true, false, "stock-admin");

        corporateActionScheduler.applyCorporateActions();

        verify(stockBatchJobLauncher, never()).applyCorporateActions();
    }

    @Test
    void portfolioSettlementScheduler_marketCloseDisabledWithoutCloseRunSkipsSettlement() {
        PortfolioSettlementScheduler portfolioSettlementScheduler =
                newPortfolioSettlementScheduler();
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "marketCloseSchedulerConfigured", true);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "settlementSchedulerConfigured", true);
        batchJobRuntimeControl.update(MarketCloseRolloverJob.JOB_NAME, true, false, "stock-admin");

        portfolioSettlementScheduler.settlePortfolios();

        verify(stockBatchJobLauncher, never()).rolloverClosingPrices();
        verify(stockBatchJobLauncher, never()).settlePortfolios();
    }

    @Test
    void portfolioSettlementScheduler_settlementDisabled_stillRunsMarketCloseOnly() {
        PortfolioSettlementScheduler portfolioSettlementScheduler =
                newPortfolioSettlementScheduler();
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "marketCloseSchedulerConfigured", true);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "settlementSchedulerConfigured", true);
        batchJobRuntimeControl.update(PortfolioSettlementJob.JOB_NAME, true, false, "stock-admin");
        when(marketCloseRolloverService.hasCompletedFullCloseRun(LocalDate.of(2026, 7, 1)))
                .thenReturn(false, true);

        portfolioSettlementScheduler.settlePortfolios();

        verify(stockBatchJobLauncher).rolloverClosingPricesScheduled();
        verify(stockBatchJobLauncher, never()).settlePortfolios();
    }

    @Test
    void portfolioSettlementScheduler_marketCloseScheduleRunsRolloverOnly() {
        PortfolioSettlementScheduler portfolioSettlementScheduler =
                newPortfolioSettlementScheduler();
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "marketCloseSchedulerConfigured", true);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "settlementSchedulerConfigured", true);

        portfolioSettlementScheduler.rolloverClosingPrices();

        verify(stockBatchJobLauncher).rolloverClosingPricesScheduled();
        verify(stockBatchJobLauncher, never()).settlePortfolios();
    }

    @Test
    void portfolioSettlementScheduler_marketCloseConfiguredOff_stillRunsSettlementOnly() {
        PortfolioSettlementScheduler portfolioSettlementScheduler =
                newPortfolioSettlementScheduler();
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "marketCloseSchedulerConfigured", false);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "settlementSchedulerConfigured", true);
        when(marketCloseRolloverService.hasCompletedFullCloseRun(LocalDate.of(2026, 7, 1)))
                .thenReturn(true);

        portfolioSettlementScheduler.settlePortfolios();

        verify(stockBatchJobLauncher, never()).rolloverClosingPrices();
        verify(stockBatchJobLauncher).settlePortfoliosScheduled();
    }

    @Test
    void portfolioSettlementScheduler_settlementConfiguredOff_stillRunsMarketCloseOnly() {
        PortfolioSettlementScheduler portfolioSettlementScheduler =
                newPortfolioSettlementScheduler();
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "marketCloseSchedulerConfigured", true);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "settlementSchedulerConfigured", false);
        when(marketCloseRolloverService.hasCompletedFullCloseRun(LocalDate.of(2026, 7, 1)))
                .thenReturn(false, true);

        portfolioSettlementScheduler.settlePortfolios();

        verify(stockBatchJobLauncher).rolloverClosingPricesScheduled();
        verify(stockBatchJobLauncher, never()).settlePortfolios();
    }

    @Test
    void portfolioSettlementScheduler_bothConfiguredOff_skipsBothJobs() {
        PortfolioSettlementScheduler portfolioSettlementScheduler =
                newPortfolioSettlementScheduler();
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "marketCloseSchedulerConfigured", false);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "settlementSchedulerConfigured", false);

        portfolioSettlementScheduler.settlePortfolios();

        verify(stockBatchJobLauncher, never()).rolloverClosingPrices();
        verify(stockBatchJobLauncher, never()).settlePortfolios();
    }

    @Test
    void portfolioSettlementScheduler_simulationDateUnchanged_skipsRolloverAndSettlement() {
        PortfolioSettlementScheduler portfolioSettlementScheduler =
                newPortfolioSettlementScheduler();
        when(simulationMarketSessionService.isAfterCloseSession()).thenReturn(false);
        when(simulationMarketSessionService.currentSession()).thenReturn(SimulationMarketSession.REGULAR);
        when(simulationMarketSessionService.currentSimulationDate())
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
                newPortfolioSettlementScheduler();
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "marketCloseSchedulerConfigured", true);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "settlementSchedulerConfigured", true);
        when(simulationMarketSessionService.currentSimulationDate())
                .thenReturn(LocalDate.of(2026, 7, 1))
                .thenReturn(LocalDate.of(2026, 7, 1));
        when(stockBatchJobLauncher.rolloverClosingPricesScheduled())
                .thenReturn(completedResponse(MarketCloseRolloverJob.JOB_NAME));
        when(stockBatchJobLauncher.settlePortfoliosScheduled())
                .thenReturn(completedResponse(PortfolioSettlementJob.JOB_NAME));
        when(marketCloseRolloverService.hasCompletedFullCloseRun(LocalDate.of(2026, 7, 1)))
                .thenReturn(false, true);
        when(postProcessingCompletionService.isComplete(LocalDate.of(2026, 7, 1)))
                .thenReturn(false, true);

        portfolioSettlementScheduler.rolloverSimulationDayIfNeeded();
        portfolioSettlementScheduler.rolloverSimulationDayIfNeeded();

        verify(stockBatchJobLauncher).rolloverClosingPricesScheduled();
        verify(stockBatchJobLauncher).settlePortfoliosScheduled();
    }

    @Test
    void portfolioSettlementScheduler_failedSettlementRetriesSameSimulationDate() {
        PortfolioSettlementScheduler portfolioSettlementScheduler =
                newPortfolioSettlementScheduler();
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "marketCloseSchedulerConfigured", true);
        ReflectionTestUtils.setField(portfolioSettlementScheduler, "settlementSchedulerConfigured", true);
        when(simulationMarketSessionService.currentSimulationDate())
                .thenReturn(LocalDate.of(2026, 7, 1))
                .thenReturn(LocalDate.of(2026, 7, 1))
                .thenReturn(LocalDate.of(2026, 7, 1));
        when(stockBatchJobLauncher.rolloverClosingPricesScheduled())
                .thenReturn(completedResponse(MarketCloseRolloverJob.JOB_NAME))
                .thenReturn(completedResponse(MarketCloseRolloverJob.JOB_NAME));
        when(stockBatchJobLauncher.settlePortfoliosScheduled())
                .thenReturn(failedResponse(PortfolioSettlementJob.JOB_NAME))
                .thenReturn(completedResponse(PortfolioSettlementJob.JOB_NAME));
        when(marketCloseRolloverService.hasCompletedFullCloseRun(LocalDate.of(2026, 7, 1)))
                .thenReturn(false, true, true);
        when(postProcessingCompletionService.isComplete(LocalDate.of(2026, 7, 1)))
                .thenReturn(false, false, true, true);

        portfolioSettlementScheduler.rolloverSimulationDayIfNeeded();
        portfolioSettlementScheduler.rolloverSimulationDayIfNeeded();
        portfolioSettlementScheduler.rolloverSimulationDayIfNeeded();

        verify(stockBatchJobLauncher).rolloverClosingPricesScheduled();
        verify(stockBatchJobLauncher, times(2)).settlePortfoliosScheduled();
    }

    private AutoParticipantCashFlowScheduler createAutoParticipantCashFlowScheduler() {
        return new AutoParticipantCashFlowScheduler(
                stockBatchJobLauncher,
                scheduledJobGuard,
                simulationMarketSessionService
        );
    }

    private PortfolioSettlementScheduler newPortfolioSettlementScheduler() {
        return new PortfolioSettlementScheduler(
                stockBatchJobLauncher,
                scheduledJobGuard,
                simulationMarketSessionService,
                orderBookMarketSessionStateService,
                marketCloseRolloverService,
                postProcessingCompletionService,
                postCloseCycleService,
                portfolioSettlementLifecycleService,
                mock(stock.batch.service.marketclose.biz.MarketSessionFenceService.class)
        );
    }

    private PostCloseCycle frozenCycle(LocalDate businessDate) {
        return new PostCloseCycle(
                1L,
                businessDate,
                PostCloseScopeType.FULL_MARKET,
                "ALL",
                stock.batch.service.marketclose.model.PostCloseCycleKind.TRADING,
                null,
                PostClosePhase.LEDGER_FROZEN,
                PostCloseCycleStatus.PENDING,
                1,
                1L,
                1L,
                businessDate.atTime(18, 10),
                0,
                null,
                null,
                null
        );
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
