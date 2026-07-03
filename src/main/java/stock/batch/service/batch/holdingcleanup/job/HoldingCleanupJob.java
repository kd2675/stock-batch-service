package stock.batch.service.batch.holdingcleanup.job;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.common.support.StockBatchJob;
import stock.batch.service.holdingcleanup.biz.HoldingCleanupService;

@Component
@RequiredArgsConstructor
public class HoldingCleanupJob implements StockBatchJob {

    public static final String JOB_NAME = "holding-cleanup";
    private static final String EXECUTION_MODE = "maintenance";

    private final HoldingCleanupService holdingCleanupService;

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
        return holdingCleanupService.cleanupEmptyHoldings();
    }
}
