package stock.batch.service.batch.marketdata.job;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.common.support.LightweightBatchTask;
import stock.batch.service.marketdata.biz.MarketDataRefreshService;

@Component
@RequiredArgsConstructor
public class MarketDataRefreshJob implements LightweightBatchTask {

    public static final String JOB_NAME = "market-data-refresh";
    private static final String EXECUTION_MODE = "n/a";

    private final MarketDataRefreshService marketDataRefreshService;

    @Override
    public String taskName() {
        return JOB_NAME;
    }

    @Override
    public String executionMode() {
        return EXECUTION_MODE;
    }

    @Override
    public boolean requiresJobLock() {
        return true;
    }

    @Override
    public int run() {
        return marketDataRefreshService.refreshWatchedPrices();
    }

    @Override
    public int runForPostCloseCycle(long closeCycleId) {
        return marketDataRefreshService.refreshWatchedPricesStrict();
    }
}
