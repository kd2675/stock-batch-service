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
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
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
    private final JobOperator jobOperator;
    private final StockBatchStaleExecutionRecovery staleExecutionRecovery;
    private final long shutdownAwaitRunningJobsSeconds;
    private final ScheduledExecutorService lockHeartbeatExecutor;
    private final StockBatchJobLockHeartbeat lockHeartbeat;
    private final StockBatchActiveJobTracker activeJobTracker = new StockBatchActiveJobTracker();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean shutdownStarted = new AtomicBoolean(false);

    @Autowired
    public StockBatchJobRunner(
            BatchJobLockRegistry batchJobLockRegistry,
            JobOperator jobOperator,
            StockBatchStaleExecutionRecovery staleExecutionRecovery,
            @Value("${stock.batch.job-lock.heartbeat-interval-seconds:30}") long lockHeartbeatIntervalSeconds,
            @Value("${stock.batch.shutdown.await-running-jobs-seconds:120}") long shutdownAwaitRunningJobsSeconds
    ) {
        this(
                batchJobLockRegistry,
                jobOperator,
                staleExecutionRecovery,
                lockHeartbeatIntervalSeconds,
                shutdownAwaitRunningJobsSeconds,
                newLockHeartbeatExecutor("stock-batch-lock-heartbeat")
        );
    }

    StockBatchJobRunner(
            BatchJobLockRegistry batchJobLockRegistry,
            JobOperator jobOperator,
            StockBatchStaleExecutionRecovery staleExecutionRecovery,
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
        this.jobOperator = jobOperator;
        this.staleExecutionRecovery = staleExecutionRecovery;
        this.shutdownAwaitRunningJobsSeconds = shutdownAwaitRunningJobsSeconds;
        this.lockHeartbeatExecutor = lockHeartbeatExecutor;
        this.lockHeartbeat = new StockBatchJobLockHeartbeat(
                batchJobLockRegistry,
                lockHeartbeatExecutor,
                lockHeartbeatIntervalSeconds
        );
    }

    public StockBatchJobRunResponse run(LightweightBatchTask task) {
        BatchExecutionDescriptor execution = new BatchExecutionDescriptor(task.taskName(), task.executionMode());
        return enter(execution, () -> runLightweightEntered(task, execution));
    }

    public StockBatchJobRunResponse run(Job job, String executionMode, JobParameters parameters) {
        BatchExecutionDescriptor execution = new BatchExecutionDescriptor(job.getName(), executionMode);
        return enter(execution, () -> runNativeEntered(job, parameters, execution));
    }

    public boolean hasActiveJobs() {
        return activeJobTracker.hasActiveJobs();
    }

    private StockBatchJobRunResponse enter(
            BatchExecutionDescriptor execution,
            ExecutionSupplier executionSupplier
    ) {
        if (!activeJobTracker.tryEnter()) {
            LocalDateTime now = LocalDateTime.now();
            log.info(
                    "Stock batch execution skipped because service is shutting down: job={}, mode={}",
                    execution.jobName(),
                    execution.executionMode()
            );
            return StockBatchJobRunResponses.shuttingDown(execution, now);
        }
        try {
            return executionSupplier.run();
        } finally {
            activeJobTracker.leave();
        }
    }

    private StockBatchJobRunResponse runLightweightEntered(
            LightweightBatchTask task,
            BatchExecutionDescriptor execution
    ) {
        LocalDateTime startedAt = LocalDateTime.now();
        if (!task.requiresJobLock()) {
            return executeLightweight(task, execution, startedAt);
        }
        return executeWithJobLock(execution, startedAt, () -> executeLightweight(task, execution, startedAt));
    }

    private StockBatchJobRunResponse runNativeEntered(
            Job job,
            JobParameters parameters,
            BatchExecutionDescriptor execution
    ) {
        LocalDateTime startedAt = LocalDateTime.now();
        return executeWithJobLock(execution, startedAt, () -> executeNative(job, parameters, execution, startedAt));
    }

    private StockBatchJobRunResponse executeWithJobLock(
            BatchExecutionDescriptor execution,
            LocalDateTime startedAt,
            ExecutionSupplier action
    ) {
        boolean lockAcquired;
        try {
            lockAcquired = batchJobLockRegistry.tryAcquire(execution.jobName(), startedAt);
        } catch (RuntimeException ex) {
            LocalDateTime endedAt = LocalDateTime.now();
            log.warn(
                    "Stock batch job lock acquisition failed: job={}, mode={}, reason={}",
                    execution.jobName(),
                    execution.executionMode(),
                    ex.getMessage(),
                    ex
            );
            return StockBatchJobRunResponses.failed(execution, ex, startedAt, endedAt);
        }
        if (!lockAcquired) {
            LocalDateTime endedAt = LocalDateTime.now();
            return StockBatchJobRunResponses.alreadyRunning(execution, startedAt, endedAt);
        }

        AtomicReference<RuntimeException> heartbeatFailure = new AtomicReference<>();
        ScheduledFuture<?> heartbeatFuture = lockHeartbeat.start(execution, heartbeatFailure);
        try {
            StockBatchJobRunResponse response = action.run();
            RuntimeException failure = heartbeatFailure.get();
            if (failure == null) {
                return response;
            }
            LocalDateTime endedAt = LocalDateTime.now();
            log.warn(
                    "Stock batch job lock heartbeat failed during execution: job={}, mode={}, reason={}",
                    execution.jobName(),
                    execution.executionMode(),
                    failure.getMessage(),
                    failure
            );
            return StockBatchJobRunResponses.failed(execution, failure, startedAt, endedAt);
        } finally {
            heartbeatFuture.cancel(false);
            lockHeartbeat.release(execution);
        }
    }

    private StockBatchJobRunResponse executeLightweight(
            LightweightBatchTask task,
            BatchExecutionDescriptor execution,
            LocalDateTime startedAt
    ) {
        try {
            logStarted(execution, startedAt);
            int processedCount = task.run();
            requireNonNegativeProcessedCount(execution, processedCount);
            LocalDateTime endedAt = LocalDateTime.now();
            logCompleted(execution, processedCount, startedAt, endedAt);
            return StockBatchJobRunResponses.completed(execution, processedCount, startedAt, endedAt);
        } catch (RuntimeException ex) {
            LocalDateTime endedAt = LocalDateTime.now();
            logFailure(execution, startedAt, endedAt, ex);
            return StockBatchJobRunResponses.failed(execution, ex, startedAt, endedAt);
        }
    }

    private StockBatchJobRunResponse executeNative(
            Job job,
            JobParameters parameters,
            BatchExecutionDescriptor execution,
            LocalDateTime startedAt
    ) {
        try {
            logStarted(execution, startedAt);
            int recoveredCount = staleExecutionRecovery.recover(execution.jobName(), startedAt);
            if (recoveredCount > 0) {
                log.warn(
                        "Recovered stale Spring Batch executions before restart: job={}, count={}",
                        execution.jobName(),
                        recoveredCount
                );
            }
            JobExecution jobExecution = jobOperator.start(job, parameters);
            LocalDateTime endedAt = jobExecution.getEndTime() == null ? LocalDateTime.now() : jobExecution.getEndTime();
            int processedCount = Math.toIntExact(jobExecution.getStepExecutions().stream()
                    .mapToLong(stepExecution -> stepExecution.getWriteCount())
                    .sum());
            if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
                logCompleted(execution, processedCount, startedAt, endedAt);
                return StockBatchJobRunResponses.completed(execution, processedCount, startedAt, endedAt);
            }
            Throwable failure = jobExecution.getAllFailureExceptions().stream()
                    .findFirst()
                    .orElseGet(() -> new IllegalStateException(jobExecution.getExitStatus().getExitDescription()));
            logFailure(execution, startedAt, endedAt, failure);
            return StockBatchJobRunResponses.failed(execution, failure, startedAt, endedAt);
        } catch (JobInstanceAlreadyCompleteException ex) {
            LocalDateTime endedAt = LocalDateTime.now();
            return StockBatchJobRunResponses.alreadyComplete(execution, startedAt, endedAt);
        } catch (JobExecutionAlreadyRunningException ex) {
            LocalDateTime endedAt = LocalDateTime.now();
            return StockBatchJobRunResponses.alreadyRunning(execution, startedAt, endedAt);
        } catch (Exception ex) {
            LocalDateTime endedAt = LocalDateTime.now();
            logFailure(execution, startedAt, endedAt, ex);
            return StockBatchJobRunResponses.failed(execution, ex, startedAt, endedAt);
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

    private void logStarted(BatchExecutionDescriptor execution, LocalDateTime startedAt) {
        log.info(
                "Stock batch execution started: job={}, mode={}, startedAt={}",
                execution.jobName(),
                execution.executionMode(),
                startedAt
        );
    }

    private void logCompleted(
            BatchExecutionDescriptor execution,
            int processedCount,
            LocalDateTime startedAt,
            LocalDateTime endedAt
    ) {
        log.info(
                "Stock batch execution completed: job={}, mode={}, processedCount={}, elapsedMs={}",
                execution.jobName(),
                execution.executionMode(),
                processedCount,
                elapsedMillis(startedAt, endedAt)
        );
    }

    private void logFailure(
            BatchExecutionDescriptor execution,
            LocalDateTime startedAt,
            LocalDateTime endedAt,
            Throwable failure
    ) {
        log.warn(
                "Stock batch execution failed: job={}, mode={}, elapsedMs={}, reason={}",
                execution.jobName(),
                execution.executionMode(),
                elapsedMillis(startedAt, endedAt),
                failure.getMessage(),
                failure
        );
    }

    private long elapsedMillis(LocalDateTime startedAt, LocalDateTime endedAt) {
        return Math.max(0, Duration.between(startedAt, endedAt).toMillis());
    }

    private void requireNonNegativeProcessedCount(BatchExecutionDescriptor execution, int processedCount) {
        if (processedCount < 0) {
            throw new IllegalStateException(
                    "Stock batch task returned negative processed count: job=%s, processedCount=%d"
                            .formatted(execution.jobName(), processedCount)
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

    @FunctionalInterface
    private interface ExecutionSupplier {

        StockBatchJobRunResponse run();
    }
}
