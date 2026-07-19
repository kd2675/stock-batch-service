package stock.batch.service.automarket.queue;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
    public boolean hasDueProfile(LocalDateTime now) {
        return now != null && readyAtByProfile.values().stream().anyMatch(readyAt -> !readyAt.isAfter(now));
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

    @Override
    public int removeAll(Collection<AutoParticipantProfileType> profileTypes) {
        if (profileTypes == null || profileTypes.isEmpty()) {
            return 0;
        }
        int sizeBefore = readyAtByProfile.size();
        profileTypes.forEach(readyAtByProfile::remove);
        return sizeBefore - readyAtByProfile.size();
    }

    @Override
    public int replaceAll(Collection<ReadyProfile> profiles) {
        if (profiles == null) {
            throw new IllegalArgumentException("Ready profiles must not be null");
        }
        Map<AutoParticipantProfileType, LocalDateTime> replacements = new LinkedHashMap<>();
        for (ReadyProfile profile : profiles) {
            if (profile == null || profile.profileType() == null || profile.readyAt() == null) {
                throw new IllegalArgumentException("Ready profile type and ready time must not be null");
            }
            replacements.put(profile.profileType(), profile.readyAt());
        }
        readyAtByProfile.clear();
        readyAtByProfile.putAll(replacements);
        return readyAtByProfile.size();
    }

    @Override
    public Map<AutoParticipantProfileType, LocalDateTime> snapshot() {
        return Map.copyOf(readyAtByProfile);
    }
}
