package stock.batch.service.automarket.queue;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public interface AutoMarketReadyProfileQueue {

    boolean enqueue(AutoParticipantProfileType profileType, LocalDateTime readyAt);

    Optional<AutoParticipantProfileType> claimDueProfile(LocalDateTime now);

    default int enqueueAll(Collection<ReadyProfile> profiles) {
        int enqueuedCount = 0;
        for (ReadyProfile profile : profiles) {
            if (enqueue(profile.profileType(), profile.readyAt())) {
                enqueuedCount++;
            }
        }
        return enqueuedCount;
    }

    record ReadyProfile(AutoParticipantProfileType profileType, LocalDateTime readyAt) {
    }
}
