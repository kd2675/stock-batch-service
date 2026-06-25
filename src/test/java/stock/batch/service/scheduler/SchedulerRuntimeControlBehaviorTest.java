package stock.batch.service.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import stock.batch.service.batch.automarket.job.AutoMarketJob;
import stock.batch.service.batch.automarket.job.AutoParticipantCashFlowJob;
import stock.batch.service.batch.common.policy.BatchJobRuntimeControl;
import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.batch.corporateaction.job.CorporateActionJob;
import stock.batch.service.batch.execution.job.OrderBookExecutionJob;
import stock.batch.service.batch.execution.job.VirtualPriceExecutionJob;
import stock.batch.service.batch.marketclose.job.MarketCloseRolloverJob;
import stock.batch.service.batch.marketdata.job.MarketDataRefreshJob;
import stock.batch.service.batch.settlement.job.PortfolioSettlementJob;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SchedulerRuntimeControlBehaviorTest {

    private StockBatchJobLauncher stockBatchJobLauncher;
    private BatchJobRuntimeControl batchJobRuntimeControl;

    @BeforeEach
    void setUp() {
        stockBatchJobLauncher = mock(StockBatchJobLauncher.class);
        batchJobRuntimeControl = mock(BatchJobRuntimeControl.class);
    }

    @Test
    void autoMarketScheduler_checksRuntimeControlBeforeLaunching() {
        AutoMarketScheduler scheduler = new AutoMarketScheduler(stockBatchJobLauncher, batchJobRuntimeControl);

        assertSimpleSchedulerGate(
                AutoMarketJob.JOB_NAME,
                scheduler::runAutoMarket,
                () -> verify(stockBatchJobLauncher).runAutoMarket()
        );
    }

    @Test
    void autoParticipantCashFlowScheduler_checksRuntimeControlBeforeLaunching() {
        AutoParticipantCashFlowScheduler scheduler = new AutoParticipantCashFlowScheduler(
                stockBatchJobLauncher,
                batchJobRuntimeControl
        );

        assertSimpleSchedulerGate(
                AutoParticipantCashFlowJob.JOB_NAME,
                scheduler::fundAutoParticipants,
                () -> verify(stockBatchJobLauncher).fundAutoParticipants()
        );
    }

    @Test
    void corporateActionScheduler_checksRuntimeControlBeforeLaunching() {
        CorporateActionScheduler scheduler = new CorporateActionScheduler(stockBatchJobLauncher, batchJobRuntimeControl);

        assertSimpleSchedulerGate(
                CorporateActionJob.JOB_NAME,
                scheduler::applyCorporateActions,
                () -> verify(stockBatchJobLauncher).applyCorporateActions()
        );
    }

    @Test
    void marketDataRefreshScheduler_checksRuntimeControlBeforeLaunching() {
        MarketDataRefreshScheduler scheduler = new MarketDataRefreshScheduler(stockBatchJobLauncher, batchJobRuntimeControl);

        assertSimpleSchedulerGate(
                MarketDataRefreshJob.JOB_NAME,
                scheduler::refreshMarketData,
                () -> verify(stockBatchJobLauncher).refreshMarketData()
        );
    }

    @Test
    void orderBookExecutionScheduler_checksRuntimeControlBeforeLaunching() {
        OrderBookExecutionScheduler scheduler = new OrderBookExecutionScheduler(stockBatchJobLauncher, batchJobRuntimeControl);

        assertSimpleSchedulerGate(
                OrderBookExecutionJob.JOB_NAME,
                scheduler::executeOrderBookOrders,
                () -> verify(stockBatchJobLauncher).executeOrderBookOrders()
        );
    }

    @Test
    void virtualPriceExecutionScheduler_checksRuntimeControlBeforeLaunching() {
        VirtualPriceExecutionScheduler scheduler = new VirtualPriceExecutionScheduler(
                stockBatchJobLauncher,
                batchJobRuntimeControl
        );

        assertSimpleSchedulerGate(
                VirtualPriceExecutionJob.JOB_NAME,
                scheduler::executeVirtualPriceOrders,
                () -> verify(stockBatchJobLauncher).executeVirtualPriceOrders()
        );
    }

    @Test
    void portfolioSettlementScheduler_checksRuntimeControlForEachJobBeforeLaunching() {
        PortfolioSettlementScheduler scheduler = new PortfolioSettlementScheduler(
                stockBatchJobLauncher,
                batchJobRuntimeControl
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

        scheduler.settlePortfolios();

        verify(batchJobRuntimeControl).shouldRunScheduledJob(MarketCloseRolloverJob.JOB_NAME, true);
        verify(batchJobRuntimeControl).shouldRunScheduledJob(PortfolioSettlementJob.JOB_NAME, true);
        verify(stockBatchJobLauncher).rolloverClosingPrices();
        verify(stockBatchJobLauncher).settlePortfolios();
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
}
