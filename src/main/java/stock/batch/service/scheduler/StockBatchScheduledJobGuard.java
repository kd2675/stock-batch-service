package stock.batch.service.scheduler;

import java.time.LocalDateTime;
import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.common.policy.BatchJobRuntimeControl;
import stock.batch.service.batch.common.support.StockBatchJobRunResponses;
import stock.batch.service.common.vo.StockBatchJobRunResponse;

@Component
@RequiredArgsConstructor
@Slf4j
public class StockBatchScheduledJobGuard {

    private final BatchJobRuntimeControl batchJobRuntimeControl;

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

    private boolean shouldRun(String jobName, boolean schedulerConfigured) {
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
