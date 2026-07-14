package stock.batch.service.batch.common.support;

import java.time.LocalDateTime;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import lombok.extern.slf4j.Slf4j;

import stock.batch.service.batch.common.policy.BatchJobLockRegistry;

@Slf4j
final class StockBatchJobLockHeartbeat {

    private final BatchJobLockRegistry batchJobLockRegistry;
    private final ScheduledExecutorService lockHeartbeatExecutor;
    private final long lockHeartbeatIntervalSeconds;

    StockBatchJobLockHeartbeat(
            BatchJobLockRegistry batchJobLockRegistry,
            ScheduledExecutorService lockHeartbeatExecutor,
            long lockHeartbeatIntervalSeconds
    ) {
        this.batchJobLockRegistry = batchJobLockRegistry;
        this.lockHeartbeatExecutor = lockHeartbeatExecutor;
        this.lockHeartbeatIntervalSeconds = lockHeartbeatIntervalSeconds;
    }

    ScheduledFuture<?> start(
            BatchExecutionDescriptor execution,
            AtomicReference<RuntimeException> lockHeartbeatFailure
    ) {
        return lockHeartbeatExecutor.scheduleWithFixedDelay(
                () -> renew(execution, lockHeartbeatFailure),
                lockHeartbeatIntervalSeconds,
                lockHeartbeatIntervalSeconds,
                TimeUnit.SECONDS
        );
    }

    void release(BatchExecutionDescriptor execution) {
        try {
            batchJobLockRegistry.release(execution.jobName());
        } catch (RuntimeException ex) {
            log.warn(
                    "Stock batch job lock release failed: job={}, mode={}, reason={}",
                    execution.jobName(),
                    execution.executionMode(),
                    ex.getMessage(),
                    ex
            );
        }
    }

    private void renew(BatchExecutionDescriptor execution, AtomicReference<RuntimeException> lockHeartbeatFailure) {
        try {
            boolean renewed = batchJobLockRegistry.renew(execution.jobName(), LocalDateTime.now());
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
            }
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
