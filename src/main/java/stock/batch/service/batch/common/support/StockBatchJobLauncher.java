package stock.batch.service.batch.common.support;

import java.util.function.IntSupplier;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import stock.batch.service.batch.automarket.job.AutoParticipantCashFlowJob;
import stock.batch.service.batch.automarket.job.AutoMarketJob;
import stock.batch.service.batch.corporateaction.job.CorporateActionJob;
import stock.batch.service.batch.execution.job.OrderBookExecutionJob;
import stock.batch.service.batch.execution.job.VirtualPriceExecutionJob;
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
    private final AutoMarketJob autoMarketJob;
    private final PortfolioSettlementJob portfolioSettlementJob;
    private final MarketCloseRolloverJob marketCloseRolloverJob;
    private final CorporateActionJob corporateActionJob;

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
        return stockBatchJobRunner.run(new DelegatingStockBatchJob(
                AutoParticipantCashFlowJob.JOB_NAME,
                "manual-recurring-cash",
                autoParticipantCashFlowJob::runManually
        ));
    }

    public StockBatchJobRunResponse runAutoMarket() {
        return stockBatchJobRunner.run(autoMarketJob);
    }

    public StockBatchJobRunResponse settlePortfolios() {
        return stockBatchJobRunner.run(portfolioSettlementJob);
    }

    public StockBatchJobRunResponse rolloverClosingPrices() {
        return stockBatchJobRunner.run(marketCloseRolloverJob);
    }

    public StockBatchJobRunResponse rolloverClosingPrices(String symbol) {
        return stockBatchJobRunner.run(new DelegatingStockBatchJob(
                MarketCloseRolloverJob.JOB_NAME,
                "price-limit-base:" + symbol,
                () -> marketCloseRolloverJob.run(symbol)
        ));
    }

    public StockBatchJobRunResponse applyCorporateActions() {
        return stockBatchJobRunner.run(corporateActionJob);
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
