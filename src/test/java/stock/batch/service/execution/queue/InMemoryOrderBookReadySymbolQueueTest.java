package stock.batch.service.execution.queue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryOrderBookReadySymbolQueueTest {

    @Test
    void tryAcquireReconciliationLease_activeLease_excludesConcurrentReconcilerUntilClose() {
        InMemoryOrderBookReadySymbolQueue queue = new InMemoryOrderBookReadySymbolQueue();

        Optional<OrderBookReadySymbolQueue.ReconciliationLease> firstLease =
                queue.tryAcquireReconciliationLease(0L);
        Optional<OrderBookReadySymbolQueue.ReconciliationLease> competingLease =
                queue.tryAcquireReconciliationLease(0L);
        boolean firstCursorUpdated = firstLease.orElseThrow().updateCursor("DEMO008");
        firstLease.orElseThrow().close();
        Optional<OrderBookReadySymbolQueue.ReconciliationLease> leaseAfterRelease =
                queue.tryAcquireReconciliationLease(0L);
        String sharedCursor = leaseAfterRelease.orElseThrow().cursor();
        firstLease.orElseThrow().close();
        Optional<OrderBookReadySymbolQueue.ReconciliationLease> leaseAfterStaleRelease =
                queue.tryAcquireReconciliationLease(0L);

        assertThat(List.of(
                firstLease.isPresent(),
                competingLease.isPresent(),
                leaseAfterRelease.isPresent(),
                leaseAfterStaleRelease.isPresent(),
                firstCursorUpdated
        )).containsExactly(true, false, true, false, true);
        assertThat(sharedCursor).isEqualTo("DEMO008");
        leaseAfterRelease.orElseThrow().close();
    }

    @Test
    void tryAcquireReconciliationLease_activeCooldown_rejectsImmediateNextRun() {
        InMemoryOrderBookReadySymbolQueue queue = new InMemoryOrderBookReadySymbolQueue();

        OrderBookReadySymbolQueue.ReconciliationLease lease =
                queue.tryAcquireReconciliationLease(30_000L).orElseThrow();
        lease.updateCursor("DEMO008");
        lease.close();

        assertThat(queue.tryAcquireReconciliationLease(30_000L)).isEmpty();
    }
}
