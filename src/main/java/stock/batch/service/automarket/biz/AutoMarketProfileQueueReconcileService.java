package stock.batch.service.automarket.biz;

import java.time.LocalDateTime;
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

    private final AutoMarketReader autoMarketReader;
    private final AutoProfileBehaviorSupport autoProfileBehaviorSupport;
    private final AutoParticipantOrderScheduleService autoParticipantOrderScheduleService;
    private final AutoMarketReadyProfileQueue readyProfileQueue;
    private final SimulationClockService simulationClockService;
    private final TransactionTemplate transactionTemplate;

    @Value("${stock.batch.auto-market.profile-queue.reconcile-limit:100}")
    private int reconcileLimit;

    public int reconcileReadyProfiles() {
        long startedNanos = System.nanoTime();
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        LocalDateTime now = clock.simulationDateTime();
        List<AutoMarketConfig> configs = autoMarketReader.findEnabledConfigs();
        if (configs.isEmpty()) {
            log.info("Auto market profile queue reconcile skipped: enabledConfigs=0, queue={}", queueName());
            return 0;
        }
        Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies =
                autoProfileBehaviorSupport.policiesWithOverrides(autoMarketReader.findParticipantProfileConfigs());
        int scheduledCount = ensureParticipantSchedules(configs, profilePolicies, now);
        List<AutoMarketReadyProfileQueue.ReadyProfile> profiles =
                autoParticipantOrderScheduleService.findNextProfileSchedules(now, Math.max(1, reconcileLimit));
        int enqueuedCount = readyProfileQueue.enqueueAll(profiles);
        log.info(
                "Auto market profile queue reconciled: profiles={}, enqueuedProfiles={}, scheduledStrategies={}, queue={}, elapsedMs={}",
                profiles.size(),
                enqueuedCount,
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

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private String queueName() {
        return readyProfileQueue.getClass().getSimpleName();
    }
}
