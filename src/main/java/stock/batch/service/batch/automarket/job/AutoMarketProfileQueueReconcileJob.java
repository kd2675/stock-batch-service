package stock.batch.service.batch.automarket.job;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import stock.batch.service.automarket.biz.AutoMarketProfileQueueReconcileService;
import stock.batch.service.batch.common.support.StockBatchJob;

@Component
@RequiredArgsConstructor
public class AutoMarketProfileQueueReconcileJob implements StockBatchJob {

    public static final String JOB_NAME = "auto-market-profile-queue-reconcile";
    private static final String EXECUTION_MODE = "profile-queue";

    private final AutoMarketProfileQueueReconcileService autoMarketProfileQueueReconcileService;

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
        return autoMarketProfileQueueReconcileService.reconcileReadyProfiles();
    }
}
