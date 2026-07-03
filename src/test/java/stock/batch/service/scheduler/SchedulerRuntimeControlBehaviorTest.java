package stock.batch.service.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import stock.batch.service.batch.automarket.job.AutoMarketJob;
import stock.batch.service.batch.automarket.job.AutoMarketOrderExpiryJob;
import stock.batch.service.batch.automarket.job.AutoParticipantCashFlowJob;
import stock.batch.service.batch.automarket.job.ListingAutoMarketJob;
import stock.batch.service.batch.common.policy.BatchJobRuntimeControl;
import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.batch.corporateaction.job.CorporateActionJob;
import stock.batch.service.batch.execution.job.OrderBookExecutionJob;
import stock.batch.service.batch.execution.job.VirtualPriceExecutionJob;
import stock.batch.service.batch.marketclose.job.MarketCloseRolloverJob;
import stock.batch.service.batch.marketdata.job.MarketDataRefreshJob;
import stock.batch.service.batch.settlement.job.PortfolioSettlementJob;
import stock.batch.service.common.vo.StockBatchJobRunResponse;
import stock.batch.service.marketclose.biz.OrderBookMarketSessionStateService;
import stock.batch.service.simulation.SimulationMarketSessionService;

import java.time.LocalDateTime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SchedulerRuntimeControlBehaviorTest {

    private StockBatchJobLauncher stockBatchJobLauncher;
    private BatchJobRuntimeControl batchJobRuntimeControl;
    private StockBatchScheduledJobGuard scheduledJobGuard;
    private SimulationMarketSessionService simulationMarketSessionService;
    private OrderBookMarketSessionStateService orderBookMarketSessionStateService;

    @BeforeEach
    void setUp() {
        stockBatchJobLauncher = mock(StockBatchJobLauncher.class);
        batchJobRuntimeControl = mock(BatchJobRuntimeControl.class);
        scheduledJobGuard = new StockBatchScheduledJobGuard(batchJobRuntimeControl);
        simulationMarketSessionService = mock(SimulationMarketSessionService.class);
        orderBookMarketSessionStateService = mock(OrderBookMarketSessionStateService.class);
        when(simulationMarketSessionService.isRegularSession()).thenReturn(true);
        when(simulationMarketSessionService.isAfterCloseSession()).thenReturn(true);
    }

    @Test
    void autoMarketScheduler_checksRuntimeControlBeforeLaunching() {
        AutoMarketScheduler scheduler = new AutoMarketScheduler(stockBatchJobLauncher, scheduledJobGuard, simulationMarketSessionService);

        assertSimpleSchedulerGate(
                AutoMarketJob.JOB_NAME,
                scheduler::runAutoMarket,
                () -> verify(stockBatchJobLauncher).runAutoMarket()
        );
    }

    @Test
    void autoMarketOrderExpiryScheduler_checksRuntimeControlBeforeLaunching() {
        AutoMarketScheduler scheduler = new AutoMarketScheduler(stockBatchJobLauncher, scheduledJobGuard, simulationMarketSessionService);

        assertSimpleSchedulerGate(
                AutoMarketOrderExpiryJob.JOB_NAME,
                scheduler::expireAutoMarketOrders,
                () -> verify(stockBatchJobLauncher).expireAutoMarketOrders()
        );
    }

    @Test
    void listingAutoMarketScheduler_checksRuntimeControlBeforeLaunching() {
        AutoMarketScheduler scheduler = new AutoMarketScheduler(stockBatchJobLauncher, scheduledJobGuard, simulationMarketSessionService);

        assertSimpleSchedulerGate(
                ListingAutoMarketJob.JOB_NAME,
                scheduler::runListingAutoMarket,
                () -> verify(stockBatchJobLauncher).runListingAutoMarket()
        );
    }

    @Test
    void autoMarketScheduler_outsideRegularSession_skipsBeforeRuntimeControl() {
        AutoMarketScheduler scheduler = new AutoMarketScheduler(stockBatchJobLauncher, scheduledJobGuard, simulationMarketSessionService);
        when(simulationMarketSessionService.isRegularSession()).thenReturn(false);

        scheduler.runAutoMarket();
        scheduler.expireAutoMarketOrders();
        scheduler.runListingAutoMarket();

        verifyNoInteractions(batchJobRuntimeControl, stockBatchJobLauncher);
    }

    @Test
    void autoParticipantCashFlowScheduler_checksRuntimeControlBeforeLaunching() {
        AutoParticipantCashFlowScheduler scheduler = new AutoParticipantCashFlowScheduler(
                stockBatchJobLauncher,
                scheduledJobGuard
        );

        assertSimpleSchedulerGate(
                AutoParticipantCashFlowJob.JOB_NAME,
                scheduler::fundAutoParticipants,
                () -> verify(stockBatchJobLauncher).fundAutoParticipants()
        );
    }

    @Test
    void corporateActionScheduler_checksRuntimeControlBeforeLaunching() {
        CorporateActionScheduler scheduler = new CorporateActionScheduler(stockBatchJobLauncher, scheduledJobGuard, simulationMarketSessionService);

        assertSimpleSchedulerGate(
                CorporateActionJob.JOB_NAME,
                scheduler::applyCorporateActions,
                () -> verify(stockBatchJobLauncher).applyCorporateActions()
        );
    }

    @Test
    void corporateActionScheduler_beforeClose_skipsBeforeRuntimeControl() {
        CorporateActionScheduler scheduler = new CorporateActionScheduler(stockBatchJobLauncher, scheduledJobGuard, simulationMarketSessionService);
        when(simulationMarketSessionService.isAfterCloseSession()).thenReturn(false);

        scheduler.applyCorporateActions();

        verifyNoInteractions(batchJobRuntimeControl, stockBatchJobLauncher);
    }

    @Test
    void marketDataRefreshScheduler_checksRuntimeControlBeforeLaunching() {
        MarketDataRefreshScheduler scheduler = new MarketDataRefreshScheduler(stockBatchJobLauncher, scheduledJobGuard);

        assertSimpleSchedulerGate(
                MarketDataRefreshJob.JOB_NAME,
                scheduler::refreshMarketData,
                () -> verify(stockBatchJobLauncher).refreshMarketData()
        );
    }

    @Test
    void orderBookExecutionScheduler_checksRuntimeControlBeforeLaunching() {
        OrderBookExecutionScheduler scheduler = new OrderBookExecutionScheduler(stockBatchJobLauncher, scheduledJobGuard, simulationMarketSessionService);

        assertSimpleSchedulerGate(
                OrderBookExecutionJob.JOB_NAME,
                scheduler::executeOrderBookOrders,
                () -> verify(stockBatchJobLauncher).executeOrderBookOrders()
        );
    }

    @Test
    void orderBookExecutionScheduler_outsideRegularSession_skipsBeforeRuntimeControl() {
        OrderBookExecutionScheduler scheduler = new OrderBookExecutionScheduler(stockBatchJobLauncher, scheduledJobGuard, simulationMarketSessionService);
        when(simulationMarketSessionService.isRegularSession()).thenReturn(false);

        scheduler.executeOrderBookOrders();

        verifyNoInteractions(batchJobRuntimeControl, stockBatchJobLauncher);
    }

    @Test
    void virtualPriceExecutionScheduler_checksRuntimeControlBeforeLaunching() {
        VirtualPriceExecutionScheduler scheduler = new VirtualPriceExecutionScheduler(
                stockBatchJobLauncher,
                scheduledJobGuard,
                simulationMarketSessionService
        );

        assertSimpleSchedulerGate(
                VirtualPriceExecutionJob.JOB_NAME,
                scheduler::executeVirtualPriceOrders,
                () -> verify(stockBatchJobLauncher).executeVirtualPriceOrders()
        );
    }

    @Test
    void virtualPriceExecutionScheduler_outsideRegularSession_skipsBeforeRuntimeControl() {
        VirtualPriceExecutionScheduler scheduler = new VirtualPriceExecutionScheduler(
                stockBatchJobLauncher,
                scheduledJobGuard,
                simulationMarketSessionService
        );
        when(simulationMarketSessionService.isRegularSession()).thenReturn(false);

        scheduler.executeVirtualPriceOrders();

        verifyNoInteractions(batchJobRuntimeControl, stockBatchJobLauncher);
    }

    @Test
    void portfolioSettlementScheduler_checksRuntimeControlForEachJobBeforeLaunching() {
        PortfolioSettlementScheduler scheduler = new PortfolioSettlementScheduler(
                stockBatchJobLauncher,
                scheduledJobGuard,
                simulationMarketSessionService,
                orderBookMarketSessionStateService
        );
        ReflectionTestUtils.setField(scheduler, "marketCloseSchedulerConfigured", true);
        ReflectionTestUtils.setField(scheduler, "settlementSchedulerConfigured", true);

        when(batchJobRuntimeControl.shouldRunScheduledJob(MarketCloseRolloverJob.JOB_NAME, true))
                .thenReturn(false);
        when(batchJobRuntimeControl.shouldRunScheduledJob(PortfolioSettlementJob.JOB_NAME, true))
                .thenReturn(false);

        scheduler.settlePortfolios();

        verify(batchJobRuntimeControl).shouldRunScheduledJob(MarketCloseRolloverJob.JOB_NAME, true);
        verify(batchJobRuntimeControl).shouldRunScheduledJob(PortfolioSettlementJob.JOB_NAME, true);
        verifyNoInteractions(stockBatchJobLauncher);

        reset(stockBatchJobLauncher, batchJobRuntimeControl);
        when(batchJobRuntimeControl.shouldRunScheduledJob(MarketCloseRolloverJob.JOB_NAME, true))
                .thenReturn(true);
        when(batchJobRuntimeControl.shouldRunScheduledJob(PortfolioSettlementJob.JOB_NAME, true))
                .thenReturn(true);
        when(stockBatchJobLauncher.rolloverClosingPrices())
                .thenReturn(completedResponse(MarketCloseRolloverJob.JOB_NAME));
        when(stockBatchJobLauncher.settlePortfolios())
                .thenReturn(completedResponse(PortfolioSettlementJob.JOB_NAME));

        scheduler.settlePortfolios();

        verify(batchJobRuntimeControl).shouldRunScheduledJob(MarketCloseRolloverJob.JOB_NAME, true);
        verify(batchJobRuntimeControl).shouldRunScheduledJob(PortfolioSettlementJob.JOB_NAME, true);
        verify(stockBatchJobLauncher).rolloverClosingPrices();
        verify(stockBatchJobLauncher).settlePortfolios();
    }

    @Test
    void portfolioSettlementScheduler_beforeClose_skipsPostCloseWork() {
        PortfolioSettlementScheduler scheduler = new PortfolioSettlementScheduler(
                stockBatchJobLauncher,
                scheduledJobGuard,
                simulationMarketSessionService,
                orderBookMarketSessionStateService
        );
        when(simulationMarketSessionService.isAfterCloseSession()).thenReturn(false);

        scheduler.rolloverSimulationDayIfNeeded();

        verifyNoInteractions(batchJobRuntimeControl, stockBatchJobLauncher);
    }

    @Test
    void scheduledJobGuard_runtimeControlThrows_skipsJobWithoutPropagatingException() {
        when(batchJobRuntimeControl.shouldRunScheduledJob(AutoMarketJob.JOB_NAME, true))
                .thenThrow(new IllegalStateException("runtime control db timeout"));

        scheduledJobGuard.runIfEnabled(AutoMarketJob.JOB_NAME, true, stockBatchJobLauncher::runAutoMarket);

        verify(batchJobRuntimeControl).shouldRunScheduledJob(AutoMarketJob.JOB_NAME, true);
        verifyNoInteractions(stockBatchJobLauncher);
    }

    @Test
    void scheduledJobGuard_launcherThrows_doesNotPropagateExceptionToSchedulerThread() {
        when(batchJobRuntimeControl.shouldRunScheduledJob(AutoMarketJob.JOB_NAME, true)).thenReturn(true);
        when(stockBatchJobLauncher.runAutoMarket()).thenThrow(new IllegalStateException("runner escaped failure"));

        scheduledJobGuard.runIfEnabled(AutoMarketJob.JOB_NAME, true, stockBatchJobLauncher::runAutoMarket);

        verify(batchJobRuntimeControl).shouldRunScheduledJob(AutoMarketJob.JOB_NAME, true);
        verify(stockBatchJobLauncher).runAutoMarket();
    }

    private void assertSimpleSchedulerGate(String jobName, Runnable scheduledAction, Runnable verifyLaunch) {
        when(batchJobRuntimeControl.shouldRunScheduledJob(jobName, true)).thenReturn(false);

        scheduledAction.run();

        verify(batchJobRuntimeControl).shouldRunScheduledJob(jobName, true);
        verifyNoInteractions(stockBatchJobLauncher);

        reset(stockBatchJobLauncher, batchJobRuntimeControl);
        when(batchJobRuntimeControl.shouldRunScheduledJob(jobName, true)).thenReturn(true);

        scheduledAction.run();

        verify(batchJobRuntimeControl).shouldRunScheduledJob(jobName, true);
        verifyLaunch.run();
    }

    private StockBatchJobRunResponse completedResponse(String jobName) {
        LocalDateTime now = LocalDateTime.now();
        return new StockBatchJobRunResponse(jobName, "COMPLETED", "test", 1, "completed", now, now);
    }
}
