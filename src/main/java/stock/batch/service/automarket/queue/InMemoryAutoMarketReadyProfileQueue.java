package stock.batch.service.automarket.queue;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

@Component
@ConditionalOnProperty(name = "stock.batch.auto-market.profile-queue.type", havingValue = "memory")
public class InMemoryAutoMarketReadyProfileQueue implements AutoMarketReadyProfileQueue {

    private final Map<AutoParticipantProfileType, LocalDateTime> readyAtByProfile = new ConcurrentHashMap<>();

    @Override
    public boolean enqueue(AutoParticipantProfileType profileType, LocalDateTime readyAt) {
        if (profileType == null || readyAt == null) {
            return false;
        }
        readyAtByProfile.put(profileType, readyAt);
        return true;
    }

    @Override
    public Optional<AutoParticipantProfileType> claimDueProfile(LocalDateTime now) {
        if (now == null) {
            return Optional.empty();
        }
        Optional<Map.Entry<AutoParticipantProfileType, LocalDateTime>> claimedEntry = readyAtByProfile.entrySet()
                .stream()
                .filter(entry -> !entry.getValue().isAfter(now))
                .min(Comparator
                        .comparing(Map.Entry<AutoParticipantProfileType, LocalDateTime>::getValue)
                        .thenComparing(entry -> entry.getKey().name()));
        claimedEntry.ifPresent(entry -> readyAtByProfile.remove(entry.getKey(), entry.getValue()));
        return claimedEntry.map(Map.Entry::getKey);
    }
}
