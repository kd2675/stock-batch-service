package stock.batch.service.batch.common.support;

import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.common.policy.BatchJobLockRegistry;
import stock.batch.service.common.vo.StockBatchJobRunResponse;

@Component
@DependsOn("simulationClockScheduler")
@Slf4j
public class StockBatchJobRunner {

    private final BatchJobLockRegistry batchJobLockRegistry;
    private final StockBatchJobRepositoryRecorder stockBatchJobRepositoryRecorder;
    private final long shutdownAwaitRunningJobsSeconds;
    private final ScheduledExecutorService lockHeartbeatExecutor;
    private final StockBatchJobLockHeartbeat lockHeartbeat;
    private final StockBatchActiveJobTracker activeJobTracker = new StockBatchActiveJobTracker();

    @Autowired
    public StockBatchJobRunner(
            BatchJobLockRegistry batchJobLockRegistry,
            StockBatchJobRepositoryRecorder stockBatchJobRepositoryRecorder,
            @Value("${stock.batch.job-lock.heartbeat-interval-seconds:30}") long lockHeartbeatIntervalSeconds,
            @Value("${stock.batch.shutdown.await-running-jobs-seconds:120}") long shutdownAwaitRunningJobsSeconds
    ) {
        this(
                batchJobLockRegistry,
                stockBatchJobRepositoryRecorder,
                lockHeartbeatIntervalSeconds,
                shutdownAwaitRunningJobsSeconds,
                newLockHeartbeatExecutor("stock-batch-lock-heartbeat")
        );
    }

    StockBatchJobRunner(
            BatchJobLockRegistry batchJobLockRegistry,
            StockBatchJobRepositoryRecorder stockBatchJobRepositoryRecorder
    ) {
        this(
                batchJobLockRegistry,
                stockBatchJobRepositoryRecorder,
                30,
                60,
                newLockHeartbeatExecutor("stock-batch-lock-heartbeat-test")
        );
    }

    StockBatchJobRunner(
            BatchJobLockRegistry batchJobLockRegistry,
            StockBatchJobRepositoryRecorder stockBatchJobRepositoryRecorder,
            long lockHeartbeatIntervalSeconds,
            ScheduledExecutorService lockHeartbeatExecutor
    ) {
        this(
                batchJobLockRegistry,
                stockBatchJobRepositoryRecorder,
                lockHeartbeatIntervalSeconds,
                60,
                lockHeartbeatExecutor
        );
    }

    StockBatchJobRunner(
            BatchJobLockRegistry batchJobLockRegistry,
            StockBatchJobRepositoryRecorder stockBatchJobRepositoryRecorder,
            long lockHeartbeatIntervalSeconds,
            long shutdownAwaitRunningJobsSeconds,
            ScheduledExecutorService lockHeartbeatExecutor
    ) {
        if (lockHeartbeatIntervalSeconds <= 0) {
            throw new IllegalArgumentException("lockHeartbeatIntervalSeconds must be positive");
        }
        if (lockHeartbeatIntervalSeconds >= batchJobLockRegistry.lockTtlSeconds()) {
            throw new IllegalArgumentException("lockHeartbeatIntervalSeconds must be shorter than lockTtlSeconds");
        }
        if (shutdownAwaitRunningJobsSeconds < 0) {
            throw new IllegalArgumentException("shutdownAwaitRunningJobsSeconds must not be negative");
        }
        this.batchJobLockRegistry = batchJobLockRegistry;
        this.stockBatchJobRepositoryRecorder = stockBatchJobRepositoryRecorder;
        this.shutdownAwaitRunningJobsSeconds = shutdownAwaitRunningJobsSeconds;
        this.lockHeartbeatExecutor = lockHeartbeatExecutor;
        this.lockHeartbeat = new StockBatchJobLockHeartbeat(
                batchJobLockRegistry,
                lockHeartbeatExecutor,
                lockHeartbeatIntervalSeconds
        );
    }

