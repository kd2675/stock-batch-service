package stock.batch.service.batch.common.support;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.common.policy.BatchJobLockRegistry;
import stock.batch.service.common.vo.StockBatchJobRunResponse;

@Component
@DependsOn("simulationClockScheduler")
@Slf4j
public class StockBatchJobRunner implements SmartLifecycle {

    private static final int LIFECYCLE_PHASE = Integer.MAX_VALUE - 100;

    private final BatchJobLockRegistry batchJobLockRegistry;
    private final StockBatchJobRepositoryRecorder stockBatchJobRepositoryRecorder;
    private final long shutdownAwaitRunningJobsSeconds;
    private final ScheduledExecutorService lockHeartbeatExecutor;
    private final StockBatchJobLockHeartbeat lockHeartbeat;
    private final StockBatchActiveJobTracker activeJobTracker = new StockBatchActiveJobTracker();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean shutdownStarted = new AtomicBoolean(false);

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
            log.info(
                    "Stock batch job skipped because service is shutting down: job={}, mode={}",
                    job.jobName(),
                    job.executionMode()
            );
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
        if (!job.recordsExecutionHistory()) {
            return runLightweightJob(job, startedAt);
        }
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
            log.warn(
                    "Stock batch job skipped because lock is held: job={}, mode={}, elapsedMs={}",
                    job.jobName(),
                    job.executionMode(),
                    elapsedMillis(startedAt, endedAt)
            );
            return StockBatchJobRunResponses.alreadyRunning(job, startedAt, endedAt);
        }
        AtomicReference<RuntimeException> lockHeartbeatFailure = new AtomicReference<>();
        ScheduledFuture<?> lockHeartbeatFuture = lockHeartbeat.start(job, lockHeartbeatFailure);
        try {
            logJobStarted(job, startedAt);
            int processedCount = job.run();
            requireNonNegativeProcessedCount(job, processedCount);
            LocalDateTime endedAt = LocalDateTime.now();
            RuntimeException heartbeatFailure = lockHeartbeatFailure.get();
            if (heartbeatFailure != null) {
                recordFailure(job, executionRecord, heartbeatFailure, endedAt, "heartbeat failure");
                log.warn(
                        "Stock batch job heartbeat failed: job={}, mode={}, elapsedMs={}, reason={}",
                        job.jobName(),
                        job.executionMode(),
                        elapsedMillis(startedAt, endedAt),
                        heartbeatFailure.getMessage(),
                        heartbeatFailure
                );
                return StockBatchJobRunResponses.failed(job, heartbeatFailure, startedAt, endedAt);
            }
            RuntimeException completeFailure = recordComplete(job, executionRecord, processedCount, endedAt);
            if (completeFailure != null) {
                return StockBatchJobRunResponses.failed(job, completeFailure, startedAt, endedAt);
            }
            logJobCompleted(job, processedCount, startedAt, endedAt);
            return StockBatchJobRunResponses.completed(job, processedCount, startedAt, endedAt);
        } catch (RuntimeException ex) {
            LocalDateTime endedAt = LocalDateTime.now();
            recordFailure(job, executionRecord, ex, endedAt, "job failure");
            log.warn(
                    "Stock batch job failed: job={}, mode={}, elapsedMs={}, reason={}",
                    job.jobName(),
                    job.executionMode(),
                    elapsedMillis(startedAt, endedAt),
                    ex.getMessage(),
                    ex
            );
            return StockBatchJobRunResponses.failed(job, ex, startedAt, endedAt);
        } finally {
            lockHeartbeatFuture.cancel(false);
            lockHeartbeat.release(job);
        }
    }

    private StockBatchJobRunResponse runLightweightJob(StockBatchJob job, LocalDateTime startedAt) {
        if (job.requiresJobLock()) {
            IllegalStateException ex = new IllegalStateException(
                    "Lightweight stock batch job must provide its own concurrency control: " + job.jobName()
            );
            LocalDateTime endedAt = LocalDateTime.now();
            log.warn(
                    "Lightweight stock batch job configuration is invalid: job={}, mode={}, reason={}",
                    job.jobName(),
                    job.executionMode(),
                    ex.getMessage()
            );
            return StockBatchJobRunResponses.failed(job, ex, startedAt, endedAt);
        }
        try {
            logJobStarted(job, startedAt);
            int processedCount = job.run();
            requireNonNegativeProcessedCount(job, processedCount);
            LocalDateTime endedAt = LocalDateTime.now();
            logJobCompleted(job, processedCount, startedAt, endedAt);
            return StockBatchJobRunResponses.completed(job, processedCount, startedAt, endedAt);
        } catch (RuntimeException ex) {
            LocalDateTime endedAt = LocalDateTime.now();
            log.warn(
                    "Lightweight stock batch job failed: job={}, mode={}, elapsedMs={}, reason={}",
                    job.jobName(),
                    job.executionMode(),
                    elapsedMillis(startedAt, endedAt),
                    ex.getMessage(),
                    ex
            );
            return StockBatchJobRunResponses.failed(job, ex, startedAt, endedAt);
        }
    }

    private StockBatchJobRunResponse runWithoutJobLock(
            StockBatchJob job,
            StockBatchJobExecutionRecord executionRecord,
            LocalDateTime startedAt
    ) {
        try {
            logJobStarted(job, startedAt);
            int processedCount = job.run();
            requireNonNegativeProcessedCount(job, processedCount);
            LocalDateTime endedAt = LocalDateTime.now();
            RuntimeException completeFailure = recordComplete(job, executionRecord, processedCount, endedAt);
            if (completeFailure != null) {
                return StockBatchJobRunResponses.failed(job, completeFailure, startedAt, endedAt);
            }
            logJobCompleted(job, processedCount, startedAt, endedAt);
            return StockBatchJobRunResponses.completed(job, processedCount, startedAt, endedAt);
        } catch (RuntimeException ex) {
            LocalDateTime endedAt = LocalDateTime.now();
            recordFailure(job, executionRecord, ex, endedAt, "job failure");
            log.warn(
                    "Stock batch job failed: job={}, mode={}, elapsedMs={}, reason={}",
                    job.jobName(),
                    job.executionMode(),
                    elapsedMillis(startedAt, endedAt),
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
        shutdownOnce();
    }

    @EventListener(ContextClosedEvent.class)
    public void prepareShutdown() {
        activeJobTracker.markShuttingDown();
    }

    @Override
    public void start() {
        running.set(true);
    }

    @Override
    public void stop() {
        shutdownOnce();
    }

    @Override
    public void stop(Runnable callback) {
        try {
            shutdownOnce();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return LIFECYCLE_PHASE;
    }

    private void shutdownOnce() {
        if (!shutdownStarted.compareAndSet(false, true)) {
            return;
        }
        running.set(false);
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

    private void logJobStarted(StockBatchJob job, LocalDateTime startedAt) {
        log.info(
                "Stock batch job started: job={}, mode={}, startedAt={}",
                job.jobName(),
                job.executionMode(),
                startedAt
        );
    }

    private void logJobCompleted(
            StockBatchJob job,
            int processedCount,
            LocalDateTime startedAt,
            LocalDateTime endedAt
    ) {
        log.info(
                "Stock batch job completed: job={}, mode={}, processedCount={}, elapsedMs={}",
                job.jobName(),
                job.executionMode(),
                processedCount,
                elapsedMillis(startedAt, endedAt)
        );
    }

    private long elapsedMillis(LocalDateTime startedAt, LocalDateTime endedAt) {
        return Math.max(0, Duration.between(startedAt, endedAt).toMillis());
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
