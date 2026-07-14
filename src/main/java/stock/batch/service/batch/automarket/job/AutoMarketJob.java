package stock.batch.service.batch.automarket.job;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import stock.batch.service.automarket.biz.AutoMarketService;
import stock.batch.service.batch.common.support.LightweightBatchTask;

@Component
@RequiredArgsConstructor
public class AutoMarketJob implements LightweightBatchTask {

    public static final String JOB_NAME = "auto-market";
    private static final String EXECUTION_MODE = "order-book";

    private final AutoMarketService autoMarketService;

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
        return autoMarketService.runAutoMarketStep();
    }
}
