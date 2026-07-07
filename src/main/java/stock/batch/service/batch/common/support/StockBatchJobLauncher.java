package stock.batch.service.batch.common.support;

import java.util.function.IntSupplier;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import stock.batch.service.batch.automarket.job.AutoParticipantCashFlowJob;
import stock.batch.service.batch.automarket.job.AutoMarketDailyRegimePreCreateJob;
import stock.batch.service.batch.automarket.job.AutoMarketJob;
import stock.batch.service.batch.automarket.job.AutoMarketOrderExpiryJob;
import stock.batch.service.batch.automarket.job.AutoMarketProfileQueueReconcileJob;
import stock.batch.service.batch.automarket.job.ListingAutoMarketJob;
import stock.batch.service.batch.corporateaction.job.CorporateActionJob;
import stock.batch.service.batch.execution.job.OrderBookExecutionJob;
import stock.batch.service.batch.execution.job.VirtualPriceExecutionJob;
import stock.batch.service.batch.holdingcleanup.job.HoldingCleanupJob;
import stock.batch.service.batch.marketclose.job.MarketCloseRolloverJob;
import stock.batch.service.batch.marketdata.job.MarketDataRefreshJob;
import stock.batch.service.batch.settlement.job.PortfolioSettlementJob;
import stock.batch.service.common.vo.StockBatchJobRunResponse;

@Service
@RequiredArgsConstructor
public class StockBatchJobLauncher {

    private final StockBatchJobRunner stockBatchJobRunner;
    private final MarketDataRefreshJob marketDataRefreshJob;
    private final VirtualPriceExecutionJob virtualPriceExecutionJob;
    private final OrderBookExecutionJob orderBookExecutionJob;
    private final AutoParticipantCashFlowJob autoParticipantCashFlowJob;
    private final AutoMarketDailyRegimePreCreateJob autoMarketDailyRegimePreCreateJob;
    private final AutoMarketProfileQueueReconcileJob autoMarketProfileQueueReconcileJob;
    private final AutoMarketJob autoMarketJob;
    private final AutoMarketOrderExpiryJob autoMarketOrderExpiryJob;
    private final ListingAutoMarketJob listingAutoMarketJob;
    private final PortfolioSettlementJob portfolioSettlementJob;
    private final MarketCloseRolloverJob marketCloseRolloverJob;
    private final CorporateActionJob corporateActionJob;
    private final HoldingCleanupJob holdingCleanupJob;

    public StockBatchJobRunResponse refreshMarketData() {
        return stockBatchJobRunner.run(marketDataRefreshJob);
    }

    public StockBatchJobRunResponse executeVirtualPriceOrders() {
        return stockBatchJobRunner.run(virtualPriceExecutionJob);
    }

    public StockBatchJobRunResponse executeOrderBookOrders() {
        return stockBatchJobRunner.run(orderBookExecutionJob);
    }

    public StockBatchJobRunResponse fundAutoParticipants() {
        return stockBatchJobRunner.run(autoParticipantCashFlowJob);
    }

    public StockBatchJobRunResponse fundAutoParticipantsManually() {
        return runDelegatingJob(
                AutoParticipantCashFlowJob.JOB_NAME,
                "manual-recurring-cash",
                autoParticipantCashFlowJob::runManually
        );
    }

    public StockBatchJobRunResponse preCreateAutoMarketDailyRegimes() {
        return stockBatchJobRunner.run(autoMarketDailyRegimePreCreateJob);
    }

    public StockBatchJobRunResponse reconcileAutoMarketProfileQueue() {
        return stockBatchJobRunner.run(autoMarketProfileQueueReconcileJob);
    }

    public StockBatchJobRunResponse runAutoMarket() {
        return stockBatchJobRunner.run(autoMarketJob);
    }

    public StockBatchJobRunResponse expireAutoMarketOrders() {
        return stockBatchJobRunner.run(autoMarketOrderExpiryJob);
    }

    public StockBatchJobRunResponse runListingAutoMarket() {
        return stockBatchJobRunner.run(listingAutoMarketJob);
    }

    public StockBatchJobRunResponse settlePortfolios() {
        return stockBatchJobRunner.run(portfolioSettlementJob);
    }

    public StockBatchJobRunResponse rolloverClosingPrices() {
        return stockBatchJobRunner.run(marketCloseRolloverJob);
    }

    public StockBatchJobRunResponse rolloverClosingPrices(String symbol) {
        return runDelegatingJob(
                MarketCloseRolloverJob.JOB_NAME,
                "price-limit-base:" + symbol,
                () -> marketCloseRolloverJob.run(symbol)
        );
    }

    public StockBatchJobRunResponse cancelOpenOrderBookOrders(String symbol) {
        return runDelegatingJob(
                MarketCloseRolloverJob.JOB_NAME,
                "halt-open-order-cancel:" + symbol,
                () -> marketCloseRolloverJob.cancelOpenOrders(symbol)
        );
    }

    public StockBatchJobRunResponse applyCorporateActions() {
        return stockBatchJobRunner.run(corporateActionJob);
    }

    public StockBatchJobRunResponse cleanupEmptyHoldings() {
        return stockBatchJobRunner.run(holdingCleanupJob);
    }

    public boolean hasActiveJobs() {
        return stockBatchJobRunner.hasActiveJobs();
    }

    private StockBatchJobRunResponse runDelegatingJob(String jobName, String executionMode, IntSupplier runner) {
        return stockBatchJobRunner.run(new DelegatingStockBatchJob(jobName, executionMode, runner));
    }

    private record DelegatingStockBatchJob(
            String jobName,
            String executionMode,
            IntSupplier runner
    ) implements StockBatchJob {

        @Override
        public int run() {
            return runner.getAsInt();
        }
    }
}
