package stock.batch.service.automarket.biz;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Keeps a bounded, process-local corroboration signal for profile decisions.
 * It is deliberately non-authoritative: executions remain the source of truth,
 * and a process restart produces an unavailable snapshot rather than a false zero.
 */
@Component
public class RecentMarketActivityTracker {

    static final int BUCKET_SECONDS = 30;
    static final int WINDOW_BUCKET_COUNT = 10;
    static final int MAX_TRACKED_SYMBOLS = 1_000;

    private final ConcurrentHashMap<String, SymbolWindow> windows = new ConcurrentHashMap<>();

    public void observe(String symbol, LocalDateTime observedAt) {
        if (symbol == null || symbol.isBlank() || observedAt == null) {
            return;
        }
        SymbolWindow window = windowFor(symbol);
        if (window != null) {
            window.observe(slot(observedAt));
        }
    }

    public void record(
            String symbol,
            long quantity,
            long buyAccountId,
            long sellAccountId,
            LocalDateTime executedAt
    ) {
        if (symbol == null || symbol.isBlank() || quantity <= 0 || executedAt == null) {
            return;
        }
        SymbolWindow window = windowFor(symbol);
        if (window == null) {
            return;
        }
        window.record(slot(executedAt), quantity, buyAccountId, sellAccountId);
    }

    public RecentMarketActivitySnapshot snapshot(String symbol, LocalDateTime asOf) {
        if (symbol == null || symbol.isBlank() || asOf == null) {
            return RecentMarketActivitySnapshot.EMPTY;
        }
        SymbolWindow window = windows.get(symbol.trim().toUpperCase(java.util.Locale.ROOT));
        return window == null
                ? RecentMarketActivitySnapshot.EMPTY
                : window.snapshot(slot(asOf));
    }

    private long slot(LocalDateTime time) {
        return time.toEpochSecond(ZoneOffset.UTC) / BUCKET_SECONDS;
    }

    private SymbolWindow windowFor(String symbol) {
        String normalizedSymbol = symbol.trim().toUpperCase(java.util.Locale.ROOT);
        SymbolWindow window = windows.get(normalizedSymbol);
        if (window != null) {
            return window;
        }
        if (windows.size() >= MAX_TRACKED_SYMBOLS) {
            return null;
        }
        SymbolWindow candidate = new SymbolWindow();
        SymbolWindow existing = windows.putIfAbsent(normalizedSymbol, candidate);
        return existing == null ? candidate : existing;
    }

    public record RecentMarketActivitySnapshot(
            long executionQuantity,
            int participantCount,
            boolean available
    ) {
        public static final RecentMarketActivitySnapshot EMPTY = new RecentMarketActivitySnapshot(0L, 0, false);

        public RecentMarketActivitySnapshot {
            executionQuantity = Math.max(0L, executionQuantity);
            participantCount = Math.max(0, participantCount);
        }
    }

    private static final class SymbolWindow {

        private final NavigableMap<Long, ActivityBucket> buckets = new TreeMap<>();
        private final NavigableSet<Long> observedSlots = new TreeSet<>();
        private long latestObservedSlot = Long.MIN_VALUE;

        private synchronized void observe(long slot) {
            latestObservedSlot = Math.max(latestObservedSlot, slot);
            observedSlots.add(slot);
            evictBefore(latestObservedSlot - WINDOW_BUCKET_COUNT + 1);
        }

        private synchronized void record(
                long slot,
                long quantity,
                long buyAccountId,
                long sellAccountId
        ) {
            latestObservedSlot = Math.max(latestObservedSlot, slot);
            observedSlots.add(slot);
            long minimumSlot = latestObservedSlot - WINDOW_BUCKET_COUNT + 1;
            evictBefore(minimumSlot);
            if (slot < minimumSlot) {
                return;
            }
            ActivityBucket bucket = buckets.computeIfAbsent(slot, ActivityBucket::new);
            while (buckets.size() > WINDOW_BUCKET_COUNT) {
                buckets.pollFirstEntry();
            }
            bucket.quantity += quantity;
            bucket.participantAccountIds.add(buyAccountId);
            bucket.participantAccountIds.add(sellAccountId);
        }

        private synchronized RecentMarketActivitySnapshot snapshot(long asOfSlot) {
            observe(asOfSlot);
            long minimumSlot = asOfSlot - WINDOW_BUCKET_COUNT + 1;
            evictBefore(minimumSlot);
            boolean fullWindowObserved = observedSlots
                    .subSet(minimumSlot, true, asOfSlot, true)
                    .size() == WINDOW_BUCKET_COUNT;
            if (!fullWindowObserved) {
                return RecentMarketActivitySnapshot.EMPTY;
            }
            long quantity = 0L;
            Set<Long> participants = new HashSet<>();
            for (ActivityBucket bucket : buckets.subMap(minimumSlot, true, asOfSlot, true).values()) {
                quantity += bucket.quantity;
                participants.addAll(bucket.participantAccountIds);
            }
            return new RecentMarketActivitySnapshot(quantity, participants.size(), true);
        }

        private void evictBefore(long minimumSlot) {
            buckets.headMap(minimumSlot, false).clear();
            observedSlots.headSet(minimumSlot, false).clear();
        }
    }

    private static final class ActivityBucket {

        private final long slot;
        private long quantity;
        private final Set<Long> participantAccountIds = new HashSet<>();

        private ActivityBucket(long slot) {
            this.slot = slot;
        }
    }
}
