package stock.batch.service.execution.queue;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "stock.batch.execution.ready-symbol-queue.type", havingValue = "memory")
public class InMemoryOrderBookReadySymbolQueue implements OrderBookReadySymbolQueue {

    private final Set<String> symbols = new LinkedHashSet<>();
    private Object reconciliationLeaseOwner;
    private String reconciliationCursor = "";
    private long nextReconciliationNanos;

    @Override
    public synchronized boolean enqueue(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return false;
        }
        return symbols.add(symbol);
    }

    @Override
    public synchronized Optional<String> poll() {
        if (symbols.isEmpty()) {
            return Optional.empty();
        }
        String symbol = symbols.iterator().next();
        symbols.remove(symbol);
        return Optional.of(symbol);
    }

    @Override
    public synchronized Optional<ReconciliationLease> tryAcquireReconciliationLease(long minimumIntervalMillis) {
        if (minimumIntervalMillis < 0L) {
            throw new IllegalArgumentException("minimumIntervalMillis must not be negative");
        }
        long now = System.nanoTime();
        if (reconciliationLeaseOwner != null || now < nextReconciliationNanos) {
            return Optional.empty();
        }
        Object owner = new Object();
        long minimumIntervalNanos = TimeUnit.MILLISECONDS.toNanos(minimumIntervalMillis);
        reconciliationLeaseOwner = owner;
        nextReconciliationNanos = now + minimumIntervalNanos;
        return Optional.of(new ReconciliationLease() {
            @Override
            public String cursor() {
                synchronized (InMemoryOrderBookReadySymbolQueue.this) {
                    return reconciliationCursor;
                }
            }

            @Override
            public boolean updateCursor(String nextCursor) {
                synchronized (InMemoryOrderBookReadySymbolQueue.this) {
                    if (reconciliationLeaseOwner != owner) {
                        return false;
                    }
                    reconciliationCursor = nextCursor == null ? "" : nextCursor;
                    nextReconciliationNanos = System.nanoTime() + minimumIntervalNanos;
                    return true;
                }
            }

            @Override
            public void close() {
                releaseReconciliationLease(owner);
            }
        });
    }

    private synchronized void releaseReconciliationLease(Object owner) {
        if (reconciliationLeaseOwner == owner) {
            reconciliationLeaseOwner = null;
        }
    }
}
