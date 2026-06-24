package stock.batch.service.batch.marketdata.job;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.common.support.StockBatchJob;
import stock.batch.service.marketdata.biz.MarketDataRefreshService;

@Component
@RequiredArgsConstructor
public class MarketDataRefreshJob implements StockBatchJob {

    public static final String JOB_NAME = "market-data-refresh";
    private static final String EXECUTION_MODE = "n/a";

    private final MarketDataRefreshService marketDataRefreshService;

    @Override
    public String jobName() {
        return JOB_NAME;
    }

    @Override
    public String executionMode() {
        return EXECUTION_MODE;
    }

    @Override
    public int run() {
        return marketDataRefreshService.refreshWatchedPrices();
    }
}
