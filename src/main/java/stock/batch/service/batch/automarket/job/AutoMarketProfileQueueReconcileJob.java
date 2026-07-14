package stock.batch.service.batch.automarket.job;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import stock.batch.service.automarket.biz.AutoMarketProfileQueueReconcileService;
import stock.batch.service.batch.common.support.LightweightBatchTask;

@Component
@RequiredArgsConstructor
public class AutoMarketProfileQueueReconcileJob implements LightweightBatchTask {

    public static final String JOB_NAME = "auto-market-profile-queue-reconcile";
    private static final String EXECUTION_MODE = "profile-queue";

    private final AutoMarketProfileQueueReconcileService autoMarketProfileQueueReconcileService;

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
        return autoMarketProfileQueueReconcileService.reconcileReadyProfiles();
    }
}
