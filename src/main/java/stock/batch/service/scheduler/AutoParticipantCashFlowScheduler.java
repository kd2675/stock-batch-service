package stock.batch.service.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import stock.batch.service.batch.automarket.job.AutoParticipantCashFlowJob;
import stock.batch.service.batch.common.policy.BatchJobRuntimeControl;
import stock.batch.service.batch.common.support.StockBatchJobLauncher;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "stock.batch.auto-participant-cash-flow", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AutoParticipantCashFlowScheduler {

    private final StockBatchJobLauncher stockBatchJobLauncher;
    private final BatchJobRuntimeControl batchJobRuntimeControl;

    @Scheduled(
            initialDelayString = "${stock.batch.auto-participant-cash-flow.initial-delay-ms:5000}",
            fixedDelayString = "${stock.batch.auto-participant-cash-flow.fixed-delay-ms:1000}"
    )
    public void fundAutoParticipants() {
        if (!batchJobRuntimeControl.shouldRunScheduledJob(AutoParticipantCashFlowJob.JOB_NAME, true)) {
            return;
        }
        stockBatchJobLauncher.fundAutoParticipants();
    }
}