    public StockBatchJobRunResponse run(StockBatchJob job) {
        if (!activeJobTracker.tryEnter()) {
            LocalDateTime now = LocalDateTime.now();
            return StockBatchJobRunResponses.shuttingDown(job, now);
        }
        try {
            return runEnteredJob(job);
        } finally {
            activeJobTracker.leave();
        }
    }

    public boolean hasActiveJobs() {
        return activeJobTracker.hasActiveJobs();
    }

    private StockBatchJobRunResponse runEnteredJob(StockBatchJob job) {
        LocalDateTime startedAt = LocalDateTime.now();
        StockBatchJobExecutionRecord executionRecord;
        try {
            executionRecord = stockBatchJobRepositoryRecorder.start(job, startedAt);
        } catch (RuntimeException ex) {
            LocalDateTime endedAt = LocalDateTime.now();
            log.warn(
                    "Stock batch job repository start failed: job={}, mode={}, reason={}",
                    job.jobName(),
                    job.executionMode(),
                    ex.getMessage(),
                    ex
            );
            return StockBatchJobRunResponses.failed(job, ex, startedAt, endedAt);
        }
        if (!job.requiresJobLock()) {
            return runWithoutJobLock(job, executionRecord, startedAt);
        }
        boolean lockAcquired;
        try {
            lockAcquired = batchJobLockRegistry.tryAcquire(job.jobName(), startedAt);
        } catch (RuntimeException ex) {
            LocalDateTime endedAt = LocalDateTime.now();
            recordFailure(job, executionRecord, ex, endedAt, "lock acquisition failure");
            log.warn(
                    "Stock batch job lock acquisition failed: job={}, mode={}, reason={}",
                    job.jobName(),
                    job.executionMode(),
                    ex.getMessage(),
                    ex
            );
            return StockBatchJobRunResponses.failed(job, ex, startedAt, endedAt);
        }
        if (!lockAcquired) {
            LocalDateTime endedAt = LocalDateTime.now();
            recordSkip(job, executionRecord, endedAt);
            return StockBatchJobRunResponses.alreadyRunning(job, startedAt, endedAt);
        }
        AtomicReference<RuntimeException> lockHeartbeatFailure = new AtomicReference<>();
        ScheduledFuture<?> lockHeartbeatFuture = lockHeartbeat.start(job, lockHeartbeatFailure);
        try {
            int processedCount = job.run();
            requireNonNegativeProcessedCount(job, processedCount);
            LocalDateTime endedAt = LocalDateTime.now();
            RuntimeException heartbeatFailure = lockHeartbeatFailure.get();
            if (heartbeatFailure != null) {
                recordFailure(job, executionRecord, heartbeatFailure, endedAt, "heartbeat failure");
                return StockBatchJobRunResponses.failed(job, heartbeatFailure, startedAt, endedAt);
            }
            RuntimeException completeFailure = recordComplete(job, executionRecord, processedCount, endedAt);
            if (completeFailure != null) {
                return StockBatchJobRunResponses.failed(job, completeFailure, startedAt, endedAt);
            }
            return StockBatchJobRunResponses.completed(job, processedCount, startedAt, endedAt);
        } catch (RuntimeException ex) {
            LocalDateTime endedAt = LocalDateTime.now();
            recordFailure(job, executionRecord, ex, endedAt, "job failure");
            log.warn(
                    "Stock batch job failed: job={}, mode={}, reason={}",
                    job.jobName(),
                    job.executionMode(),
                    ex.getMessage(),
                    ex
            );
            return StockBatchJobRunResponses.failed(job, ex, startedAt, endedAt);
        } finally {
            lockHeartbeatFuture.cancel(false);
            lockHeartbeat.release(job);
        }
    }

