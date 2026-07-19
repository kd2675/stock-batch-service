package stock.batch.service.scheduler;

import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.common.support.StockBatchJobRunner;
import stock.batch.service.batch.execution.job.ExecutionAccountDaySummaryFlushJob;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;

@Component
@RequiredArgsConstructor
public class ExecutionAccountDaySummaryScheduler {

    private static final long MIN_FLUSH_FIXED_DELAY_MS = 30_000L;

    private final ExecutionAccountDaySummaryFlushJob flushJob;
    private final StockBatchJobRunner stockBatchJobRunner;
    private final StockBatchScheduledJobGuard scheduledJobGuard;
    private final MarketSessionFenceService marketSessionFenceService;

    @Value("${stock.batch.execution-account-summary.enabled:true}")
    private boolean schedulerConfigured;

    @Value("${stock.batch.execution-account-summary.flush-fixed-delay-ms:30000}")
    private long flushFixedDelayMs;

    @PostConstruct
    void validateVolumeConfiguration() {
        if (flushFixedDelayMs < MIN_FLUSH_FIXED_DELAY_MS) {
            throw new IllegalStateException(
                    "stock.batch.execution-account-summary.flush-fixed-delay-ms must be at least %d: %d"
                            .formatted(MIN_FLUSH_FIXED_DELAY_MS, flushFixedDelayMs)
            );
        }
    }

    @Scheduled(
            scheduler = StockBatchSchedulerNames.MAINTENANCE,
            initialDelayString = "${stock.batch.execution-account-summary.initial-delay-ms:30000}",
            fixedDelayString = "${stock.batch.execution-account-summary.flush-fixed-delay-ms:30000}"
    )
    public void flush() {
        if (!flushJob.hasWork()) {
            return;
        }
        if (!marketSessionFenceService.hasOpenMarket()) {
            return;
        }
        scheduledJobGuard.runIfEnabled(
                ExecutionAccountDaySummaryFlushJob.JOB_NAME,
                schedulerConfigured,
                () -> stockBatchJobRunner.run(flushJob)
        );
    }
}
