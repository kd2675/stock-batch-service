package stock.batch.service.scheduler;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import stock.batch.service.batch.automarket.job.AutoMarketDailyRegimePreCreateJob;
import stock.batch.service.batch.automarket.job.AutoMarketJob;
import stock.batch.service.batch.automarket.job.AutoMarketOrderExpiryJob;
import stock.batch.service.batch.automarket.job.AutoMarketProfileQueueReconcileJob;
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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
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
        AutoMarketScheduler scheduler = newAutoMarketScheduler(command -> command.run());

        assertSimpleSchedulerGate(
                AutoMarketJob.JOB_NAME,
                scheduler::runAutoMarket,
                () -> verify(stockBatchJobLauncher).runAutoMarket()
        );
    }

    @Test
    void autoMarketOrderExpiryScheduler_checksRuntimeControlBeforeLaunching() {
        AutoMarketScheduler scheduler = newAutoMarketScheduler(command -> command.run());

        assertSimpleSchedulerGate(
                AutoMarketOrderExpiryJob.JOB_NAME,
                scheduler::expireAutoMarketOrders,
                () -> verify(stockBatchJobLauncher).expireAutoMarketOrders()
        );
    }

    @Test
    void listingAutoMarketScheduler_checksRuntimeControlBeforeLaunching() {
        AutoMarketScheduler scheduler = newAutoMarketScheduler(command -> command.run());

        assertSimpleSchedulerGate(
                ListingAutoMarketJob.JOB_NAME,
                scheduler::runListingAutoMarket,
                () -> verify(stockBatchJobLauncher).runListingAutoMarket()
        );
    }

    @Test
    void autoMarketDailyRegimePreCreateScheduler_checksRuntimeControlBeforeLaunching() {
        AutoMarketScheduler scheduler = newAutoMarketScheduler(command -> command.run());

        assertSimpleSchedulerGate(
                AutoMarketDailyRegimePreCreateJob.JOB_NAME,
                scheduler::preCreateDailyRegimes,
                () -> verify(stockBatchJobLauncher).preCreateAutoMarketDailyRegimes()
        );
    }

    @Test
    void autoMarketScheduler_outsideRegularSession_skipsBeforeRuntimeControl() {
        AutoMarketScheduler scheduler = newAutoMarketScheduler(command -> command.run());
        when(simulationMarketSessionService.isRegularSession()).thenReturn(false);

        scheduler.runAutoMarket();
        scheduler.expireAutoMarketOrders();
        scheduler.runListingAutoMarket();

        verifyNoInteractions(batchJobRuntimeControl, stockBatchJobLauncher);
    }

    @Test
    void autoMarketDailyRegimePreCreateScheduler_outsideRegularSession_stillChecksRuntimeControl() {
        AutoMarketScheduler scheduler = newAutoMarketScheduler(command -> command.run());
        when(simulationMarketSessionService.isRegularSession()).thenReturn(false);

        assertSimpleSchedulerGate(
                AutoMarketDailyRegimePreCreateJob.JOB_NAME,
                scheduler::preCreateDailyRegimes,
                () -> verify(stockBatchJobLauncher).preCreateAutoMarketDailyRegimes()
        );
    }

    @Test
    void autoMarketProfileQueueReconcileScheduler_checksRuntimeControlBeforeLaunching() {
        AutoMarketScheduler scheduler = newAutoMarketScheduler(command -> command.run());

        assertSimpleSchedulerGate(
                AutoMarketProfileQueueReconcileJob.JOB_NAME,
                scheduler::reconcileProfileQueue,
                () -> verify(stockBatchJobLauncher).reconcileAutoMarketProfileQueue()
        );
    }

    @Test
    void autoMarketProfileQueueReconcileScheduler_outsideRegularSession_stillChecksRuntimeControl() {
        AutoMarketScheduler scheduler = newAutoMarketScheduler(command -> command.run());
        when(simulationMarketSessionService.isRegularSession()).thenReturn(false);

        assertSimpleSchedulerGate(
                AutoMarketProfileQueueReconcileJob.JOB_NAME,
                scheduler::reconcileProfileQueue,
                () -> verify(stockBatchJobLauncher).reconcileAutoMarketProfileQueue()
        );
    }

    @Test
    void autoMarketProfileQueueReconcileStartupRun_checksRuntimeControlWithoutRegularSessionGate() {
        AutoMarketScheduler scheduler = newAutoMarketScheduler(command -> command.run());
        when(simulationMarketSessionService.isRegularSession()).thenReturn(false);

        assertSimpleSchedulerGate(
                AutoMarketProfileQueueReconcileJob.JOB_NAME,
                scheduler::reconcileProfileQueueOnStartup,
                () -> verify(stockBatchJobLauncher).reconcileAutoMarketProfileQueue()
        );
    }

    @Test
    void autoMarketScheduler_dispatchesRunsWithoutWaitingForEarlierRunsAndSkipsWhenSaturated() {
        RecordingExecutor executor = new RecordingExecutor(3);
        AutoMarketScheduler scheduler = newAutoMarketScheduler(executor);
        when(batchJobRuntimeControl.shouldRunScheduledJob(AutoMarketJob.JOB_NAME, true)).thenReturn(true);

        scheduler.runAutoMarket();
        scheduler.runAutoMarket();
        scheduler.runAutoMarket();
        scheduler.runAutoMarket();

        verify(batchJobRuntimeControl, never()).shouldRunScheduledJob(AutoMarketJob.JOB_NAME, true);
        verify(stockBatchJobLauncher, never()).runAutoMarket();

        executor.runAll();

        verify(batchJobRuntimeControl, times(3)).shouldRunScheduledJob(AutoMarketJob.JOB_NAME, true);
        verify(stockBatchJobLauncher, times(3)).runAutoMarket();
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
        OrderBookExecutionScheduler scheduler = newOrderBookExecutionScheduler(command -> command.run());

        assertSimpleSchedulerGate(
                OrderBookExecutionJob.JOB_NAME,
                scheduler::executeOrderBookOrders,
                () -> verify(stockBatchJobLauncher).executeOrderBookOrders()
        );
    }

    @Test
    void orderBookExecutionScheduler_outsideRegularSession_skipsBeforeRuntimeControl() {
        OrderBookExecutionScheduler scheduler = newOrderBookExecutionScheduler(command -> command.run());
        when(simulationMarketSessionService.isRegularSession()).thenReturn(false);

        scheduler.executeOrderBookOrders();

        verifyNoInteractions(batchJobRuntimeControl, stockBatchJobLauncher);
    }

    @Test
    void orderBookExecutionScheduler_dispatchesRunsWithoutWaitingForEarlierRunsAndSkipsWhenSaturated() {
        RecordingExecutor executor = new RecordingExecutor(3);
        OrderBookExecutionScheduler scheduler = newOrderBookExecutionScheduler(executor);
        when(batchJobRuntimeControl.shouldRunScheduledJob(OrderBookExecutionJob.JOB_NAME, true)).thenReturn(true);

        scheduler.executeOrderBookOrders();
        scheduler.executeOrderBookOrders();
        scheduler.executeOrderBookOrders();
        scheduler.executeOrderBookOrders();

        verify(batchJobRuntimeControl, never()).shouldRunScheduledJob(OrderBookExecutionJob.JOB_NAME, true);
        verify(stockBatchJobLauncher, never()).executeOrderBookOrders();

        executor.runAll();

        verify(batchJobRuntimeControl, times(3)).shouldRunScheduledJob(OrderBookExecutionJob.JOB_NAME, true);
        verify(stockBatchJobLauncher, times(3)).executeOrderBookOrders();
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

    @Test
    void scheduledJobGuard_contextClosed_skipsBeforeRuntimeControlLookup() {
        scheduledJobGuard.prepareShutdown();

        scheduledJobGuard.runIfEnabled(AutoMarketJob.JOB_NAME, true, stockBatchJobLauncher::runAutoMarket);

        verifyNoInteractions(batchJobRuntimeControl, stockBatchJobLauncher);
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

    private AutoMarketScheduler newAutoMarketScheduler(Executor autoMarketRunTaskExecutor) {
        return new AutoMarketScheduler(
                stockBatchJobLauncher,
                scheduledJobGuard,
                simulationMarketSessionService,
                autoMarketRunTaskExecutor
        );
    }

    private OrderBookExecutionScheduler newOrderBookExecutionScheduler(Executor orderBookExecutionRunTaskExecutor) {
        return new OrderBookExecutionScheduler(
                stockBatchJobLauncher,
                scheduledJobGuard,
                simulationMarketSessionService,
                orderBookExecutionRunTaskExecutor
        );
    }

    private static final class RecordingExecutor implements Executor {

        private final int maxPendingTasks;
        private final Queue<Runnable> pendingTasks = new ArrayDeque<>();

        private RecordingExecutor(int maxPendingTasks) {
            this.maxPendingTasks = maxPendingTasks;
        }

        @Override
        public void execute(Runnable command) {
            if (pendingTasks.size() >= maxPendingTasks) {
                throw new RejectedExecutionException("saturated");
            }
            pendingTasks.add(command);
        }

        private void runAll() {
            while (!pendingTasks.isEmpty()) {
                pendingTasks.remove().run();
            }
        }
    }
}
