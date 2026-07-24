package stock.batch.service.automarket.biz;

import jakarta.annotation.PostConstruct;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
    private static final int MAX_MARKET_MAKER_REPLACEMENT_LIMIT_PER_SIDE = 100;
    private static final int MAX_MARKET_MAKER_REPRICE_THRESHOLD_TICKS = 100;
    private static final int MAX_MARKET_MAKER_MINIMUM_QUOTE_LIFETIME_SECONDS = 86_400;
    private static final int MAX_ACTIVE_V2_MARKET_MAKER_ACCOUNTS = 500;

    private final AutoMarketOrderReader autoMarketOrderReader;
    private final AutoMarketOrderExecutor autoMarketOrderExecutor;
    private final AutoProfileBehaviorSupport autoProfileBehaviorSupport;

    @Value("${stock.batch.auto-market-order-expiry.expiry-chunk-limit:100}")
    private int expiryChunkLimit;

    @Value("${stock.batch.auto-market-order-expiry.market-maker-replacement-limit-per-side:10}")
    private int marketMakerReplacementLimitPerSide;

    @Value("${stock.batch.auto-market-order-expiry.market-maker-reprice-threshold-ticks:2}")
    private int marketMakerRepriceThresholdTicks;

    @Value("${stock.batch.auto-market-order-expiry.market-maker-minimum-quote-lifetime-seconds:30}")
    private int marketMakerMinimumQuoteLifetimeSeconds;

    @PostConstruct
    void validateVolumeConfiguration() {
        if (expiryChunkLimit < 1 || expiryChunkLimit > MAX_EXPIRY_CHUNK_LIMIT) {
            throw new IllegalStateException(
                    "stock.batch.auto-market-order-expiry.expiry-chunk-limit must be between 1 and %d: %d"
                            .formatted(MAX_EXPIRY_CHUNK_LIMIT, expiryChunkLimit)
            );
        }
        if (marketMakerReplacementLimitPerSide < 1
                || marketMakerReplacementLimitPerSide > MAX_MARKET_MAKER_REPLACEMENT_LIMIT_PER_SIDE) {
            throw new IllegalStateException(
                    "stock.batch.auto-market-order-expiry.market-maker-replacement-limit-per-side "
                            + "must be between 1 and %d: %d"
                            .formatted(
                                    MAX_MARKET_MAKER_REPLACEMENT_LIMIT_PER_SIDE,
                                    marketMakerReplacementLimitPerSide
                            )
            );
        }
        if (marketMakerRepriceThresholdTicks < 1
                || marketMakerRepriceThresholdTicks > MAX_MARKET_MAKER_REPRICE_THRESHOLD_TICKS) {
            throw new IllegalStateException(
                    "stock.batch.auto-market-order-expiry.market-maker-reprice-threshold-ticks "
                            + "must be between 1 and %d: %d"
                            .formatted(MAX_MARKET_MAKER_REPRICE_THRESHOLD_TICKS, marketMakerRepriceThresholdTicks)
            );
        }
        if (marketMakerMinimumQuoteLifetimeSeconds < 1
                || marketMakerMinimumQuoteLifetimeSeconds > MAX_MARKET_MAKER_MINIMUM_QUOTE_LIFETIME_SECONDS) {
            throw new IllegalStateException(
                    "stock.batch.auto-market-order-expiry.market-maker-minimum-quote-lifetime-seconds "
                            + "must be between 1 and %d: %d"
                            .formatted(
                                    MAX_MARKET_MAKER_MINIMUM_QUOTE_LIFETIME_SECONDS,
                                    marketMakerMinimumQuoteLifetimeSeconds
                            )
            );
        }
    }

    ExpiryCandidatePlan planExpiryCandidates(
            AutoMarketConfig config,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now,
            List<Long> activeV2MarketMakerAccountIds
    ) {
        Map<AutoParticipantProfileType, LocalDateTime> thresholdsByProfile = expiryThresholdsByProfile(
                config,
                profilePolicies,
                now
        );
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
        int remainingCandidateCapacity = Math.max(0, candidateLimit - expiredCandidates.size());
        List<AutoOrder> marketMakerCandidates = findMarketMakerReplacementCandidates(
                config,
                activeV2MarketMakerAccountIds,
                now,
                remainingCandidateCapacity
        );
        return new ExpiryCandidatePlan(
                thresholdsByProfile,
                fallbackThreshold,
                candidateLimit,
                expiredCandidates,
                marketMakerCandidates
        );
    }

    List<Long> loadActiveV2MarketMakerAccountIds() {
        return autoMarketOrderReader.findActiveV2MarketMakerAccountIds(
                MAX_ACTIVE_V2_MARKET_MAKER_ACCOUNTS
        );
    }

    int expirePlannedOrders(
            AutoMarketConfig config,
            ExpiryCandidatePlan plan,
            LocalDateTime now
    ) {
        Map<Long, AutoOrder> candidatesById = new LinkedHashMap<>();
        for (AutoOrder order : plan.expiredCandidates()) {
            candidatesById.putIfAbsent(order.id(), order);
        }
        int remainingCandidateCapacity = Math.max(0, plan.candidateLimit() - candidatesById.size());
        if (remainingCandidateCapacity > 0 && !plan.marketMakerCandidates().isEmpty()) {
            AutoMarketOrderBookState orderBookState =
                    autoMarketOrderExecutor.loadOrderBookState(config.symbol());
            for (AutoOrder order : plan.marketMakerCandidates()) {
                if (candidatesById.size() >= plan.candidateLimit()) {
                    break;
                }
                if (requiresMarketMakerReprice(config, orderBookState, order)) {
                    candidatesById.putIfAbsent(order.id(), order);
                }
            }
        }
        List<AutoOrder> expiredOrders = new ArrayList<>();
        int marketMakerBuyReplacementCount = 0;
        int marketMakerSellReplacementCount = 0;
        for (AutoOrder order : candidatesById.values()) {
            if (order.expiresAt() != null) {
                if (order.profileType() == AutoParticipantProfileType.MARKET_MAKER
                        && order.behaviorModelVersion()
                        == stock.batch.service.batch.automarket.model.AutoParticipantBehaviorModelVersion.V2) {
                    if ("BUY".equals(order.side())) {
                        if (marketMakerBuyReplacementCount >= marketMakerReplacementLimitPerSide) {
                            continue;
                        }
                        marketMakerBuyReplacementCount++;
                    } else {
                        if (marketMakerSellReplacementCount >= marketMakerReplacementLimitPerSide) {
                            continue;
                        }
                        marketMakerSellReplacementCount++;
                    }
                }
                expiredOrders.add(order);
                continue;
            }
            LocalDateTime threshold = plan.thresholdsByProfile().getOrDefault(
                    order.profileType(),
                    plan.fallbackThreshold()
            );
            if (order.createdAt() != null && !order.createdAt().isBefore(threshold)) {
                continue;
            }
            expiredOrders.add(order);
        }
        return autoMarketOrderExecutor.expireOrders(expiredOrders, now);
    }

    record ExpiryCandidatePlan(
            Map<AutoParticipantProfileType, LocalDateTime> thresholdsByProfile,
            LocalDateTime fallbackThreshold,
            int candidateLimit,
            List<AutoOrder> expiredCandidates,
            List<AutoOrder> marketMakerCandidates
    ) {

        ExpiryCandidatePlan {
            thresholdsByProfile = Map.copyOf(thresholdsByProfile);
            expiredCandidates = List.copyOf(expiredCandidates);
            marketMakerCandidates = List.copyOf(marketMakerCandidates);
        }

        boolean hasWork() {
            return !expiredCandidates.isEmpty() || !marketMakerCandidates.isEmpty();
        }
    }

    private List<AutoOrder> findMarketMakerReplacementCandidates(
            AutoMarketConfig config,
            List<Long> activeV2MarketMakerAccountIds,
            LocalDateTime now,
            int remainingCandidateCapacity
    ) {
        if (remainingCandidateCapacity <= 0 || activeV2MarketMakerAccountIds.isEmpty()) {
            return List.of();
        }
        LocalDateTime createdBefore = now.minusSeconds(marketMakerMinimumQuoteLifetimeSeconds);
        int sideCandidateLimit = Math.min(
                marketMakerReplacementLimitPerSide,
                remainingCandidateCapacity
        );
        List<AutoOrder> buyCandidates = autoMarketOrderReader.findV2MarketMakerReplacementCandidates(
                config,
                activeV2MarketMakerAccountIds,
                createdBefore,
                "BUY",
                sideCandidateLimit
        );
        List<AutoOrder> sellCandidates = autoMarketOrderReader.findV2MarketMakerReplacementCandidates(
                config,
                activeV2MarketMakerAccountIds,
                createdBefore,
                "SELL",
                sideCandidateLimit
        );
        return interleaveMarketMakerCandidates(
                buyCandidates,
                sellCandidates,
                remainingCandidateCapacity
        );
    }

    private List<AutoOrder> interleaveMarketMakerCandidates(
            List<AutoOrder> buyCandidates,
            List<AutoOrder> sellCandidates,
            int limit
    ) {
        List<AutoOrder> candidates = new ArrayList<>(Math.min(
                limit,
                buyCandidates.size() + sellCandidates.size()
        ));
        int buyIndex = 0;
        int sellIndex = 0;
        while (candidates.size() < limit
                && (buyIndex < buyCandidates.size() || sellIndex < sellCandidates.size())) {
            if (buyIndex < buyCandidates.size()) {
                candidates.add(buyCandidates.get(buyIndex++));
            }
            if (candidates.size() < limit && sellIndex < sellCandidates.size()) {
                candidates.add(sellCandidates.get(sellIndex++));
            }
        }
        return candidates;
    }

    private boolean requiresMarketMakerReprice(
            AutoMarketConfig config,
            AutoMarketOrderBookState orderBookState,
            AutoOrder order
    ) {
        if (order.limitPrice() == null) {
            return false;
        }
        if ("BUY".equals(order.side()) && orderBookState.bestBid() != null) {
            BigDecimal minimumCompetitiveBid = AutoMarketPricePolicy.moveByTicks(
                    config.market(),
                    orderBookState.bestBid(),
                    -marketMakerRepriceThresholdTicks
            );
            return order.limitPrice().compareTo(minimumCompetitiveBid) < 0;
        }
        if ("SELL".equals(order.side()) && orderBookState.bestAsk() != null) {
            BigDecimal maximumCompetitiveAsk = AutoMarketPricePolicy.moveByTicks(
                    config.market(),
                    orderBookState.bestAsk(),
                    marketMakerRepriceThresholdTicks
            );
            return order.limitPrice().compareTo(maximumCompetitiveAsk) > 0;
        }
        return false;
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
                            ProfilePolicy policy = autoProfileBehaviorSupport.policy(profilePolicies, profileType);
                            AutoProfileBehavior behavior = autoProfileBehaviorSupport.behavior(profileType);
                            int projectTtlSeconds = behavior.orderTtlSeconds(config.orderTtlSeconds(), policy);
                            return now.minusSeconds(Math.max(1, projectTtlSeconds));
                        }
                ));
    }
}
