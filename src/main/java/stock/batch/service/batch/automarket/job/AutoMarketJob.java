package stock.batch.service.batch.automarket.job;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import stock.batch.service.automarket.biz.AutoMarketService;
import stock.batch.service.batch.common.support.StockBatchJob;

@Component
@RequiredArgsConstructor
public class AutoMarketJob implements StockBatchJob {

    public static final String JOB_NAME = "auto-market";
    private static final String EXECUTION_MODE = "order-book";

    private final AutoMarketService autoMarketService;

    @Override
    public String jobName() {
        return JOB_NAME;
    }

    @Override
    public String executionMode() {
        return EXECUTION_MODE;
    }

    @Override
    public boolean requiresJobLock() {
        return false;
    }

    @Override
    public boolean recordsExecutionHistory() {
        return false;
    }

    @Override
    public int run() {
        return autoMarketService.runAutoMarketStep();
    }
}
