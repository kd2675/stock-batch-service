package stock.batch.service.automarket.biz;

import jakarta.annotation.PostConstruct;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import stock.batch.service.automarket.profile.AutoProfileBehavior;
import stock.batch.service.automarket.profile.ProfilePolicy;
import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoOrder;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.reader.AutoMarketOrderReader;

@Component
@RequiredArgsConstructor
class AutoMarketOrderExpiryService {

    private static final int EXPIRED_AUTO_ORDER_LIMIT_PER_PROFILE = 200;
    private static final int MAX_EXPIRY_CHUNK_LIMIT = 1_000;

    private final AutoMarketOrderReader autoMarketOrderReader;
    private final AutoMarketOrderExecutor autoMarketOrderExecutor;
    private final AutoProfileBehaviorSupport autoProfileBehaviorSupport;

    @Value("${stock.batch.auto-market-order-expiry.expiry-chunk-limit:100}")
    private int expiryChunkLimit;

    @PostConstruct
    void validateVolumeConfiguration() {
        if (expiryChunkLimit < 1 || expiryChunkLimit > MAX_EXPIRY_CHUNK_LIMIT) {
            throw new IllegalStateException(
                    "stock.batch.auto-market-order-expiry.expiry-chunk-limit must be between 1 and %d: %d"
                            .formatted(MAX_EXPIRY_CHUNK_LIMIT, expiryChunkLimit)
            );
        }
    }

    ExpiryCandidatePlan planExpiryCandidates(
            AutoMarketConfig config,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now
    ) {
        Map<AutoParticipantProfileType, LocalDateTime> thresholdsByProfile =
                expiryThresholdsByProfile(config, profilePolicies, now);
        LocalDateTime fallbackThreshold = thresholdsByProfile.values().stream()
                .min(LocalDateTime::compareTo)
                .orElse(now);
        int candidateLimit = Math.max(1, Math.min(
                Math.max(
                        EXPIRED_AUTO_ORDER_LIMIT_PER_PROFILE,
                        thresholdsByProfile.size() * EXPIRED_AUTO_ORDER_LIMIT_PER_PROFILE
                ),
                expiryChunkLimit
        ));
        List<AutoOrder> expiredCandidates = autoMarketOrderReader.findExpiredAutoOrders(
                config,
                thresholdsByProfile,
                now,
                candidateLimit
        );
        List<AutoOrder> institutionCandidates =
                autoMarketOrderReader.findExpiredInstitutionOrders(
                        config.symbol(),
                        now,
                        candidateLimit
                );
        List<AutoOrder> combinedCandidates = new ArrayList<>(
                expiredCandidates.size() + institutionCandidates.size()
        );
        combinedCandidates.addAll(expiredCandidates);
        combinedCandidates.addAll(institutionCandidates);
        combinedCandidates.sort(
                Comparator.comparing(
                                AutoOrder::expiresAt,
                                Comparator.nullsLast(Comparator.naturalOrder())
                        )
                        .thenComparing(AutoOrder::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingLong(AutoOrder::id)
        );
        if (combinedCandidates.size() > candidateLimit) {
            combinedCandidates = new ArrayList<>(
                    combinedCandidates.subList(0, candidateLimit)
            );
        }
        return new ExpiryCandidatePlan(
                thresholdsByProfile,
                fallbackThreshold,
                candidateLimit,
                combinedCandidates
        );
    }

    int expirePlannedOrders(
            AutoMarketConfig config,
            ExpiryCandidatePlan plan,
            LocalDateTime now
    ) {
        List<AutoOrder> expiredOrders = plan.expiredCandidates().stream()
                .filter(order -> order.expiresAt() != null
                        || order.createdAt() != null
                        && order.createdAt().isBefore(
                                plan.thresholdsByProfile().getOrDefault(
                                        order.profileType(),
                                        plan.fallbackThreshold()
                                )
                        ))
                .toList();
        return autoMarketOrderExecutor.expireOrders(expiredOrders, now, "TTL_EXPIRED");
    }

    record ExpiryCandidatePlan(
            Map<AutoParticipantProfileType, LocalDateTime> thresholdsByProfile,
            LocalDateTime fallbackThreshold,
            int candidateLimit,
            List<AutoOrder> expiredCandidates
    ) {
        ExpiryCandidatePlan {
            thresholdsByProfile = Map.copyOf(thresholdsByProfile);
            expiredCandidates = List.copyOf(expiredCandidates);
        }

        boolean hasWork() {
            return !expiredCandidates.isEmpty();
        }
    }

    private Map<AutoParticipantProfileType, LocalDateTime> expiryThresholdsByProfile(
            AutoMarketConfig config,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now
    ) {
        return Arrays.stream(AutoParticipantProfileType.values())
                .collect(Collectors.toMap(
                        Function.identity(),
                        profileType -> {
                            ProfilePolicy policy =
                                    autoProfileBehaviorSupport.policy(profilePolicies, profileType);
                            AutoProfileBehavior behavior =
                                    autoProfileBehaviorSupport.behavior(profileType);
                            int ttlSeconds = behavior.orderTtlSeconds(
                                    config.orderTtlSeconds(),
                                    policy
                            );
                            return now.minusSeconds(Math.max(1, ttlSeconds));
                        }
                ));
    }
}
