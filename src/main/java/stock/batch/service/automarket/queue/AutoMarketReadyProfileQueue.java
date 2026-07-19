package stock.batch.service.automarket.queue;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public interface AutoMarketReadyProfileQueue {

    boolean enqueue(AutoParticipantProfileType profileType, LocalDateTime readyAt);

    default boolean hasDueProfile(LocalDateTime now) {
        return true;
    }

    Optional<AutoParticipantProfileType> claimDueProfile(LocalDateTime now);

    default int removeAll(Collection<AutoParticipantProfileType> profileTypes) {
        return 0;
    }

    default int enqueueAll(Collection<ReadyProfile> profiles) {
        int enqueuedCount = 0;
        for (ReadyProfile profile : profiles) {
            if (enqueue(profile.profileType(), profile.readyAt())) {
                enqueuedCount++;
            }
        }
        return enqueuedCount;
    }

    /**
     * Replaces the complete ready-profile queue and returns the number of distinct profiles stored.
     * Implementations must fail instead of reporting partial success. This operation is reserved for
     * the closed-market pre-open preparation phase; regular-session reconciliation remains best effort.
     */
    default int replaceAll(Collection<ReadyProfile> profiles) {
        throw new IllegalStateException(
                "Strict auto-market ready profile queue replacement is not supported: "
                        + getClass().getSimpleName()
        );
    }

    /**
     * Returns the complete bounded profile queue for the PRE_OPEN readiness comparison. The key
     * cardinality cannot exceed the fixed profile enum and this method must never inspect orders,
     * executions, or account holdings.
     */
    default Map<AutoParticipantProfileType, LocalDateTime> snapshot() {
        throw new IllegalStateException(
                "Auto-market ready profile queue snapshot is not supported: "
                        + getClass().getSimpleName()
        );
    }

    record ReadyProfile(AutoParticipantProfileType profileType, LocalDateTime readyAt) {
    }
}
