package stock.batch.service.batch.common.support;

import java.time.LocalDateTime;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import lombok.extern.slf4j.Slf4j;

import stock.batch.service.batch.common.policy.BatchJobLockRegistry;
import stock.batch.service.marketclose.biz.PostCloseCycleService;

@Slf4j
final class StockBatchJobLockHeartbeat {

    private final BatchJobLockRegistry batchJobLockRegistry;
    private final ScheduledExecutorService lockHeartbeatExecutor;
    private final long lockHeartbeatIntervalSeconds;
    private final PostCloseCycleService postCloseCycleService;

    StockBatchJobLockHeartbeat(
            BatchJobLockRegistry batchJobLockRegistry,
            ScheduledExecutorService lockHeartbeatExecutor,
            long lockHeartbeatIntervalSeconds,
            PostCloseCycleService postCloseCycleService
    ) {
        this.batchJobLockRegistry = batchJobLockRegistry;
        this.lockHeartbeatExecutor = lockHeartbeatExecutor;
        this.lockHeartbeatIntervalSeconds = lockHeartbeatIntervalSeconds;
        this.postCloseCycleService = postCloseCycleService;
    }

    ScheduledFuture<?> start(
            BatchExecutionDescriptor execution,
            String jobLockOwner,
            Long closeCycleId,
            String admissionLockName,
            String admissionLockOwner,
            AtomicReference<RuntimeException> lockHeartbeatFailure
    ) {
        return lockHeartbeatExecutor.scheduleWithFixedDelay(
                () -> renew(
                        execution,
                        jobLockOwner,
                        closeCycleId,
                        admissionLockName,
                        admissionLockOwner,
                        lockHeartbeatFailure
                ),
                lockHeartbeatIntervalSeconds,
                lockHeartbeatIntervalSeconds,
                TimeUnit.SECONDS
        );
    }

    /**
     * Performs one serialized final renewal before the runner publishes the job result.
     * A periodic renewal that is already in flight completes first, so lock release cannot race
     * with a late heartbeat. A fully successful renewal clears an earlier transient DB failure;
     * a real ownership loss keeps failing because the owner-qualified update cannot recover it.
     */
    void verifyOwnership(
            BatchExecutionDescriptor execution,
            String jobLockOwner,
            Long closeCycleId,
            String admissionLockName,
            String admissionLockOwner,
            AtomicReference<RuntimeException> lockHeartbeatFailure
    ) {
        renew(
                execution,
                jobLockOwner,
                closeCycleId,
                admissionLockName,
                admissionLockOwner,
                lockHeartbeatFailure
        );
    }

    void release(
            BatchExecutionDescriptor execution,
            String jobLockOwner,
            String admissionLockName,
            String admissionLockOwner
    ) {
        try {
            batchJobLockRegistry.release(execution.jobName(), jobLockOwner);
        } catch (RuntimeException ex) {
            log.warn(
                    "Stock batch job lock release failed: job={}, mode={}, reason={}",
                    execution.jobName(),
                    execution.executionMode(),
                    ex.getMessage(),
                    ex
            );
        }
        if (admissionLockName == null) {
            return;
        }
        try {
            batchJobLockRegistry.release(admissionLockName, admissionLockOwner);
        } catch (RuntimeException ex) {
            log.warn(
                    "Stock batch heavy admission lock release failed: job={}, mode={}, reason={}",
                    execution.jobName(),
                    execution.executionMode(),
                    ex.getMessage(),
                    ex
            );
        }
    }

    private synchronized void renew(
            BatchExecutionDescriptor execution,
            String jobLockOwner,
            Long closeCycleId,
            String admissionLockName,
            String admissionLockOwner,
            AtomicReference<RuntimeException> lockHeartbeatFailure
    ) {
        try {
            LocalDateTime now = LocalDateTime.now();
            boolean renewed = batchJobLockRegistry.renew(execution.jobName(), now, jobLockOwner);
            if (!renewed) {
                lockHeartbeatFailure.compareAndSet(
                        null,
                        new IllegalStateException("Job lock heartbeat lost ownership")
                );
                log.warn(
                        "Stock batch job lock heartbeat lost ownership: job={}, mode={}",
                        execution.jobName(),
                        execution.executionMode()
                );
                return;
            }
            if (admissionLockName != null
                    && !batchJobLockRegistry.renew(admissionLockName, now, admissionLockOwner)) {
                lockHeartbeatFailure.compareAndSet(
                        null,
                        new IllegalStateException("Heavy admission lock heartbeat lost ownership")
                );
                log.warn(
                        "Stock batch heavy admission lock heartbeat lost ownership: job={}, mode={}",
                        execution.jobName(),
                        execution.executionMode()
                );
                return;
            }
            if (closeCycleId != null && postCloseCycleService != null) {
                postCloseCycleService.renewOwnedRunningLease(closeCycleId, now);
            }
            lockHeartbeatFailure.set(null);
        } catch (RuntimeException ex) {
            log.warn(
                    "Stock batch job lock heartbeat failed: job={}, mode={}, reason={}",
                    execution.jobName(),
                    execution.executionMode(),
                    ex.getMessage(),
                    ex
            );
            lockHeartbeatFailure.compareAndSet(null, ex);
        }
    }
}
