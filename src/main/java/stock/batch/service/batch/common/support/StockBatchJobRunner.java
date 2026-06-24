package stock.batch.service.batch.common.support;

import java.time.LocalDateTime;
import java.util.concurrent.locks.ReentrantLock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.common.policy.BatchJobLockRegistry;
import stock.batch.service.common.vo.StockBatchJobRunResponse;

@Component
@RequiredArgsConstructor
@Slf4j
public class StockBatchJobRunner {

    private static final String COMPLETED = "COMPLETED";
    private static final String SKIPPED = "SKIPPED";
    private static final String FAILED = "FAILED";

    private final BatchJobLockRegistry batchJobLockRegistry;
    private final StockBatchJobRepositoryRecorder stockBatchJobRepositoryRecorder;

    public StockBatchJobRunResponse run(StockBatchJob job) {
        LocalDateTime startedAt = LocalDateTime.now();
        StockBatchJobExecutionRecord executionRecord = stockBatchJobRepositoryRecorder.start(job, startedAt);
        ReentrantLock lock = batchJobLockRegistry.lockFor(job.jobName());
        if (!lock.tryLock()) {
            LocalDateTime endedAt = LocalDateTime.now();
            stockBatchJobRepositoryRecorder.skip(executionRecord, endedAt);
            return new StockBatchJobRunResponse(
                    job.jobName(),
                    SKIPPED,
                    job.executionMode(),
                    0,
                    "Job is already running",
                    startedAt,
                    endedAt
            );
        }
        try {
            int processedCount = job.run();
            LocalDateTime endedAt = LocalDateTime.now();
            stockBatchJobRepositoryRecorder.complete(executionRecord, processedCount, endedAt);
            return new StockBatchJobRunResponse(
                    job.jobName(),
                    COMPLETED,
                    job.executionMode(),
                    processedCount,
                    "Job completed",
                    startedAt,
                    endedAt
            );
        } catch (RuntimeException ex) {
            LocalDateTime endedAt = LocalDateTime.now();
            stockBatchJobRepositoryRecorder.fail(executionRecord, ex, endedAt);
            log.warn(
                    "Stock batch job failed: job={}, mode={}, reason={}",
                    job.jobName(),
                    job.executionMode(),
                    ex.getMessage(),
                    ex
            );
            return new StockBatchJobRunResponse(
                    job.jobName(),
                    FAILED,
                    job.executionMode(),
                    0,
                    ex.getMessage(),
                    startedAt,
                    endedAt
            );
        } finally {
            lock.unlock();
        }
    }
}
