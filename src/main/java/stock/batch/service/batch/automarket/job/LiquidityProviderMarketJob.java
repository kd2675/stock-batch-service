package stock.batch.service.batch.automarket.job;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import stock.batch.service.automarket.biz.LiquidityProviderMarketJobService;
import stock.batch.service.batch.common.support.LightweightBatchTask;

@Component
@RequiredArgsConstructor
public class LiquidityProviderMarketJob implements LightweightBatchTask {

    public static final String JOB_NAME = "liquidity-provider-market";
    private static final String EXECUTION_MODE = "passive-order-book";

    private final LiquidityProviderMarketJobService marketJobService;

    @Override
    public String taskName() {
        return JOB_NAME;
    }

    @Override
    public String executionMode() {
        return EXECUTION_MODE;
    }

    @Override
    public int run() {
        return marketJobService.runLiquidityProviderMarket();
    }
}
