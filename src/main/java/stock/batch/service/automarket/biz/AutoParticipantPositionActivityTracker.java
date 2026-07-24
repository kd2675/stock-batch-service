package stock.batch.service.automarket.biz;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Bounded process-local intraday position clock for automatic participants.
 * The database holding remains authoritative; this tracker only avoids a hot-ledger
 * read/write on every decision and reports unavailable after a process restart.
 */
@Component
public class AutoParticipantPositionActivityTracker {

    static final int MAX_TRACKED_POSITIONS = 20_000;

    private final ConcurrentHashMap<PositionKey, PositionClock> clocks = new ConcurrentHashMap<>();

    public void register(long accountId, String symbol, long holdingQuantity, LocalDateTime asOf) {
        PositionKey key = PositionKey.of(accountId, symbol);
        if (!key.valid() || asOf == null) {
            return;
        }
        PositionClock existing = clocks.get(key);
        if (existing == null && clocks.size() >= MAX_TRACKED_POSITIONS) {
            return;
        }
        clocks.compute(key, (ignored, current) -> {
            PositionClock clock = current == null ? new PositionClock() : current;
            clock.reconcile(Math.max(0L, holdingQuantity));
            return clock;
        });
    }

    public void record(
            String symbol,
            long quantity,
            long buyAccountId,
            long sellAccountId,
            LocalDateTime executedAt
    ) {
        if (quantity <= 0 || executedAt == null) {
            return;
        }
        updateIfRegistered(PositionKey.of(buyAccountId, symbol), quantity, executedAt);
        updateIfRegistered(PositionKey.of(sellAccountId, symbol), -quantity, executedAt);
    }

    public PositionAgeSnapshot snapshot(long accountId, String symbol, LocalDateTime asOf) {
        if (asOf == null) {
            return PositionAgeSnapshot.UNAVAILABLE;
        }
        PositionClock clock = clocks.get(PositionKey.of(accountId, symbol));
        return clock == null ? PositionAgeSnapshot.UNAVAILABLE : clock.snapshot(asOf);
    }

    private void updateIfRegistered(PositionKey key, long quantityDelta, LocalDateTime executedAt) {
        PositionClock clock = clocks.get(key);
        if (clock != null) {
            clock.record(quantityDelta, executedAt);
        }
    }

    public record PositionAgeSnapshot(long ageSeconds, boolean available) {
        public static final PositionAgeSnapshot UNAVAILABLE = new PositionAgeSnapshot(0L, false);

        public PositionAgeSnapshot {
            ageSeconds = Math.max(0L, ageSeconds);
        }
    }

    private record PositionKey(long accountId, String symbol) {
        private static PositionKey of(long accountId, String symbol) {
            return new PositionKey(accountId, symbol == null ? "" : symbol.trim().toUpperCase(java.util.Locale.ROOT));
        }

        private boolean valid() {
            return accountId > 0 && !symbol.isBlank();
        }
    }

    private static final class PositionClock {

        private long quantity;
        private LocalDateTime openedAt;

        private synchronized void reconcile(long authoritativeQuantity) {
            quantity = authoritativeQuantity;
            if (quantity == 0L) {
                openedAt = null;
            }
        }

        private synchronized void record(long quantityDelta, LocalDateTime executedAt) {
            long previousQuantity = quantity;
            quantity = Math.max(0L, quantity + quantityDelta);
            if (previousQuantity == 0L && quantity > 0L && quantityDelta > 0L) {
                openedAt = executedAt;
            }
            if (quantity == 0L) {
                openedAt = null;
            }
        }

        private synchronized PositionAgeSnapshot snapshot(LocalDateTime asOf) {
            if (quantity <= 0L || openedAt == null || asOf.isBefore(openedAt)) {
                return PositionAgeSnapshot.UNAVAILABLE;
            }
            return new PositionAgeSnapshot(Duration.between(openedAt, asOf).toSeconds(), true);
        }
    }
}
