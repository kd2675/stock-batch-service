package stock.batch.service.batch.common.support;

import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.common.policy.BatchJobLockRegistry;
import stock.batch.service.common.vo.StockBatchJobRunResponse;

@Component
@Slf4j
public class StockBatchJobRunner {

    private static final String COMPLETED = "COMPLETED";
    private static final String SKIPPED = "SKIPPED";
    private static final String FAILED = "FAILED";
    private static final String SHUTTING_DOWN_MESSAGE = "Batch service is shutting down";

    private final BatchJobLockRegistry batchJobLockRegistry;
    private final StockBatchJobRepositoryRecorder stockBatchJobRepositoryRecorder;
    private final long lockHeartbeatIntervalSeconds;
    private final long shutdownAwaitRunningJobsSeconds;
    private final ScheduledExecutorService lockHeartbeatExecutor;
    private final Object activeJobsMonitor = new Object();

    private boolean shuttingDown;
    private int activeJobCount;

    @Autowired
    public StockBatchJobRunner(
            BatchJobLockRegistry batchJobLockRegistry,
            StockBatchJobRepositoryRecorder stockBatchJobRepositoryRecorder,
            @Value("${stock.batch.job-lock.heartbeat-interval-seconds:30}") long lockHeartbeatIntervalSeconds,
            @Value("${stock.batch.shutdown.await-running-jobs-seconds:60}") long shutdownAwaitRunningJobsSeconds
    ) {
        this(
                batchJobLockRegistry,
                stockBatchJobRepositoryRecorder,
                lockHeartbeatIntervalSeconds,
                shutdownAwaitRunningJobsSeconds,
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "stock-batch-lock-heartbeat");
                    thread.setDaemon(true);
                    return thread;
                })
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
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "stock-batch-lock-heartbeat-test");
                    thread.setDaemon(true);
                    return thread;
                })
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
        this.lockHeartbeatIntervalSeconds = lockHeartbeatIntervalSeconds;
        this.shutdownAwaitRunningJobsSeconds = shutdownAwaitRunningJobsSeconds;
        this.lockHeartbeatExecutor = lockHeartbeatExecutor;
    }

    public StockBatchJobRunResponse run(StockBatchJob job) {
        if (!tryEnterActiveJob()) {
            LocalDateTime now = LocalDateTime.now();
            return new StockBatchJobRunResponse(
                    job.jobName(),
                    SKIPPED,
                    job.executionMode(),
                    0,
                    SHUTTING_DOWN_MESSAGE,
                    now,
                    now
            );
        }
        try {
            return runEnteredJob(job);
        } finally {
            leaveActiveJob();
        }
    }

    private StockBatchJobRunResponse runEnteredJob(StockBatchJob job) {
        LocalDateTime startedAt = LocalDateTime.now();
        StockBatchJobExecutionRecord executionRecord = stockBatchJobRepositoryRecorder.start(job, startedAt);
        boolean lockAcquired;
        try {
            lockAcquired = batchJobLockRegistry.tryAcquire(job.jobName(), startedAt);
        } catch (RuntimeException ex) {
            LocalDateTime endedAt = LocalDateTime.now();
            stockBatchJobRepositoryRecorder.fail(executionRecord, ex, endedAt);
            log.warn(
                    "Stock batch job lock acquisition failed: job={}, mode={}, reason={}",
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
        }
        if (!lockAcquired) {
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
        AtomicReference<RuntimeException> lockHeartbeatFailure = new AtomicReference<>();
        ScheduledFuture<?> lockHeartbeat = startLockHeartbeat(job, lockHeartbeatFailure);
        try {
            int processedCount = job.run();
            requireNonNegativeProcessedCount(job, processedCount);
            LocalDateTime endedAt = LocalDateTime.now();
            RuntimeException heartbeatFailure = lockHeartbeatFailure.get();
            if (heartbeatFailure != null) {
                stockBatchJobRepositoryRecorder.fail(executionRecord, heartbeatFailure, endedAt);
                return new StockBatchJobRunResponse(
                        job.jobName(),
                        FAILED,
                        job.executionMode(),
                        0,
                        heartbeatFailure.getMessage(),
                        startedAt,
                        endedAt
                );
            }
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
            lockHeartbeat.cancel(false);
            releaseLock(job);
        }
    }

    @PreDestroy
    public void shutdown() {
        markShuttingDown();
        waitForActiveJobsToComplete();
        shutdownLockHeartbeatExecutor();
    }

    private boolean tryEnterActiveJob() {
        synchronized (activeJobsMonitor) {
            if (shuttingDown) {
                return false;
            }
            activeJobCount++;
            return true;
        }
    }

    private void leaveActiveJob() {
        synchronized (activeJobsMonitor) {
            activeJobCount--;
            if (activeJobCount == 0) {
                activeJobsMonitor.notifyAll();
            }
        }
    }

    private void markShuttingDown() {
        synchronized (activeJobsMonitor) {
            shuttingDown = true;
        }
    }

    private void waitForActiveJobsToComplete() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(shutdownAwaitRunningJobsSeconds);
        synchronized (activeJobsMonitor) {
            while (activeJobCount > 0) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    log.warn("Stock batch shutdown timed out while waiting for running jobs: activeJobCount={}", activeJobCount);
                    return;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(activeJobsMonitor, remainingNanos);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    log.warn("Stock batch shutdown interrupted while waiting for running jobs");
                    return;
                }
            }
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

    private ScheduledFuture<?> startLockHeartbeat(
            StockBatchJob job,
            AtomicReference<RuntimeException> lockHeartbeatFailure
    ) {
        return lockHeartbeatExecutor.scheduleWithFixedDelay(
                () -> renewLock(job, lockHeartbeatFailure),
                lockHeartbeatIntervalSeconds,
                lockHeartbeatIntervalSeconds,
                TimeUnit.SECONDS
        );
    }

    private void renewLock(StockBatchJob job, AtomicReference<RuntimeException> lockHeartbeatFailure) {
        try {
            boolean renewed = batchJobLockRegistry.renew(job.jobName(), LocalDateTime.now());
            if (!renewed) {
                lockHeartbeatFailure.compareAndSet(
                        null,
                        new IllegalStateException("Job lock heartbeat lost ownership")
                );
                log.warn(
                        "Stock batch job lock heartbeat lost ownership: job={}, mode={}",
                        job.jobName(),
                        job.executionMode()
                );
            }
        } catch (RuntimeException ex) {
            log.warn(
                    "Stock batch job lock heartbeat failed: job={}, mode={}, reason={}",
                    job.jobName(),
                    job.executionMode(),
                    ex.getMessage(),
                    ex
            );
            lockHeartbeatFailure.compareAndSet(null, ex);
        }
    }

    private void releaseLock(StockBatchJob job) {
        try {
            batchJobLockRegistry.release(job.jobName());
        } catch (RuntimeException ex) {
            log.warn(
                    "Stock batch job lock release failed: job={}, mode={}, reason={}",
                    job.jobName(),
                    job.executionMode(),
                    ex.getMessage(),
                    ex
            );
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
}
