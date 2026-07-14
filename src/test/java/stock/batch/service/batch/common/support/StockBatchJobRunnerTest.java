package stock.batch.service.batch.common.support;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.step.StepExecution;

import stock.batch.service.batch.common.policy.BatchJobLockRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockBatchJobRunnerTest {

    private final BatchJobLockRegistry batchJobLockRegistry = mock(BatchJobLockRegistry.class);
    private final JobOperator jobOperator = mock(JobOperator.class);
    private final StockBatchStaleExecutionRecovery staleExecutionRecovery =
            mock(StockBatchStaleExecutionRecovery.class);
    private final ScheduledExecutorService lockHeartbeatExecutor = mock(ScheduledExecutorService.class);
    @SuppressWarnings("unchecked")
    private final ScheduledFuture<Object> lockHeartbeatFuture = mock(ScheduledFuture.class);

    @BeforeEach
    void setUp() throws InterruptedException {
        when(batchJobLockRegistry.lockTtlSeconds()).thenReturn(60L);
        doReturn(lockHeartbeatFuture).when(lockHeartbeatExecutor).scheduleWithFixedDelay(
                any(Runnable.class),
                eq(30L),
                eq(30L),
                eq(TimeUnit.SECONDS)
        );
        when(lockHeartbeatExecutor.awaitTermination(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
    }

    @Test
    void run_lightweightTask_doesNotWriteSpringBatchMetadata() throws Exception {
        TestTask task = new TestTask("order-book-execution", "order-book", false, 7, null);

        var response = runner().run(task);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.processedCount()).isEqualTo(7);
        assertThat(task.runCount).isEqualTo(1);
        verify(jobOperator, never()).start(any(Job.class), any(JobParameters.class));
        verify(staleExecutionRecovery, never()).recover(any(), any());
        verify(batchJobLockRegistry, never()).tryAcquire(any(), any());
    }

    @Test
    void run_lockedLightweightTask_acquiresAndReleasesBusinessLock() {
        TestTask task = new TestTask("market-data-refresh", "n/a", true, 3, null);
        when(batchJobLockRegistry.tryAcquire(eq(task.taskName()), any(LocalDateTime.class))).thenReturn(true);

        var response = runner().run(task);

        assertThat(response.status()).isEqualTo("COMPLETED");
        verify(batchJobLockRegistry).release(task.taskName());
        verify(lockHeartbeatExecutor).scheduleWithFixedDelay(
                any(Runnable.class),
                eq(30L),
                eq(30L),
                eq(TimeUnit.SECONDS)
        );
    }

    @Test
    void run_nativeJob_returnsActualStepWriteCount() throws Exception {
        Job job = mock(Job.class);
        JobParameters parameters = new JobParameters();
        JobExecution jobExecution = mock(JobExecution.class);
        StepExecution stepExecution = mock(StepExecution.class);
        when(job.getName()).thenReturn("portfolio-settlement");
        when(batchJobLockRegistry.tryAcquire(eq("portfolio-settlement"), any(LocalDateTime.class))).thenReturn(true);
        when(jobOperator.start(job, parameters)).thenReturn(jobExecution);
        when(jobExecution.getStatus()).thenReturn(BatchStatus.COMPLETED);
        when(jobExecution.getEndTime()).thenReturn(LocalDateTime.now());
        when(jobExecution.getStepExecutions()).thenReturn(List.of(stepExecution));
        when(stepExecution.getWriteCount()).thenReturn(250L);

        var response = runner().run(job, "portfolio-snapshot", parameters);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.processedCount()).isEqualTo(250);
        verify(jobOperator).start(job, parameters);
        verify(staleExecutionRecovery).recover(eq("portfolio-settlement"), any(LocalDateTime.class));
        verify(batchJobLockRegistry).release("portfolio-settlement");
    }

    @Test
    void run_nativeCompletedInstance_returnsSkipped() throws Exception {
        Job job = mock(Job.class);
        JobParameters parameters = new JobParameters();
        when(job.getName()).thenReturn("market-close-rollover");
        when(batchJobLockRegistry.tryAcquire(eq("market-close-rollover"), any(LocalDateTime.class))).thenReturn(true);
        when(jobOperator.start(job, parameters))
                .thenThrow(new JobInstanceAlreadyCompleteException("already complete"));

        var response = runner().run(job, "price-limit-base:full", parameters);

        assertThat(response.status()).isEqualTo("SKIPPED");
        assertThat(response.message()).isEqualTo("Job instance is already complete");
    }

    @Test
    void run_nativeJobHeldByAnotherInstance_skipsBeforeMetadataCreation() throws Exception {
        Job job = mock(Job.class);
        JobParameters parameters = new JobParameters();
        when(job.getName()).thenReturn("corporate-actions");
        when(batchJobLockRegistry.tryAcquire(eq("corporate-actions"), any(LocalDateTime.class))).thenReturn(false);

        var response = runner().run(job, "order-book", parameters);

        assertThat(response.status()).isEqualTo("SKIPPED");
        assertThat(response.message()).isEqualTo("Job is already running");
        verify(jobOperator, never()).start(any(Job.class), any(JobParameters.class));
        verify(staleExecutionRecovery, never()).recover(any(), any());
    }

    @Test
    void run_nativeFailedExecution_returnsFailureDescription() throws Exception {
        Job job = mock(Job.class);
        JobParameters parameters = new JobParameters();
        JobExecution jobExecution = mock(JobExecution.class);
        when(job.getName()).thenReturn("corporate-actions");
        when(batchJobLockRegistry.tryAcquire(eq("corporate-actions"), any(LocalDateTime.class))).thenReturn(true);
        when(jobOperator.start(job, parameters)).thenReturn(jobExecution);
        when(jobExecution.getStatus()).thenReturn(BatchStatus.FAILED);
        when(jobExecution.getEndTime()).thenReturn(LocalDateTime.now());
        when(jobExecution.getStepExecutions()).thenReturn(List.of());
        when(jobExecution.getAllFailureExceptions()).thenReturn(List.of());
        when(jobExecution.getExitStatus()).thenReturn(ExitStatus.FAILED.addExitDescription("stage failed"));

        var response = runner().run(job, "order-book", parameters);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.message()).contains("stage failed");
    }

    @Test
    void run_negativeLightweightCount_returnsFailed() {
        TestTask task = new TestTask("invalid", "test", false, -1, null);

        var response = runner().run(task);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.message()).contains("negative processed count");
    }

    @Test
    void shutdown_rejectsNewExecution() {
        StockBatchJobRunner runner = runner();
        runner.shutdown();

        var response = runner.run(new TestTask("after-shutdown", "test", false, 1, null));

        assertThat(response.status()).isEqualTo("SKIPPED");
        assertThat(response.message()).isEqualTo("Batch service is shutting down");
    }

    @Test
    void constructor_heartbeatAtOrBeyondTtl_rejectsConfiguration() {
        when(batchJobLockRegistry.lockTtlSeconds()).thenReturn(30L);

        assertThatThrownBy(() -> new StockBatchJobRunner(
                batchJobLockRegistry,
                jobOperator,
                staleExecutionRecovery,
                30,
                60,
                lockHeartbeatExecutor
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private StockBatchJobRunner runner() {
        return new StockBatchJobRunner(
                batchJobLockRegistry,
                jobOperator,
                staleExecutionRecovery,
                30,
                60,
                lockHeartbeatExecutor
        );
    }

    private static final class TestTask implements LightweightBatchTask {

        private final String taskName;
        private final String executionMode;
        private final boolean requiresJobLock;
        private final int processedCount;
        private final RuntimeException failure;
        private int runCount;

        private TestTask(
                String taskName,
                String executionMode,
                boolean requiresJobLock,
                int processedCount,
                RuntimeException failure
        ) {
            this.taskName = taskName;
            this.executionMode = executionMode;
            this.requiresJobLock = requiresJobLock;
            this.processedCount = processedCount;
            this.failure = failure;
        }

        @Override
        public String taskName() {
            return taskName;
        }

        @Override
        public String executionMode() {
            return executionMode;
        }

        @Override
        public boolean requiresJobLock() {
            return requiresJobLock;
        }

        @Override
        public int run() {
            runCount++;
            if (failure != null) {
                throw failure;
            }
            return processedCount;
        }
    }
}
