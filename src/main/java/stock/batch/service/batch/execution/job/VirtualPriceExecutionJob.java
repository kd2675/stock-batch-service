package stock.batch.service.batch.execution.job;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.common.support.StockBatchJob;
import stock.batch.service.execution.biz.OrderExecutionService;

@Component
@RequiredArgsConstructor
public class VirtualPriceExecutionJob implements StockBatchJob {

    public static final String JOB_NAME = "virtual-price-execution";
    private static final String EXECUTION_MODE = "virtual-price";

    private final OrderExecutionService orderExecutionService;

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
        return orderExecutionService.executeEligibleOrders();
    }
}
