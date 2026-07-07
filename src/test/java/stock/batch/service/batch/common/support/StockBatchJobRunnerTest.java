package stock.batch.service.batch.common.support;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import stock.batch.service.batch.common.policy.BatchJobLockRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockBatchJobRunnerTest {

    private final BatchJobLockRegistry batchJobLockRegistry = mock(BatchJobLockRegistry.class);
    private final StockBatchJobRepositoryRecorder stockBatchJobRepositoryRecorder = mock(StockBatchJobRepositoryRecorder.class);
    private final StockBatchJobExecutionRecord executionRecord = mock(StockBatchJobExecutionRecord.class);
    private final ScheduledExecutorService lockHeartbeatExecutor = mock(ScheduledExecutorService.class);
    @SuppressWarnings("unchecked")
    private final ScheduledFuture<Object> lockHeartbeatFuture = mock(ScheduledFuture.class);

    @BeforeEach
    void setUp() {
        when(stockBatchJobRepositoryRecorder.start(any(), any())).thenReturn(executionRecord);
        when(batchJobLockRegistry.lockTtlSeconds()).thenReturn(60L);
        doReturn(lockHeartbeatFuture).when(lockHeartbeatExecutor).scheduleWithFixedDelay(
                any(Runnable.class),
                eq(30L),
                eq(30L),
                eq(TimeUnit.SECONDS)
        );
        try {
            when(lockHeartbeatExecutor.awaitTermination(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
        } catch (InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void run_repositoryStartThrows_returnsFailedResponseWithoutAcquiringLock() {
        TestStockBatchJob job = new TestStockBatchJob("repository-start-fail-job", "test-mode", 7);
        RuntimeException repositoryFailure = new IllegalStateException("batch metadata connection timeout");
        when(stockBatchJobRepositoryRecorder.start(eq(job), any(LocalDateTime.class)))
                .thenThrow(repositoryFailure);

        var response = runner().run(job);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.processedCount()).isZero();
        assertThat(response.message()).isEqualTo("batch metadata connection timeout");
        assertThat(job.runCount()).isZero();
        verify(batchJobLockRegistry, never()).tryAcquire(any(), any());
        verify(stockBatchJobRepositoryRecorder, never()).fail(any(), any(RuntimeException.class), any());
        verify(stockBatchJobRepositoryRecorder, never()).complete(any(), anyInt(), any());
        verify(stockBatchJobRepositoryRecorder, never()).skip(any(), any());
        verify(lockHeartbeatExecutor, never()).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any());
    }

    @Test
    void run_lockAcquireThrows_recordsFailedExecutionAndDoesNotRunJob() {
        TestStockBatchJob job = new TestStockBatchJob("lock-fail-job", "test-mode", 7);
        RuntimeException lockFailure = new IllegalStateException("lock table unavailable");
        when(batchJobLockRegistry.tryAcquire(eq("lock-fail-job"), any(LocalDateTime.class)))
                .thenThrow(lockFailure);

        var response = runner().run(job);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.processedCount()).isZero();
        assertThat(response.message()).isEqualTo("lock table unavailable");
        assertThat(job.runCount()).isZero();
        verify(stockBatchJobRepositoryRecorder).fail(eq(executionRecord), eq(lockFailure), any(LocalDateTime.class));
        verify(stockBatchJobRepositoryRecorder, never()).complete(any(), anyInt(), any());
        verify(stockBatchJobRepositoryRecorder, never()).skip(any(), any());
        verify(batchJobLockRegistry, never()).release("lock-fail-job");
        verify(lockHeartbeatExecutor, never()).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any());
    }

    @Test
    void run_jobWithoutJobLock_runsWithoutBatchJobLockOrHeartbeat() {
        TestStockBatchJob job = new TestStockBatchJob("unlocked-job", "test-mode", 7, () -> {
        }, false);

        var response = runner().run(job);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.processedCount()).isEqualTo(7);
        assertThat(job.runCount()).isEqualTo(1);
        verify(batchJobLockRegistry, never()).tryAcquire(any(), any());
        verify(batchJobLockRegistry, never()).release(any());
        verify(stockBatchJobRepositoryRecorder).complete(eq(executionRecord), eq(7), any(LocalDateTime.class));
        verify(lockHeartbeatExecutor, never()).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any());
    }

    @Test
    void run_lockAcquireThrows_whenFailRecordingThrows_preservesOriginalFailureResponse() {
        TestStockBatchJob job = new TestStockBatchJob("lock-fail-recording-fail-job", "test-mode", 7);
        RuntimeException lockFailure = new IllegalStateException("lock table unavailable");
        when(batchJobLockRegistry.tryAcquire(eq("lock-fail-recording-fail-job"), any(LocalDateTime.class)))
                .thenThrow(lockFailure);
        doThrow(new IllegalStateException("batch metadata commit timeout"))
                .when(stockBatchJobRepositoryRecorder)
                .fail(eq(executionRecord), eq(lockFailure), any(LocalDateTime.class));

        var response = runner().run(job);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.processedCount()).isZero();
        assertThat(response.message()).isEqualTo("lock table unavailable");
        assertThat(job.runCount()).isZero();
        verify(stockBatchJobRepositoryRecorder).fail(eq(executionRecord), eq(lockFailure), any(LocalDateTime.class));
        verify(stockBatchJobRepositoryRecorder, never()).complete(any(), anyInt(), any());
        verify(stockBatchJobRepositoryRecorder, never()).skip(any(), any());
        verify(batchJobLockRegistry, never()).release("lock-fail-recording-fail-job");
        verify(lockHeartbeatExecutor, never()).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any());
    }

    @Test
    void run_lockHeartbeatRenewsCurrentLockUntilJobFinishes() {
        TestStockBatchJob job = new TestStockBatchJob("heartbeat-job", "test-mode", 5);
        when(batchJobLockRegistry.tryAcquire(eq("heartbeat-job"), any(LocalDateTime.class)))
                .thenReturn(true);
        when(batchJobLockRegistry.renew(eq("heartbeat-job"), any(LocalDateTime.class)))
                .thenReturn(true);

        var response = runner().run(job);

        assertThat(response.status()).isEqualTo("COMPLETED");
        ArgumentCaptor<Runnable> heartbeatCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(lockHeartbeatExecutor).scheduleWithFixedDelay(
                heartbeatCaptor.capture(),
                eq(30L),
                eq(30L),
                eq(TimeUnit.SECONDS)
        );
        heartbeatCaptor.getValue().run();
        verify(batchJobLockRegistry).renew(eq("heartbeat-job"), any(LocalDateTime.class));
        verify(lockHeartbeatFuture).cancel(false);
    }

    @Test
    void run_lockHeartbeatLosesOwnership_recordsFailedExecution() {
        AtomicReference<Runnable> scheduledHeartbeat = new AtomicReference<>();
        doAnswer(invocation -> {
            scheduledHeartbeat.set(invocation.getArgument(0));
            return lockHeartbeatFuture;
        }).when(lockHeartbeatExecutor).scheduleWithFixedDelay(
                any(Runnable.class),
                eq(30L),
                eq(30L),
                eq(TimeUnit.SECONDS)
        );
        TestStockBatchJob job = new TestStockBatchJob(
                "heartbeat-lost-job",
                "test-mode",
                5,
                () -> scheduledHeartbeat.get().run()
        );
        when(batchJobLockRegistry.tryAcquire(eq("heartbeat-lost-job"), any(LocalDateTime.class)))
                .thenReturn(true);
        when(batchJobLockRegistry.renew(eq("heartbeat-lost-job"), any(LocalDateTime.class)))
                .thenReturn(false);

        var response = runner().run(job);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.processedCount()).isZero();
        assertThat(response.message()).isEqualTo("Job lock heartbeat lost ownership");
        assertThat(job.runCount()).isEqualTo(1);
        verify(stockBatchJobRepositoryRecorder).fail(
                eq(executionRecord),
                any(IllegalStateException.class),
                any(LocalDateTime.class)
        );
        verify(stockBatchJobRepositoryRecorder, never()).complete(any(), anyInt(), any());
        verify(lockHeartbeatFuture).cancel(false);
    }

    @Test
    void run_lockHeartbeatThrows_recordsFailedExecution() {
        AtomicReference<Runnable> scheduledHeartbeat = new AtomicReference<>();
        doAnswer(invocation -> {
            scheduledHeartbeat.set(invocation.getArgument(0));
            return lockHeartbeatFuture;
        }).when(lockHeartbeatExecutor).scheduleWithFixedDelay(
                any(Runnable.class),
                eq(30L),
                eq(30L),
                eq(TimeUnit.SECONDS)
        );
        RuntimeException heartbeatFailure = new IllegalStateException("lock table timeout");
        TestStockBatchJob job = new TestStockBatchJob(
                "heartbeat-error-job",
                "test-mode",
                5,
                () -> scheduledHeartbeat.get().run()
        );
        when(batchJobLockRegistry.tryAcquire(eq("heartbeat-error-job"), any(LocalDateTime.class)))
                .thenReturn(true);
        when(batchJobLockRegistry.renew(eq("heartbeat-error-job"), any(LocalDateTime.class)))
                .thenThrow(heartbeatFailure);

        var response = runner().run(job);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.processedCount()).isZero();
        assertThat(response.message()).isEqualTo("lock table timeout");
        verify(stockBatchJobRepositoryRecorder).fail(
                eq(executionRecord),
                eq(heartbeatFailure),
                any(LocalDateTime.class)
        );
        verify(stockBatchJobRepositoryRecorder, never()).complete(any(), anyInt(), any());
        verify(lockHeartbeatFuture).cancel(false);
    }

    @Test
    void run_releaseThrows_preservesCompletedResponseAndCompletedExecution() {
        TestStockBatchJob job = new TestStockBatchJob("release-fail-job", "test-mode", 5);
        when(batchJobLockRegistry.tryAcquire(eq("release-fail-job"), any(LocalDateTime.class)))
                .thenReturn(true);
        doThrow(new IllegalStateException("release unavailable"))
                .when(batchJobLockRegistry)
                .release("release-fail-job");

        var response = runner().run(job);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.processedCount()).isEqualTo(5);
        assertThat(job.runCount()).isEqualTo(1);
        verify(stockBatchJobRepositoryRecorder).complete(eq(executionRecord), eq(5), any(LocalDateTime.class));
        verify(stockBatchJobRepositoryRecorder, never()).fail(any(), any(RuntimeException.class), any());
        verify(lockHeartbeatFuture).cancel(false);
    }

    @Test
    void run_completeRecordingThrows_returnsFailedResponseWithoutSecondFailRecording() {
        TestStockBatchJob job = new TestStockBatchJob("complete-recording-fail-job", "test-mode", 5);
        RuntimeException completeFailure = new IllegalStateException("batch metadata commit timeout");
        when(batchJobLockRegistry.tryAcquire(eq("complete-recording-fail-job"), any(LocalDateTime.class)))
                .thenReturn(true);
        doThrow(completeFailure)
                .when(stockBatchJobRepositoryRecorder)
                .complete(eq(executionRecord), eq(5), any(LocalDateTime.class));

        var response = runner().run(job);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.processedCount()).isZero();
        assertThat(response.message()).isEqualTo("batch metadata commit timeout");
        assertThat(job.runCount()).isEqualTo(1);
        verify(stockBatchJobRepositoryRecorder).complete(eq(executionRecord), eq(5), any(LocalDateTime.class));
        verify(stockBatchJobRepositoryRecorder, never()).fail(any(), any(RuntimeException.class), any());
        verify(lockHeartbeatFuture).cancel(false);
    }

    @Test
    void run_skipRecordingThrows_preservesAlreadyRunningResponse() {
        TestStockBatchJob job = new TestStockBatchJob("skip-recording-fail-job", "test-mode", 5);
        when(batchJobLockRegistry.tryAcquire(eq("skip-recording-fail-job"), any(LocalDateTime.class)))
                .thenReturn(false);
        doThrow(new IllegalStateException("batch metadata commit timeout"))
                .when(stockBatchJobRepositoryRecorder)
                .skip(eq(executionRecord), any(LocalDateTime.class));

        var response = runner().run(job);

        assertThat(response.status()).isEqualTo("SKIPPED");
        assertThat(response.processedCount()).isZero();
        assertThat(response.message()).isEqualTo("Job is already running");
        assertThat(job.runCount()).isZero();
        verify(stockBatchJobRepositoryRecorder).skip(eq(executionRecord), any(LocalDateTime.class));
        verify(stockBatchJobRepositoryRecorder, never()).complete(any(), anyInt(), any());
        verify(stockBatchJobRepositoryRecorder, never()).fail(any(), any(RuntimeException.class), any());
        verify(lockHeartbeatExecutor, never()).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any());
    }

    @Test
    void run_jobReturnsNegativeProcessedCount_recordsFailedExecution() {
        TestStockBatchJob job = new TestStockBatchJob("negative-count-job", "test-mode", -1);
        when(batchJobLockRegistry.tryAcquire(eq("negative-count-job"), any(LocalDateTime.class)))
                .thenReturn(true);

        var response = runner().run(job);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.processedCount()).isZero();
        assertThat(response.message()).contains("negative processed count");
        assertThat(job.runCount()).isEqualTo(1);
        verify(stockBatchJobRepositoryRecorder).fail(
                eq(executionRecord),
                any(IllegalStateException.class),
                any(LocalDateTime.class)
        );
        verify(stockBatchJobRepositoryRecorder, never()).complete(any(), anyInt(), any());
        verify(lockHeartbeatFuture).cancel(false);
    }

    @Test
    void run_jobThrows_whenFailRecordingThrows_preservesOriginalFailureResponse() {
        RuntimeException jobFailure = new IllegalStateException("job query timeout");
        TestStockBatchJob job = new TestStockBatchJob("job-fail-recording-fail-job", "test-mode", 5, () -> {
            throw jobFailure;
        });
        when(batchJobLockRegistry.tryAcquire(eq("job-fail-recording-fail-job"), any(LocalDateTime.class)))
                .thenReturn(true);
        doThrow(new IllegalStateException("batch metadata commit timeout"))
                .when(stockBatchJobRepositoryRecorder)
                .fail(eq(executionRecord), eq(jobFailure), any(LocalDateTime.class));

        var response = runner().run(job);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.processedCount()).isZero();
        assertThat(response.message()).isEqualTo("job query timeout");
        assertThat(job.runCount()).isEqualTo(1);
        verify(stockBatchJobRepositoryRecorder).fail(eq(executionRecord), eq(jobFailure), any(LocalDateTime.class));
        verify(stockBatchJobRepositoryRecorder, never()).complete(any(), anyInt(), any());
        verify(lockHeartbeatFuture).cancel(false);
    }

    @Test
    void run_jobThrowsWithoutMessage_returnsExceptionTypeAsFailureMessage() {
        RuntimeException jobFailure = new IllegalStateException();
        TestStockBatchJob job = new TestStockBatchJob("job-fail-empty-message-job", "test-mode", 5, () -> {
            throw jobFailure;
        });
        when(batchJobLockRegistry.tryAcquire(eq("job-fail-empty-message-job"), any(LocalDateTime.class)))
                .thenReturn(true);

        var response = runner().run(job);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.processedCount()).isZero();
        assertThat(response.message()).isEqualTo("IllegalStateException");
        verify(stockBatchJobRepositoryRecorder).fail(eq(executionRecord), eq(jobFailure), any(LocalDateTime.class));
        verify(stockBatchJobRepositoryRecorder, never()).complete(any(), anyInt(), any());
        verify(lockHeartbeatFuture).cancel(false);
    }

    @Test
    void constructor_heartbeatIntervalNotShorterThanLockTtl_rejectsConfiguration() {
        assertThatThrownBy(() -> new StockBatchJobRunner(
                batchJobLockRegistry,
                stockBatchJobRepositoryRecorder,
                60,
                lockHeartbeatExecutor
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("lockHeartbeatIntervalSeconds must be shorter than lockTtlSeconds");
    }

    @Test
    void run_shutdownInProgress_skipsNewJobWithoutStartingExecutionRecord() {
        StockBatchJobRunner runner = runner();
        runner.shutdown();
        TestStockBatchJob job = new TestStockBatchJob("shutdown-skip-job", "test-mode", 5);

        var response = runner.run(job);

        assertThat(response.status()).isEqualTo("SKIPPED");
        assertThat(response.message()).isEqualTo("Batch service is shutting down");
        assertThat(job.runCount()).isZero();
        verify(stockBatchJobRepositoryRecorder, never()).start(any(), any());
        verify(batchJobLockRegistry, never()).tryAcquire(any(), any());
    }

    @Test
    void hasActiveJobs_reportsRunningJobUntilCompletion() throws Exception {
        StockBatchJobRunner runner = runner();
        CountDownLatch jobStarted = new CountDownLatch(1);
        CountDownLatch releaseJob = new CountDownLatch(1);
        when(batchJobLockRegistry.tryAcquire(eq("active-state-job"), any(LocalDateTime.class))).thenReturn(true);
        TestStockBatchJob job = new TestStockBatchJob("active-state-job", "test-mode", 5, () -> {
            jobStarted.countDown();
            try {
                releaseJob.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var runFuture = executor.submit(() -> runner.run(job));
            assertThat(jobStarted.await(3, TimeUnit.SECONDS)).isTrue();

            assertThat(runner.hasActiveJobs()).isTrue();

            releaseJob.countDown();
            assertThat(runFuture.get(3, TimeUnit.SECONDS).status()).isEqualTo("COMPLETED");
            assertThat(runner.hasActiveJobs()).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shutdown_waitsForRunningJobBeforeStoppingHeartbeatExecutor() throws Exception {
        StockBatchJobRunner runner = runner();
        CountDownLatch jobStarted = new CountDownLatch(1);
        CountDownLatch releaseJob = new CountDownLatch(1);
        CountDownLatch heartbeatShutdownCalled = new CountDownLatch(1);
        when(batchJobLockRegistry.tryAcquire(eq("running-job"), any(LocalDateTime.class))).thenReturn(true);
        doAnswer(invocation -> {
            heartbeatShutdownCalled.countDown();
            return null;
        }).when(lockHeartbeatExecutor).shutdown();
        TestStockBatchJob job = new TestStockBatchJob("running-job", "test-mode", 5, () -> {
            jobStarted.countDown();
            try {
                releaseJob.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var runFuture = executor.submit(() -> runner.run(job));
            assertThat(jobStarted.await(3, TimeUnit.SECONDS)).isTrue();

            Thread shutdownThread = new Thread(runner::shutdown, "stock-batch-runner-shutdown-test");
            shutdownThread.start();

            assertThat(heartbeatShutdownCalled.await(200, TimeUnit.MILLISECONDS)).isFalse();
            releaseJob.countDown();
            shutdownThread.join(3000);

            assertThat(shutdownThread.isAlive()).isFalse();
            assertThat(runFuture.get(3, TimeUnit.SECONDS).status()).isEqualTo("COMPLETED");
            verify(lockHeartbeatExecutor).shutdown();
            verify(lockHeartbeatFuture).cancel(false);
        } finally {
            executor.shutdownNow();
        }
    }

    private StockBatchJobRunner runner() {
        return new StockBatchJobRunner(
                batchJobLockRegistry,
                stockBatchJobRepositoryRecorder,
                30,
                lockHeartbeatExecutor
        );
    }

    private static final class TestStockBatchJob implements StockBatchJob {

        private final String jobName;
        private final String executionMode;
        private final int processedCount;
        private final Runnable onRun;
        private final boolean requiresJobLock;
        private int runCount;

        private TestStockBatchJob(String jobName, String executionMode, int processedCount) {
            this(jobName, executionMode, processedCount, () -> {
            });
        }

        private TestStockBatchJob(String jobName, String executionMode, int processedCount, Runnable onRun) {
            this(jobName, executionMode, processedCount, onRun, true);
        }

        private TestStockBatchJob(
                String jobName,
                String executionMode,
                int processedCount,
                Runnable onRun,
                boolean requiresJobLock
        ) {
            this.jobName = jobName;
            this.executionMode = executionMode;
            this.processedCount = processedCount;
            this.onRun = onRun;
            this.requiresJobLock = requiresJobLock;
        }

        @Override
        public String jobName() {
            return jobName;
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
            onRun.run();
            return processedCount;
        }

        private int runCount() {
            return runCount;
        }
    }
}