    private StockBatchJobRunResponse runWithoutJobLock(
            StockBatchJob job,
            StockBatchJobExecutionRecord executionRecord,
            LocalDateTime startedAt
    ) {
        try {
            int processedCount = job.run();
            requireNonNegativeProcessedCount(job, processedCount);
            LocalDateTime endedAt = LocalDateTime.now();
            RuntimeException completeFailure = recordComplete(job, executionRecord, processedCount, endedAt);
            if (completeFailure != null) {
                return StockBatchJobRunResponses.failed(job, completeFailure, startedAt, endedAt);
            }
            return StockBatchJobRunResponses.completed(job, processedCount, startedAt, endedAt);
        } catch (RuntimeException ex) {
            LocalDateTime endedAt = LocalDateTime.now();
            recordFailure(job, executionRecord, ex, endedAt, "job failure");
            log.warn(
                    "Stock batch job failed: job={}, mode={}, reason={}",
                    job.jobName(),
                    job.executionMode(),
                    ex.getMessage(),
                    ex
            );
            return StockBatchJobRunResponses.failed(job, ex, startedAt, endedAt);
        }
    }

    private RuntimeException recordComplete(
            StockBatchJob job,
            StockBatchJobExecutionRecord executionRecord,
            int processedCount,
            LocalDateTime endedAt
    ) {
        try {
            stockBatchJobRepositoryRecorder.complete(executionRecord, processedCount, endedAt);
            return null;
        } catch (RuntimeException ex) {
            log.warn(
                    "Stock batch job repository complete failed: job={}, mode={}, reason={}",
                    job.jobName(),
                    job.executionMode(),
                    ex.getMessage(),
                    ex
            );
            return ex;
        }
    }

    private void recordSkip(
            StockBatchJob job,
            StockBatchJobExecutionRecord executionRecord,
            LocalDateTime endedAt
    ) {
        try {
            stockBatchJobRepositoryRecorder.skip(executionRecord, endedAt);
        } catch (RuntimeException ex) {
            log.warn(
                    "Stock batch job repository skip failed: job={}, mode={}, reason={}",
                    job.jobName(),
                    job.executionMode(),
                    ex.getMessage(),
                    ex
            );
        }
    }

    private void recordFailure(
            StockBatchJob job,
            StockBatchJobExecutionRecord executionRecord,
            RuntimeException failure,
            LocalDateTime endedAt,
            String failureContext
    ) {
        try {
            stockBatchJobRepositoryRecorder.fail(executionRecord, failure, endedAt);
        } catch (RuntimeException recordFailure) {
            log.warn(
                    "Stock batch job repository fail recording failed: job={}, mode={}, context={}, reason={}",
                    job.jobName(),
                    job.executionMode(),
                    failureContext,
                    recordFailure.getMessage(),
                    recordFailure
            );
        }
    }

    @PreDestroy
    public void shutdown() {
        activeJobTracker.markShuttingDown();
        waitForActiveJobsToComplete();
        shutdownLockHeartbeatExecutor();
    }

    private void waitForActiveJobsToComplete() {
        StockBatchActiveJobTracker.WaitResult waitResult = activeJobTracker.waitForActiveJobsToComplete(
                shutdownAwaitRunningJobsSeconds
        );
        if (waitResult.status() == StockBatchActiveJobTracker.WaitResult.Status.TIMED_OUT) {
            log.warn(
                    "Stock batch shutdown timed out while waiting for running jobs: activeJobCount={}",
                    waitResult.activeJobCount()
            );
        }
        if (waitResult.status() == StockBatchActiveJobTracker.WaitResult.Status.INTERRUPTED) {
            log.warn("Stock batch shutdown interrupted while waiting for running jobs");
        }
    }

    private void shutdownLockHeartbeatExecutor() {
        lockHeartbeatExecutor.shutdown();
        try {
            if (!lockHeartbeatExecutor.awaitTermination(
                    Math.max(1, shutdownAwaitRunningJobsSeconds),
                    TimeUnit.SECONDS
            )) {
                lockHeartbeatExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            lockHeartbeatExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void requireNonNegativeProcessedCount(StockBatchJob job, int processedCount) {
        if (processedCount < 0) {
            throw new IllegalStateException(
                    "Stock batch job returned negative processed count: job=%s, processedCount=%d"
                            .formatted(job.jobName(), processedCount)
            );
        }
    }

    private static ScheduledExecutorService newLockHeartbeatExecutor(String threadName) {
        return Executors.newSingleThreadScheduledExecutor(daemonThreadFactory(threadName));
    }

    private static ThreadFactory daemonThreadFactory(String threadName) {
        return runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        };
    }
}
