package stock.batch.service.batch.common.support;

import java.time.LocalDateTime;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import stock.batch.service.batch.common.policy.BatchJobLockRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StockBatchJobLockHeartbeatTest {

    private static final String JOB_LOCK_OWNER = "job-lock-owner";

    @Test
    void verifyOwnership_transientRenewalFailureRecovered_clearsFailure() {
        BatchJobLockRegistry lockRegistry = mock(BatchJobLockRegistry.class);
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        AtomicReference<Runnable> scheduledRenewal = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            scheduledRenewal.set(invocation.getArgument(0));
            return future;
        }).when(executor).scheduleWithFixedDelay(
                any(Runnable.class),
                eq(30L),
                eq(30L),
                eq(TimeUnit.SECONDS)
        );
        when(lockRegistry.renew(
                eq("corporate-actions"),
                any(LocalDateTime.class),
                eq(JOB_LOCK_OWNER)
        ))
                .thenThrow(new IllegalStateException("temporary database interruption"))
                .thenReturn(true);
        StockBatchJobLockHeartbeat heartbeat = new StockBatchJobLockHeartbeat(
                lockRegistry,
                executor,
                30,
                null
        );
        BatchExecutionDescriptor execution = new BatchExecutionDescriptor(
                "corporate-actions",
                "corporate-cash"
        );
        AtomicReference<RuntimeException> failure = new AtomicReference<>();

        heartbeat.start(execution, JOB_LOCK_OWNER, null, null, null, failure);
        scheduledRenewal.get().run();
        assertThat(failure.get()).isNotNull();

        heartbeat.verifyOwnership(execution, JOB_LOCK_OWNER, null, null, null, failure);

        assertThat(failure.get()).isNull();
    }

    @Test
    void verifyOwnership_lockOwnershipLost_keepsFailure() {
        BatchJobLockRegistry lockRegistry = mock(BatchJobLockRegistry.class);
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        when(lockRegistry.renew(
                eq("portfolio-settlement"),
                any(LocalDateTime.class),
                eq(JOB_LOCK_OWNER)
        )).thenReturn(false);
        StockBatchJobLockHeartbeat heartbeat = new StockBatchJobLockHeartbeat(
                lockRegistry,
                executor,
                30,
                null
        );
        BatchExecutionDescriptor execution = new BatchExecutionDescriptor(
                "portfolio-settlement",
                "portfolio-snapshot"
        );
        AtomicReference<RuntimeException> failure = new AtomicReference<>();

        heartbeat.verifyOwnership(execution, JOB_LOCK_OWNER, null, null, null, failure);
        RuntimeException firstFailure = failure.get();
        heartbeat.verifyOwnership(execution, JOB_LOCK_OWNER, null, null, null, failure);

        assertThat(firstFailure).isNotNull();
        assertThat(failure.get()).isSameAs(firstFailure);
    }
}
