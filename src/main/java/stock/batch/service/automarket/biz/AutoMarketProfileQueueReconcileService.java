package stock.batch.service.automarket.biz;

import jakarta.annotation.PostConstruct;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.automarket.profile.ProfilePolicy;
import stock.batch.service.automarket.queue.AutoMarketReadyProfileQueue;
import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;
import stock.batch.service.batch.automarket.reader.AutoMarketReader;
import stock.batch.service.simulation.SimulationClockService;
import web.common.core.simulation.SimulationClockSnapshot;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutoMarketProfileQueueReconcileService {

    private static final int MAX_RECONCILE_LIMIT = 1_000;

    private final AutoMarketReader autoMarketReader;
    private final AutoProfileBehaviorSupport autoProfileBehaviorSupport;
    private final AutoParticipantOrderScheduleService autoParticipantOrderScheduleService;
    private final AutoMarketReadyProfileQueue readyProfileQueue;
    private final SimulationClockService simulationClockService;
    private final TransactionTemplate transactionTemplate;

    @Value("${stock.batch.auto-market.profile-queue.reconcile-limit:100}")
    private int reconcileLimit;

    @PostConstruct
    void validateVolumeConfiguration() {
        if (reconcileLimit < 1 || reconcileLimit > MAX_RECONCILE_LIMIT) {
            throw new IllegalStateException(
                    "stock.batch.auto-market.profile-queue.reconcile-limit must be between 1 and %d: %d"
                            .formatted(MAX_RECONCILE_LIMIT, reconcileLimit)
            );
        }
    }

    public int reconcileReadyProfiles() {
        return reconcileReadyProfiles(autoMarketReader.findEnabledConfigs(), false);
    }

    public int reconcileReadyProfilesForPreOpen() {
        return reconcileReadyProfiles(autoMarketReader.findDailyRegimePreCreateConfigs(), true);
    }

    /**
     * Compares the complete Redis/JVM queue with the bounded profile-level schedule projection.
     * The comparison runs once in market-open readiness and never reads order or execution data.
     */
    public boolean isPreOpenQueueSynchronized() {
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        LocalDateTime now = clock.simulationDateTime();
        List<AutoMarketConfig> configs = autoMarketReader.findDailyRegimePreCreateConfigs();
        if (configs.isEmpty()) {
            return readyProfileQueue.snapshot().isEmpty();
        }
        if (autoMarketReader.hasMissingParticipantSchedules(configs)) {
            return false;
        }
        int profileLimit = Math.max(Math.max(1, reconcileLimit), AutoParticipantProfileType.values().length);
        Map<AutoParticipantProfileType, LocalDateTime> expected = toProfileScheduleMap(
                autoParticipantOrderScheduleService.findNextProfileSchedules(now, profileLimit)
        );
        Map<AutoParticipantProfileType, LocalDateTime> actual = readyProfileQueue.snapshot().entrySet()
                .stream()
                .collect(
                        LinkedHashMap::new,
                        (map, entry) -> map.put(entry.getKey(), normalizeQueueTime(entry.getValue())),
                        LinkedHashMap::putAll
                );
        return expected.equals(actual);
    }

    private int reconcileReadyProfiles(List<AutoMarketConfig> configs, boolean strictReplacement) {
        long startedNanos = System.nanoTime();
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        LocalDateTime now = clock.simulationDateTime();
        if (configs.isEmpty()) {
            int removedCount = strictReplacement
                    ? readyProfileQueue.replaceAll(List.of())
                    : readyProfileQueue.removeAll(EnumSet.allOf(AutoParticipantProfileType.class));
            log.info(
                    "Auto market profile queue reconcile skipped: enabledConfigs=0, remainingProfiles={}, queue={}, strict={}",
                    removedCount,
                    queueName(),
                    strictReplacement
            );
            return 0;
        }
        Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies =
                autoProfileBehaviorSupport.policiesWithOverrides(autoMarketReader.findParticipantProfileConfigs());
        int scheduledCount = ensureParticipantSchedules(configs, profilePolicies, now);
        int profileLimit = Math.max(Math.max(1, reconcileLimit), AutoParticipantProfileType.values().length);
        List<AutoMarketReadyProfileQueue.ReadyProfile> profiles =
                autoParticipantOrderScheduleService.findNextProfileSchedules(now, profileLimit);
        if (strictReplacement) {
            int expectedProfileCount = (int) profiles.stream()
                    .map(AutoMarketReadyProfileQueue.ReadyProfile::profileType)
                    .distinct()
                    .count();
            int storedProfileCount = readyProfileQueue.replaceAll(profiles);
            if (storedProfileCount != expectedProfileCount) {
                throw new IllegalStateException(
                        "Pre-open auto market profile queue replacement count mismatch: expected=%d, stored=%d"
                                .formatted(expectedProfileCount, storedProfileCount)
                );
            }
            log.info(
                    "Pre-open auto market profile queue replaced: profiles={}, scheduledStrategies={}, queue={}, elapsedMs={}",
                    storedProfileCount,
                    scheduledCount,
                    queueName(),
                    elapsedMillis(startedNanos)
            );
            return storedProfileCount;
        }
        EnumSet<AutoParticipantProfileType> inactiveProfiles = EnumSet.allOf(AutoParticipantProfileType.class);
        profiles.stream()
                .map(AutoMarketReadyProfileQueue.ReadyProfile::profileType)
                .forEach(inactiveProfiles::remove);
        int removedCount = readyProfileQueue.removeAll(inactiveProfiles);
        int enqueuedCount = readyProfileQueue.enqueueAll(profiles);
        log.info(
                "Auto market profile queue reconciled: profiles={}, enqueuedProfiles={}, removedProfiles={}, scheduledStrategies={}, queue={}, elapsedMs={}",
                profiles.size(),
                enqueuedCount,
                removedCount,
                scheduledCount,
                queueName(),
                elapsedMillis(startedNanos)
        );
        return enqueuedCount;
    }

    private int ensureParticipantSchedules(
            List<AutoMarketConfig> configs,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now
    ) {
        if (!autoMarketReader.hasMissingParticipantSchedules(configs)) {
            return 0;
        }
        Map<String, List<AutoParticipantStrategy>> enabledStrategiesBySymbol =
                autoMarketReader.findEnabledParticipantStrategiesBySymbol(configs);
        return transactionTemplate.execute(status -> autoParticipantOrderScheduleService.ensureSchedules(
                uniqueParticipantStrategies(enabledStrategiesBySymbol),
                profilePolicies,
                now
        ));
    }

    private List<AutoParticipantStrategy> uniqueParticipantStrategies(Map<String, List<AutoParticipantStrategy>> strategiesBySymbol) {
        Map<String, AutoParticipantStrategy> strategiesByUserKey = new LinkedHashMap<>();
        strategiesBySymbol.values()
                .stream()
                .flatMap(List::stream)
                .filter(strategy -> strategy.userKey() != null && !strategy.userKey().isBlank())
                .forEach(strategy -> strategiesByUserKey.putIfAbsent(strategy.userKey(), strategy));
        return List.copyOf(strategiesByUserKey.values());
    }

    private Map<AutoParticipantProfileType, LocalDateTime> toProfileScheduleMap(
            List<AutoMarketReadyProfileQueue.ReadyProfile> profiles
    ) {
        Map<AutoParticipantProfileType, LocalDateTime> schedules = new LinkedHashMap<>();
        for (AutoMarketReadyProfileQueue.ReadyProfile profile : profiles) {
            schedules.put(profile.profileType(), normalizeQueueTime(profile.readyAt()));
        }
        return schedules;
    }

    private LocalDateTime normalizeQueueTime(LocalDateTime value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MILLIS);
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private String queueName() {
        return readyProfileQueue.getClass().getSimpleName();
    }
}
