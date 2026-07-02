package stock.batch.service.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import stock.batch.service.batch.automarket.job.AutoParticipantCashFlowJob;
import stock.batch.service.batch.common.support.StockBatchJobLauncher;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "stock.batch.auto-participant-cash-flow", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AutoParticipantCashFlowScheduler {

    private final StockBatchJobLauncher stockBatchJobLauncher;
    private final StockBatchScheduledJobGuard scheduledJobGuard;

    @Scheduled(
            initialDelayString = "${stock.batch.auto-participant-cash-flow.initial-delay-ms:5000}",
            fixedDelayString = "${stock.batch.auto-participant-cash-flow.fixed-delay-ms:60000}"
    )
    public void fundAutoParticipants() {
        scheduledJobGuard.runIfEnabled(
                AutoParticipantCashFlowJob.JOB_NAME,
                true,
                stockBatchJobLauncher::fundAutoParticipants
        );
    }
}
