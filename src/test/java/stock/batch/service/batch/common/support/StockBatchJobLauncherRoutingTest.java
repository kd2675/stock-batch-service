package stock.batch.service.batch.common.support;

import org.junit.jupiter.api.Test;
import stock.batch.service.automarket.biz.AutoParticipantCashFlowRuntimeControl;
import stock.batch.service.automarket.biz.AutoMarketOrderExpiryJobService;
import stock.batch.service.automarket.biz.AutoMarketService;
import stock.batch.service.automarket.biz.AutoParticipantCashFlowService;
import stock.batch.service.automarket.biz.AutoMarketDailyRegimePreCreateService;
import stock.batch.service.automarket.biz.AutoMarketProfileQueueReconcileService;
import stock.batch.service.automarket.biz.ListingAutoMarketJobService;
import stock.batch.service.batch.automarket.job.AutoMarketOrderExpiryJob;
import stock.batch.service.batch.automarket.job.AutoMarketJob;
import stock.batch.service.batch.automarket.job.AutoParticipantCashFlowJob;
import stock.batch.service.batch.automarket.job.AutoMarketDailyRegimePreCreateJob;
import stock.batch.service.batch.automarket.job.AutoMarketProfileQueueReconcileJob;
import stock.batch.service.batch.automarket.job.ListingAutoMarketJob;
import stock.batch.service.batch.corporateaction.job.CorporateActionJob;
import stock.batch.service.batch.execution.job.OrderBookExecutionJob;
import stock.batch.service.batch.holdingcleanup.job.HoldingCleanupJob;
import stock.batch.service.batch.marketclose.job.MarketCloseRolloverJob;
import stock.batch.service.batch.marketdata.job.MarketDataRefreshJob;
import stock.batch.service.batch.settlement.job.PortfolioSettlementJob;
import stock.batch.service.common.vo.StockBatchJobRunResponse;
import stock.batch.service.corporateaction.biz.CorporateActionService;
import stock.batch.service.execution.biz.InternalOrderBookExecutionService;
import stock.batch.service.holdingcleanup.biz.HoldingCleanupService;
import stock.batch.service.marketclose.biz.MarketCloseRolloverService;
import stock.batch.service.marketdata.biz.MarketDataRefreshService;
import stock.batch.service.settlement.biz.PortfolioSettlementService;
import stock.batch.service.simulation.SimulationMarketSessionService;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class StockBatchJobLauncherRoutingTest {

    private final StockBatchJobRunner stockBatchJobRunner = mock(StockBatchJobRunner.class);
    private final AutoParticipantCashFlowRuntimeControl autoParticipantCashFlowRuntimeControl =
            mock(AutoParticipantCashFlowRuntimeControl.class);
    private final SimulationMarketSessionService simulationMarketSessionService =
            mock(SimulationMarketSessionService.class);
    private final MarketDataRefreshService marketDataRefreshService = mock(MarketDataRefreshService.class);
    private final InternalOrderBookExecutionService internalOrderBookExecutionService =
            mock(InternalOrderBookExecutionService.class);
    private final AutoParticipantCashFlowService autoParticipantCashFlowService =
            mock(AutoParticipantCashFlowService.class);
    private final AutoMarketDailyRegimePreCreateService autoMarketDailyRegimePreCreateService =
            mock(AutoMarketDailyRegimePreCreateService.class);
    private final AutoMarketProfileQueueReconcileService autoMarketProfileQueueReconcileService =
            mock(AutoMarketProfileQueueReconcileService.class);
    private final AutoMarketService autoMarketService = mock(AutoMarketService.class);
    private final AutoMarketOrderExpiryJobService autoMarketOrderExpiryJobService =
            mock(AutoMarketOrderExpiryJobService.class);
    private final ListingAutoMarketJobService listingAutoMarketJobService =
            mock(ListingAutoMarketJobService.class);
    private final PortfolioSettlementService portfolioSettlementService = mock(PortfolioSettlementService.class);
    private final MarketCloseRolloverService marketCloseRolloverService = mock(MarketCloseRolloverService.class);
    private final CorporateActionService corporateActionService = mock(CorporateActionService.class);
    private final HoldingCleanupService holdingCleanupService = mock(HoldingCleanupService.class);

    private final MarketDataRefreshJob marketDataRefreshJob = new MarketDataRefreshJob(marketDataRefreshService);
    private final OrderBookExecutionJob orderBookExecutionJob = new OrderBookExecutionJob(internalOrderBookExecutionService);
    private final AutoParticipantCashFlowJob autoParticipantCashFlowJob =
            new AutoParticipantCashFlowJob(autoParticipantCashFlowService);
    private final AutoMarketDailyRegimePreCreateJob autoMarketDailyRegimePreCreateJob =
            new AutoMarketDailyRegimePreCreateJob(autoMarketDailyRegimePreCreateService);
    private final AutoMarketProfileQueueReconcileJob autoMarketProfileQueueReconcileJob =
            new AutoMarketProfileQueueReconcileJob(autoMarketProfileQueueReconcileService);
    private final AutoMarketJob autoMarketJob = new AutoMarketJob(autoMarketService);
    private final AutoMarketOrderExpiryJob autoMarketOrderExpiryJob =
            new AutoMarketOrderExpiryJob(autoMarketOrderExpiryJobService);
    private final ListingAutoMarketJob listingAutoMarketJob = new ListingAutoMarketJob(listingAutoMarketJobService);
    private final PortfolioSettlementJob portfolioSettlementJob = new PortfolioSettlementJob(portfolioSettlementService);
    private final MarketCloseRolloverJob marketCloseRolloverJob = new MarketCloseRolloverJob(marketCloseRolloverService);
    private final CorporateActionJob corporateActionJob = new CorporateActionJob(corporateActionService);
    private final HoldingCleanupJob holdingCleanupJob = new HoldingCleanupJob(holdingCleanupService);

    private final StockBatchJobLauncher stockBatchJobLauncher = new StockBatchJobLauncher(
            stockBatchJobRunner,
            autoParticipantCashFlowRuntimeControl,
            simulationMarketSessionService,
            marketDataRefreshJob,
            orderBookExecutionJob,
            autoParticipantCashFlowJob,
            autoMarketDailyRegimePreCreateJob,
            autoMarketProfileQueueReconcileJob,
            autoMarketJob,
            autoMarketOrderExpiryJob,
            listingAutoMarketJob,
            portfolioSettlementJob,
            marketCloseRolloverJob,
            corporateActionJob,
            holdingCleanupJob
    );

    StockBatchJobLauncherRoutingTest() {
        when(autoParticipantCashFlowRuntimeControl.canRunManualCashFlow()).thenReturn(true);
    }

    @Test
    void launcherMethods_routeEveryJobThroughRunner() {
        assertRoutesThroughRunner(marketDataRefreshJob, stockBatchJobLauncher::refreshMarketData);
        assertRoutesThroughRunner(orderBookExecutionJob, stockBatchJobLauncher::executeOrderBookOrders);
        assertRoutesThroughRunner(autoParticipantCashFlowJob, stockBatchJobLauncher::fundAutoParticipants);
        assertRoutesThroughRunner(autoMarketDailyRegimePreCreateJob, stockBatchJobLauncher::preCreateAutoMarketDailyRegimes);
        assertRoutesThroughRunner(autoMarketProfileQueueReconcileJob, stockBatchJobLauncher::reconcileAutoMarketProfileQueue);
        assertRoutesThroughRunner(autoMarketJob, stockBatchJobLauncher::runAutoMarket);
        assertRoutesThroughRunner(autoMarketOrderExpiryJob, stockBatchJobLauncher::expireAutoMarketOrders);
        assertRoutesThroughRunner(listingAutoMarketJob, stockBatchJobLauncher::runListingAutoMarket);
        assertRoutesThroughRunner(portfolioSettlementJob, stockBatchJobLauncher::settlePortfolios);
        assertRoutesThroughRunner(marketCloseRolloverJob, stockBatchJobLauncher::rolloverClosingPrices);
        assertRoutesThroughRunner(corporateActionJob, stockBatchJobLauncher::applyCorporateActions);
        assertRoutesThroughRunner(holdingCleanupJob, stockBatchJobLauncher::cleanupEmptyHoldings);

        verifyNoMoreInteractions(stockBatchJobRunner);
        verifyNoInteractions(
                marketDataRefreshService,
                internalOrderBookExecutionService,
                autoParticipantCashFlowService,
                autoMarketDailyRegimePreCreateService,
                autoMarketService,
                autoMarketOrderExpiryJobService,
                listingAutoMarketJobService,
                portfolioSettlementService,
                marketCloseRolloverService,
                corporateActionService,
                holdingCleanupService
        );
    }

    private void assertRoutesThroughRunner(StockBatchJob job, JobLauncherCall launcherCall) {
        StockBatchJobRunResponse expectedResponse = response(job);
        when(stockBatchJobRunner.run(same(job))).thenReturn(expectedResponse);

        StockBatchJobRunResponse actualResponse = launcherCall.run();

        assertThat(actualResponse).isSameAs(expectedResponse);
        verify(stockBatchJobRunner).run(same(job));
    }

    private StockBatchJobRunResponse response(StockBatchJob job) {
        LocalDateTime now = LocalDateTime.of(2026, 6, 25, 17, 30);
        return new StockBatchJobRunResponse(
                job.jobName(),
                "COMPLETED",
                job.executionMode(),
                1,
                "Job completed",
                now,
                now
        );
    }

    @FunctionalInterface
    private interface JobLauncherCall {
        StockBatchJobRunResponse run();
    }
}
