package stock.batch.service.batch.signal.biz;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import stock.batch.service.batch.signal.model.BatchJobSignal;
import stock.batch.service.common.vo.StockBatchJobRunResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BatchJobSignalLeaseHeartbeatTest {

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void execute_longRunningSignal_renewsExactClaimUntilActionCompletes() throws Exception {
        BatchJobSignalReader reader = mock(BatchJobSignalReader.class);
        CountDownLatch periodicRenewal = new CountDownLatch(1);
        AtomicInteger renewals = new AtomicInteger();
        when(reader.renewLease(any(BatchJobSignal.class), any(LocalDateTime.class))).thenAnswer(invocation -> {
            if (renewals.incrementAndGet() >= 2) {
                periodicRenewal.countDown();
            }
            return true;
        });
        BatchJobSignalLeaseHeartbeat heartbeat = new BatchJobSignalLeaseHeartbeat(reader, 1, 3, executor);
        BatchJobSignal signal = signal(10L);

        StockBatchJobRunResponse response = heartbeat.execute(signal, () -> {
            try {
                assertThat(periodicRenewal.await(2, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
            return response();
        });

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(renewals.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void execute_claimAlreadyLost_rejectsBeforeLaunchingJob() {
        BatchJobSignalReader reader = mock(BatchJobSignalReader.class);
        when(reader.renewLease(any(BatchJobSignal.class), any(LocalDateTime.class))).thenReturn(false);
        BatchJobSignalLeaseHeartbeat heartbeat = new BatchJobSignalLeaseHeartbeat(reader, 1, 3, executor);
        AtomicInteger jobRuns = new AtomicInteger();

        assertThatThrownBy(() -> heartbeat.execute(signal(11L), () -> {
            jobRuns.incrementAndGet();
            return response();
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claim was lost");
        assertThat(jobRuns.get()).isZero();
    }

    @Test
    void execute_transientHeartbeatFailure_recoversOnLaterRenewal() {
        BatchJobSignalReader reader = mock(BatchJobSignalReader.class);
        CountDownLatch recoveredRenewal = new CountDownLatch(1);
        AtomicInteger renewals = new AtomicInteger();
        when(reader.renewLease(any(BatchJobSignal.class), any(LocalDateTime.class))).thenAnswer(invocation -> {
            int renewal = renewals.incrementAndGet();
            if (renewal == 2) {
                throw new IllegalStateException("temporary database interruption");
            }
            if (renewal >= 3) {
                recoveredRenewal.countDown();
            }
            return true;
        });
        BatchJobSignalLeaseHeartbeat heartbeat = new BatchJobSignalLeaseHeartbeat(reader, 1, 4, executor);

        StockBatchJobRunResponse response = heartbeat.execute(signal(12L), () -> {
            try {
                assertThat(recoveredRenewal.await(3, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
            return response();
        });

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(renewals.get()).isGreaterThanOrEqualTo(4);
    }

    @Test
    void execute_claimLostAtFinalRenewal_doesNotReturnCompletedResult() {
        BatchJobSignalReader reader = mock(BatchJobSignalReader.class);
        when(reader.renewLease(any(BatchJobSignal.class), any(LocalDateTime.class)))
                .thenReturn(true)
                .thenReturn(false);
        BatchJobSignalLeaseHeartbeat heartbeat = new BatchJobSignalLeaseHeartbeat(reader, 1, 4, executor);

        assertThatThrownBy(() -> heartbeat.execute(signal(13L), this::response))
                .isInstanceOf(BatchJobSignalClaimLostException.class)
                .hasMessageContaining("id=13");
    }

    private BatchJobSignal signal(long id) {
        LocalDateTime now = LocalDateTime.of(2026, 7, 16, 0, 10);
        return new BatchJobSignal(
                id,
                "AUTO_PARTICIPANT_CASH_FLOW_RUN",
                "auto-participant-cash-flow",
                "manual-recurring-cash",
                null,
                "admin",
                now
        );
    }

    private StockBatchJobRunResponse response() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 16, 0, 10);
        return new StockBatchJobRunResponse(
                "auto-participant-cash-flow",
                "COMPLETED",
                "manual-recurring-cash",
                1,
                "completed",
                now,
                now
        );
    }
}
