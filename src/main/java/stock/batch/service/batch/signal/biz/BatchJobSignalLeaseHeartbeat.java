package stock.batch.service.batch.signal.biz;

import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.signal.model.BatchJobSignal;
import stock.batch.service.common.vo.StockBatchJobRunResponse;

/**
 * Keeps the low-volume manual signal claim alive while its synchronous batch job is running.
 *
 * <p>The dedicated daemon is intentionally single-threaded because the maintenance scheduler
 * processes signals serially. Each tick updates one signal row by primary key; it does not touch
 * stock_order, stock_execution, account, holding, or price tables.</p>
 */
@Component
@Slf4j
public class BatchJobSignalLeaseHeartbeat {

    private final BatchJobSignalReader signalReader;
    private final long heartbeatIntervalSeconds;
    private final ScheduledExecutorService executor;

    @Autowired
    public BatchJobSignalLeaseHeartbeat(
            BatchJobSignalReader signalReader,
            @Value("${stock.batch.signal.heartbeat-interval-seconds:30}") long heartbeatIntervalSeconds,
            @Value("${stock.batch.signal.lease-seconds:180}") long leaseSeconds
    ) {
        this(
                signalReader,
                heartbeatIntervalSeconds,
                leaseSeconds,
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "stock-batch-signal-lease-heartbeat");
                    thread.setDaemon(true);
                    return thread;
                })
        );
    }

    BatchJobSignalLeaseHeartbeat(
            BatchJobSignalReader signalReader,
            long heartbeatIntervalSeconds,
            long leaseSeconds,
            ScheduledExecutorService executor
    ) {
        if (heartbeatIntervalSeconds <= 0 || heartbeatIntervalSeconds >= leaseSeconds) {
            throw new IllegalArgumentException(
                    "Signal heartbeat interval must be positive and shorter than leaseSeconds"
            );
        }
        this.signalReader = signalReader;
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
        this.executor = executor;
    }

    public StockBatchJobRunResponse execute(
            BatchJobSignal signal,
            Supplier<StockBatchJobRunResponse> action
    ) {
        requireOwnedClaim(signal);

        AtomicBoolean stopped = new AtomicBoolean(false);
        AtomicReference<RuntimeException> ownershipFailure = new AtomicReference<>();
        AtomicReference<RuntimeException> transientRenewalFailure = new AtomicReference<>();
        ReentrantLock renewalLock = new ReentrantLock();
        ScheduledFuture<?> future = executor.scheduleWithFixedDelay(
                () -> renew(
                        signal,
                        stopped,
                        ownershipFailure,
                        transientRenewalFailure,
                        renewalLock
                ),
                heartbeatIntervalSeconds,
                heartbeatIntervalSeconds,
                TimeUnit.SECONDS
        );

        StockBatchJobRunResponse response = null;
        RuntimeException actionFailure = null;
        try {
            response = action.get();
        } catch (RuntimeException failure) {
            actionFailure = failure;
        } finally {
            future.cancel(false);
            renewalLock.lock();
            try {
                stopped.set(true);
            } finally {
                renewalLock.unlock();
            }
        }

        RuntimeException leaseFailure = ownershipFailure.get();
        if (leaseFailure == null) {
            try {
                // Close the gap between the last periodic renewal and the terminal signal write.
                // A successful final renewal also proves that any earlier transient DB failure
                // recovered without transferring ownership to another scheduler.
                requireOwnedClaim(signal);
                transientRenewalFailure.set(null);
            } catch (RuntimeException failure) {
                leaseFailure = failure;
            }
        }
        if (actionFailure != null) {
            if (leaseFailure != null) {
                actionFailure.addSuppressed(leaseFailure);
            }
            throw actionFailure;
        }
        if (leaseFailure != null) {
            throw leaseFailure;
        }
        return response;
    }

    private void requireOwnedClaim(BatchJobSignal signal) {
        if (!signalReader.renewLease(signal, LocalDateTime.now())) {
            throw claimLost(signal);
        }
    }

    private void renew(
            BatchJobSignal signal,
            AtomicBoolean stopped,
            AtomicReference<RuntimeException> ownershipFailure,
            AtomicReference<RuntimeException> transientRenewalFailure,
            ReentrantLock renewalLock
    ) {
        renewalLock.lock();
        try {
            if (stopped.get() || ownershipFailure.get() != null) {
                return;
            }
            if (!signalReader.renewLease(signal, LocalDateTime.now())) {
                RuntimeException failure = claimLost(signal);
                if (ownershipFailure.compareAndSet(null, failure)) {
                    log.warn("Batch signal lease heartbeat lost ownership: signalId={}", signal.id());
                }
                return;
            }
            transientRenewalFailure.set(null);
        } catch (RuntimeException failure) {
            // A single DB/network hiccup must not stop future renewals. The 180-second lease is
            // deliberately longer than the 30-second cadence so subsequent ticks can recover.
            if (transientRenewalFailure.compareAndSet(null, failure)) {
                log.warn(
                        "Batch signal lease heartbeat temporarily failed and will retry: signalId={}, reason={}",
                        signal.id(),
                        failure.getMessage(),
                        failure
                );
            }
        } finally {
            renewalLock.unlock();
        }
    }

    private BatchJobSignalClaimLostException claimLost(BatchJobSignal signal) {
        return new BatchJobSignalClaimLostException(signal.id());
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
