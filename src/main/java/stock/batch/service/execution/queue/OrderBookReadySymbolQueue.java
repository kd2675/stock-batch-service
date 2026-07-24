package stock.batch.service.execution.queue;

import java.util.Collection;
import java.util.Optional;

public interface OrderBookReadySymbolQueue {

    boolean enqueue(String symbol);

    Optional<String> poll();

    Optional<ReconciliationLease> tryAcquireReconciliationLease(long minimumIntervalMillis);

    default int enqueueAll(Collection<String> symbols) {
        int enqueuedCount = 0;
        for (String symbol : symbols) {
            if (enqueue(symbol)) {
                enqueuedCount++;
            }
        }
        return enqueuedCount;
    }

    default int reconcileAll(Collection<String> symbols) {
        return enqueueAll(symbols);
    }

    interface ReconciliationLease extends AutoCloseable {

        String cursor();

        boolean updateCursor(String nextCursor);

        @Override
        void close();
    }
}
