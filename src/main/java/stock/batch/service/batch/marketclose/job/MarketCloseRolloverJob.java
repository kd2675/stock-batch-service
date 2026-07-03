package stock.batch.service.batch.marketclose.job;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.common.support.StockBatchJob;
import stock.batch.service.marketclose.biz.MarketCloseRolloverService;

@Component
@RequiredArgsConstructor
public class MarketCloseRolloverJob implements StockBatchJob {

    public static final String JOB_NAME = "market-close-rollover";
    private static final String EXECUTION_MODE = "price-limit-base";

    private final MarketCloseRolloverService marketCloseRolloverService;

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
        return marketCloseRolloverService.rolloverClosingPrices();
    }

    public int run(String symbol) {
        return marketCloseRolloverService.rolloverClosingPrices(symbol);
    }

    public int cancelOpenOrders(String symbol) {
        return marketCloseRolloverService.cancelOpenOrderBookOrders(symbol);
    }
}
