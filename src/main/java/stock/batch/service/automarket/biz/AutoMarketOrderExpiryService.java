package stock.batch.service.automarket.biz;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import stock.batch.service.automarket.profile.AutoProfileBehavior;
import stock.batch.service.automarket.profile.ProfilePolicy;
import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoOrder;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.reader.AutoMarketOrderReader;
import stock.batch.service.simulation.SimulationClockService;
import web.common.core.simulation.SimulationClockSnapshot;

@Component
@RequiredArgsConstructor
class AutoMarketOrderExpiryService {

    private static final int EXPIRED_AUTO_ORDER_LIMIT_PER_PROFILE = 200;

    private final AutoMarketOrderReader autoMarketOrderReader;
    private final AutoMarketOrderExecutor autoMarketOrderExecutor;
    private final AutoProfileBehaviorSupport autoProfileBehaviorSupport;
    private final SimulationClockService simulationClockService;

    int expireOldAutoOrders(
            AutoMarketConfig config,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies
    ) {
        int expired = 0;
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        LocalDateTime now = clock.simulationDateTime();
        Map<AutoParticipantProfileType, LocalDateTime> thresholdsByProfile = expiryThresholdsByProfile(config, profilePolicies, clock);
        LocalDateTime candidateThreshold = thresholdsByProfile.values().stream()
                .max(LocalDateTime::compareTo)
                .orElse(now);
        int candidateLimit = Math.max(
                EXPIRED_AUTO_ORDER_LIMIT_PER_PROFILE,
                thresholdsByProfile.size() * EXPIRED_AUTO_ORDER_LIMIT_PER_PROFILE
        );
        for (AutoOrder order : autoMarketOrderReader.findExpiredAutoOrders(config, candidateThreshold, candidateLimit)) {
            LocalDateTime threshold = thresholdsByProfile.getOrDefault(order.profileType(), candidateThreshold);
            if (order.createdAt() != null && !order.createdAt().isBefore(threshold)) {
                continue;
            }
            autoMarketOrderExecutor.expireOrder(order, now);
            expired++;
        }
        return expired;
    }

    private Map<AutoParticipantProfileType, LocalDateTime> expiryThresholdsByProfile(
            AutoMarketConfig config,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            SimulationClockSnapshot clock
    ) {
        return Arrays.stream(AutoParticipantProfileType.values())
                .collect(Collectors.toMap(
                        Function.identity(),
                        profileType -> {
                            ProfilePolicy policy = autoProfileBehaviorSupport.policy(profilePolicies, profileType);
                            AutoProfileBehavior behavior = autoProfileBehaviorSupport.behavior(profileType);
                            int projectTtlSeconds = behavior.orderTtlSeconds(config.orderTtlSeconds(), policy);
                            return clock.simulationDateTime().minusSeconds(Math.max(1, projectTtlSeconds));
                        }
                ));
    }
}
