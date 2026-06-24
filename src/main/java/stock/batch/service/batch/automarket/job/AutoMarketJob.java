package stock.batch.service.batch.automarket.job;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import stock.batch.service.automarket.biz.AutoMarketService;
import stock.batch.service.batch.common.support.StockBatchJob;
import stock.batch.service.execution.biz.InternalOrderBookExecutionService;

@Component
@RequiredArgsConstructor
public class AutoMarketJob implements StockBatchJob {

    public static final String JOB_NAME = "auto-market";
    private static final String EXECUTION_MODE = "order-book";

    private final AutoMarketService autoMarketService;
    private final InternalOrderBookExecutionService internalOrderBookExecutionService;

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
        int generatedOrExpiredOrders = autoMarketService.runAutoMarketStep();
        int executions = internalOrderBookExecutionService.executeEligibleOrders();
        return generatedOrExpiredOrders + executions;
    }
}
