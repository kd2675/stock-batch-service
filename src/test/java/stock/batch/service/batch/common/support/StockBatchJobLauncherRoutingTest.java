package stock.batch.service.batch.common.support;

import org.junit.jupiter.api.Test;
import stock.batch.service.automarket.biz.AutoMarketService;
import stock.batch.service.automarket.biz.AutoParticipantCashFlowService;
import stock.batch.service.batch.automarket.job.AutoMarketJob;
import stock.batch.service.batch.automarket.job.AutoParticipantCashFlowJob;
import stock.batch.service.batch.corporateaction.job.CorporateActionJob;
import stock.batch.service.batch.execution.job.OrderBookExecutionJob;
import stock.batch.service.batch.execution.job.VirtualPriceExecutionJob;
import stock.batch.service.batch.marketclose.job.MarketCloseRolloverJob;
import stock.batch.service.batch.marketdata.job.MarketDataRefreshJob;
import stock.batch.service.batch.settlement.job.PortfolioSettlementJob;
import stock.batch.service.common.vo.StockBatchJobRunResponse;
import stock.batch.service.corporateaction.biz.CorporateActionService;
import stock.batch.service.execution.biz.InternalOrderBookExecutionService;
import stock.batch.service.execution.biz.OrderExecutionService;
import stock.batch.service.marketclose.biz.MarketCloseRolloverService;
import stock.batch.service.marketdata.biz.MarketDataRefreshService;
import stock.batch.service.settlement.biz.PortfolioSettlementService;

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
    private final MarketDataRefreshService marketDataRefreshService = mock(MarketDataRefreshService.class);
    private final OrderExecutionService orderExecutionService = mock(OrderExecutionService.class);
    private final InternalOrderBookExecutionService internalOrderBookExecutionService =
            mock(InternalOrderBookExecutionService.class);
    private final AutoParticipantCashFlowService autoParticipantCashFlowService =
            mock(AutoParticipantCashFlowService.class);
    private final AutoMarketService autoMarketService = mock(AutoMarketService.class);
    private final PortfolioSettlementService portfolioSettlementService = mock(PortfolioSettlementService.class);
    private final MarketCloseRolloverService marketCloseRolloverService = mock(MarketCloseRolloverService.class);
    private final CorporateActionService corporateActionService = mock(CorporateActionService.class);

    private final MarketDataRefreshJob marketDataRefreshJob = new MarketDataRefreshJob(marketDataRefreshService);
    private final VirtualPriceExecutionJob virtualPriceExecutionJob = new VirtualPriceExecutionJob(orderExecutionService);
    private final OrderBookExecutionJob orderBookExecutionJob = new OrderBookExecutionJob(internalOrderBookExecutionService);
    private final AutoParticipantCashFlowJob autoParticipantCashFlowJob =
            new AutoParticipantCashFlowJob(autoParticipantCashFlowService);
    private final AutoMarketJob autoMarketJob = new AutoMarketJob(autoMarketService, internalOrderBookExecutionService);
    private final PortfolioSettlementJob portfolioSettlementJob = new PortfolioSettlementJob(portfolioSettlementService);
    private final MarketCloseRolloverJob marketCloseRolloverJob = new MarketCloseRolloverJob(marketCloseRolloverService);
    private final CorporateActionJob corporateActionJob = new CorporateActionJob(corporateActionService);

    private final StockBatchJobLauncher stockBatchJobLauncher = new StockBatchJobLauncher(
            stockBatchJobRunner,
            marketDataRefreshJob,
            virtualPriceExecutionJob,
            orderBookExecutionJob,
            autoParticipantCashFlowJob,
            autoMarketJob,
            portfolioSettlementJob,
            marketCloseRolloverJob,
            corporateActionJob
    );

    @Test
    void launcherMethods_routeEveryJobThroughRunner() {
        assertRoutesThroughRunner(marketDataRefreshJob, stockBatchJobLauncher::refreshMarketData);
        assertRoutesThroughRunner(virtualPriceExecutionJob, stockBatchJobLauncher::executeVirtualPriceOrders);
        assertRoutesThroughRunner(orderBookExecutionJob, stockBatchJobLauncher::executeOrderBookOrders);
        assertRoutesThroughRunner(autoParticipantCashFlowJob, stockBatchJobLauncher::fundAutoParticipants);
        assertRoutesThroughRunner(autoMarketJob, stockBatchJobLauncher::runAutoMarket);
        assertRoutesThroughRunner(portfolioSettlementJob, stockBatchJobLauncher::settlePortfolios);
        assertRoutesThroughRunner(marketCloseRolloverJob, stockBatchJobLauncher::rolloverClosingPrices);
        assertRoutesThroughRunner(corporateActionJob, stockBatchJobLauncher::applyCorporateActions);

        verifyNoMoreInteractions(stockBatchJobRunner);
        verifyNoInteractions(
                marketDataRefreshService,
                orderExecutionService,
                internalOrderBookExecutionService,
                autoParticipantCashFlowService,
                autoMarketService,
                portfolioSettlementService,
                marketCloseRolloverService,
                corporateActionService
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
