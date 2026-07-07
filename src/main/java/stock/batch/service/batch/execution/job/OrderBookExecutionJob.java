package stock.batch.service.batch.execution.job;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.common.support.StockBatchJob;
import stock.batch.service.execution.biz.InternalOrderBookExecutionService;

@Component
@RequiredArgsConstructor
public class OrderBookExecutionJob implements StockBatchJob {

    public static final String JOB_NAME = "order-book-execution";
    private static final String EXECUTION_MODE = "order-book";

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
    public boolean requiresJobLock() {
        return false;
    }

    @Override
    public int run() {
        return internalOrderBookExecutionService.executeEligibleOrders();
    }
}
