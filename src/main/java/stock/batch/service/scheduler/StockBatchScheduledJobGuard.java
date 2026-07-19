package stock.batch.service.scheduler;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.common.policy.BatchJobRuntimeControl;
import stock.batch.service.batch.common.support.StockBatchJobRunResponses;
import stock.batch.service.common.vo.StockBatchJobRunResponse;

@Component
@RequiredArgsConstructor
@Slf4j
public class StockBatchScheduledJobGuard {

    private final BatchJobRuntimeControl batchJobRuntimeControl;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    @EventListener(ContextClosedEvent.class)
    public void prepareShutdown() {
        shuttingDown.set(true);
    }

    public void runIfEnabled(String jobName, boolean schedulerConfigured, Runnable action) {
        if (!shouldRun(jobName, schedulerConfigured)) {
            return;
        }
        try {
            action.run();
        } catch (RuntimeException ex) {
            log.warn(
                    "Stock batch scheduled job failed outside runner: job={}, reason={}",
                    jobName,
                    ex.getMessage(),
                    ex
            );
        }
    }

    public StockBatchJobRunResponse runBatchIfEnabled(
            String jobName,
            boolean schedulerConfigured,
            Supplier<StockBatchJobRunResponse> action
    ) {
        if (!shouldRun(jobName, schedulerConfigured)) {
            return StockBatchJobRunResponses.scheduledDisabled(jobName, LocalDateTime.now());
        }
        try {
            return action.get();
        } catch (RuntimeException ex) {
            log.warn(
                    "Stock batch scheduled job failed outside runner: job={}, reason={}",
                    jobName,
                    ex.getMessage(),
                    ex
            );
            return StockBatchJobRunResponses.scheduledFailure(jobName, ex, LocalDateTime.now());
        }
    }

    public StockBatchJobRunResponse runOptionalBatchIfEnabled(
            String jobName,
            boolean schedulerConfigured,
            Supplier<StockBatchJobRunResponse> action
    ) {
        LocalDateTime now = LocalDateTime.now();
        if (shuttingDown.get()) {
            return StockBatchJobRunResponses.scheduledDisabled(jobName, now);
        }
        try {
            if (!batchJobRuntimeControl.shouldRunScheduledJob(jobName, schedulerConfigured)) {
                return StockBatchJobRunResponses.completedWithoutWork(
                        jobName,
                        "optional-post-close-maintenance",
                        "Optional post-close job is disabled; phase continues without maintenance work",
                        now
                );
            }
            return action.get();
        } catch (RuntimeException ex) {
            log.warn(
                    "Optional stock batch scheduled job failed: job={}, reason={}",
                    jobName,
                    ex.getMessage(),
                    ex
            );
            return StockBatchJobRunResponses.scheduledFailure(jobName, ex, LocalDateTime.now());
        }
    }

    private boolean shouldRun(String jobName, boolean schedulerConfigured) {
        if (shuttingDown.get()) {
            return false;
        }
        try {
            return batchJobRuntimeControl.shouldRunScheduledJob(jobName, schedulerConfigured);
        } catch (RuntimeException ex) {
            log.warn(
                    "Stock batch scheduled job runtime control check failed: job={}, reason={}",
                    jobName,
                    ex.getMessage(),
                    ex
            );
            return false;
        }
    }
}
