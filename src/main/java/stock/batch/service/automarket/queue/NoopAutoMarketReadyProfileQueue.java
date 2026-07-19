package stock.batch.service.automarket.queue;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

@Component
@ConditionalOnProperty(name = "stock.batch.auto-market.profile-queue.type", havingValue = "none")
public class NoopAutoMarketReadyProfileQueue implements AutoMarketReadyProfileQueue {

    @Override
    public boolean enqueue(AutoParticipantProfileType profileType, LocalDateTime readyAt) {
        return false;
    }

    @Override
    public Optional<AutoParticipantProfileType> claimDueProfile(LocalDateTime now) {
        return Optional.empty();
    }

    @Override
    public int replaceAll(Collection<ReadyProfile> profiles) {
        if (profiles == null) {
            throw new IllegalArgumentException("Ready profiles must not be null");
        }
        if (profiles.isEmpty()) {
            return 0;
        }
        throw new IllegalStateException(
                "Strict pre-open profile queue replacement is unavailable when profile-queue.type=none"
        );
    }

    @Override
    public Map<AutoParticipantProfileType, LocalDateTime> snapshot() {
        return Map.of();
    }
}
