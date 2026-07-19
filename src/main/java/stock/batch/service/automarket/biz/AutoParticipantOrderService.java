package stock.batch.service.automarket.biz;

import jakarta.annotation.PostConstruct;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import stock.batch.service.automarket.profile.AutoProfileBehavior;
import stock.batch.service.automarket.profile.ProfilePolicy;
import stock.batch.service.automarket.profile.ProfileSignalContext;
import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;
import stock.batch.service.batch.automarket.reader.AutoMarketReader;

import static stock.batch.service.automarket.biz.AutoMarketPricePolicy.dailyLowerLimit;
import static stock.batch.service.automarket.support.AutoMarketRandomSupport.nextInt;
import static stock.batch.service.automarket.support.AutoMarketRandomSupport.noise;

@Component
@RequiredArgsConstructor
class AutoParticipantOrderService {

    private static final String BUY = "BUY";
    private static final String SELL = "SELL";
    private static final Duration PROJECT_DIVIDEND_REINVESTMENT_SIGNAL_WINDOW = Duration.ofDays(7);
    private static final int MAX_OPEN_ORDER_QUANTITY_MULTIPLIER = 100;

    private final AutoMarketReader autoMarketReader;
    private final AutoMarketOrderExecutor autoMarketOrderExecutor;
    private final AutoParticipantOrderPricing autoParticipantOrderPricing;
    private final AutoProfileBehaviorSupport autoProfileBehaviorSupport;

    @Value("${stock.batch.auto-market.max-open-order-quantity-multiplier:10}")
    private int maxOpenOrderQuantityMultiplier = 10;

    @PostConstruct
    void validateVolumeConfiguration() {
        if (maxOpenOrderQuantityMultiplier < 1
                || maxOpenOrderQuantityMultiplier > MAX_OPEN_ORDER_QUANTITY_MULTIPLIER) {
            throw new IllegalStateException(
                    "stock.batch.auto-market.max-open-order-quantity-multiplier must be between 1 and %d: %d"
                            .formatted(MAX_OPEN_ORDER_QUANTITY_MULTIPLIER, maxOpenOrderQuantityMultiplier)
            );
        }
    }

    AutoParticipantOrderGenerationResult placeAutoOrders(
            List<AutoParticipantStrategy> strategies,
            AutoMarketConfig config,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            double momentumPressure,
            LocalDateTime businessEffectiveAt
    ) {
        Map<Long, AutoParticipantTradingState> tradingStates = loadTradingStates(strategies, config, businessEffectiveAt);
        AutoMarketOrderBookState priceReferenceOrderBookState =
                autoMarketOrderExecutor.loadOrderBookState(config.symbol());
        AutoMarketOrderBookState planningOrderBookState = priceReferenceOrderBookState;
        double initialOrderPressure = planningOrderBookState.orderPressure();
        boolean atLowerPriceLimit = isAtLowerPriceLimit(config);
        List<AutoMarketPlannedOrder> plannedOrders = new ArrayList<>();
        EnumMap<AutoMarketOrderDropReason, Integer> planningDropCounts = new EnumMap<>(AutoMarketOrderDropReason.class);
        int decisionCount = 0;
        for (AutoParticipantStrategy strategy : strategies) {
            AutoParticipantTradingState tradingState = tradingStates.getOrDefault(
                    strategy.accountId(),
                    AutoParticipantTradingState.empty(strategy.accountId())
            );
            AutoProfileBehavior behavior = autoProfileBehaviorSupport.behavior(strategy.profileType());
            ProfilePolicy policy = autoProfileBehaviorSupport.policy(profilePolicies, strategy.profileType());
            int effectiveIntensity = behavior.effectiveIntensity(strategy, config, policy);
            double unrealizedReturn = unrealizedReturn(tradingState, config);
            double profileNoise = noise(policy.noiseWeight(), 0.18);
            ProfileSignalContext initialContext = profileContext(
                    strategy,
                    config,
                    policy,
                    effectiveIntensity,
                    momentumPressure,
                    unrealizedReturn,
                    0,
                    tradingState,
                    planningOrderBookState,
                    profileNoise,
                    atLowerPriceLimit
            );
            int orderCount = scaledOrderCount(behavior.orderCount(initialContext), config);
            decisionCount += orderCount;
            for (int index = 0; index < orderCount; index++) {
                ProfileSignalContext context = profileContext(
                        strategy,
                        config,
                        policy,
                        effectiveIntensity,
                        momentumPressure,
                        unrealizedReturn,
                        index,
                        tradingState,
                        planningOrderBookState,
                        profileNoise,
                        atLowerPriceLimit
                );
                String side = behavior.chooseSide(context);
                if (side == null) {
                    incrementDropCount(planningDropCounts, AutoMarketOrderDropReason.SIDE_NOT_SELECTED);
                    continue;
                }
                BigDecimal price = autoParticipantOrderPricing.createAutoPrice(
                        config,
                        effectiveIntensity,
                        side,
                        policy,
                        priceReferenceOrderBookState
                );
                if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                    incrementDropCount(planningDropCounts, AutoMarketOrderDropReason.INVALID_PRICE);
                    continue;
                }
                QuantityDecision quantityDecision = createQuantity(
                        tradingState,
                        side,
                        price,
                        policy,
                        behavior,
                        config.maxOrderQuantity()
                );
                if (!quantityDecision.accepted()) {
                    incrementDropCount(planningDropCounts, quantityDecision.dropReason());
                    continue;
                }
                long quantity = adjustQuantityForOrderPressure(side, quantityDecision.quantity(), initialOrderPressure);
                plannedOrders.add(new AutoMarketPlannedOrder(strategy.accountId(), config.symbol(), side, price, quantity));
                planningOrderBookState = planningOrderBookState.withPlacedOrder(side, price, quantity);
                tradingState.reserve(side, price, quantity);
            }
        }
        return autoMarketOrderExecutor.placeOrders(plannedOrders)
                .withPlanning(decisionCount, planningDropCounts);
    }

    private Map<Long, AutoParticipantTradingState> loadTradingStates(
            List<AutoParticipantStrategy> strategies,
            AutoMarketConfig config,
            LocalDateTime businessEffectiveAt
    ) {
        if (strategies.isEmpty()) {
            return Map.of();
        }
        LocalDateTime recentDividendSince = businessEffectiveAt.minus(PROJECT_DIVIDEND_REINVESTMENT_SIGNAL_WINDOW);
        return autoMarketReader.findTradingSnapshots(
                        strategies.stream()
                                .map(AutoParticipantStrategy::accountId)
                                .distinct()
                                .toList(),
                        config.symbol(),
                        recentDividendSince
                ).stream()
                .map(AutoParticipantTradingState::from)
                .collect(Collectors.toMap(
                        AutoParticipantTradingState::accountId,
                        Function.identity(),
                        (left, right) -> left
                ));
    }

    private ProfileSignalContext profileContext(
            AutoParticipantStrategy strategy,
            AutoMarketConfig config,
            ProfilePolicy policy,
            int effectiveIntensity,
            double momentumPressure,
            double unrealizedReturn,
            int orderIndex,
            AutoParticipantTradingState tradingState,
            AutoMarketOrderBookState orderBookState,
            double profileNoise,
            boolean atLowerPriceLimit
    ) {
        return new ProfileSignalContext(
                strategy,
                config,
                policy,
                effectiveIntensity,
                momentumPressure,
                orderBookState.orderPressure(),
                unrealizedReturn,
                tradingState.availableQuantity(),
                tradingState.cashBalance(),
                tradingState.recentDividendCashAmount(),
                atLowerPriceLimit,
                orderIndex,
                profileNoise
        );
    }

    private QuantityDecision createQuantity(
            AutoParticipantTradingState tradingState,
            String side,
            BigDecimal price,
            ProfilePolicy policy,
            AutoProfileBehavior behavior,
            int maxOrderQuantity
    ) {
        int maxQuantity = Math.max(1, maxOrderQuantity);
        long maxOpenQuantity = (long) maxQuantity * Math.max(1, maxOpenOrderQuantityMultiplier);
        long remainingOpenCapacity = tradingState.remainingOpenCapacity(side, maxOpenQuantity);
        if (remainingOpenCapacity <= 0) {
            return QuantityDecision.dropped(AutoMarketOrderDropReason.OPEN_QUANTITY_LIMIT);
        }
        long profileQuantity;
        if (policy.quantityMultiplier() >= 1.5 && maxQuantity > 1) {
            profileQuantity = nextInt(Math.max(1, maxQuantity / 2), behavior.quantityUpperBound(maxQuantity, policy));
        } else {
            int upperBound = behavior.quantityUpperBound(maxQuantity, policy);
            profileQuantity = nextInt(1, upperBound);
        }
        if (BUY.equals(side)) {
            if (price.compareTo(BigDecimal.ZERO) <= 0) {
                return QuantityDecision.dropped(AutoMarketOrderDropReason.INVALID_PRICE);
            }
            long affordableQuantity = tradingState.cashBalance()
                    .divide(price, 0, RoundingMode.DOWN)
                    .longValue();
            if (affordableQuantity <= 0) {
                return QuantityDecision.dropped(AutoMarketOrderDropReason.INSUFFICIENT_CASH);
            }
            return QuantityDecision.accepted(Math.min(Math.min(profileQuantity, affordableQuantity), remainingOpenCapacity));
        }
        if (tradingState.availableQuantity() <= 0) {
            return QuantityDecision.dropped(AutoMarketOrderDropReason.INSUFFICIENT_HOLDING);
        }
        return QuantityDecision.accepted(
                Math.min(Math.min(profileQuantity, tradingState.availableQuantity()), remainingOpenCapacity)
        );
    }

    private void incrementDropCount(
            Map<AutoMarketOrderDropReason, Integer> dropCounts,
            AutoMarketOrderDropReason reason
    ) {
        dropCounts.merge(reason, 1, Integer::sum);
    }

    private long adjustQuantityForOrderPressure(String side, long quantity, double orderPressure) {
        if (quantity <= 1) {
            return quantity;
        }
        boolean addsToDominantSide = orderPressure >= 0.85 && BUY.equals(side)
                || orderPressure <= -0.85 && SELL.equals(side);
        return addsToDominantSide ? Math.max(1L, quantity / 4L) : quantity;
    }

    private int scaledOrderCount(int baseOrderCount, AutoMarketConfig config) {
        if (baseOrderCount <= 0) {
            return 0;
        }
        return Math.clamp((int) Math.round(baseOrderCount * config.liquidityMultiplier()), 1, 8);
    }

    private boolean isAtLowerPriceLimit(AutoMarketConfig config) {
        return config.currentPrice().compareTo(dailyLowerLimit(config)) <= 0;
    }

    private double unrealizedReturn(AutoParticipantTradingState tradingState, AutoMarketConfig config) {
        BigDecimal averagePrice = tradingState.averagePrice();
        if (averagePrice.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        return config.currentPrice()
                .subtract(averagePrice)
                .divide(averagePrice, 6, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private record QuantityDecision(long quantity, AutoMarketOrderDropReason dropReason) {

        private static QuantityDecision accepted(long quantity) {
            return new QuantityDecision(quantity, null);
        }

        private static QuantityDecision dropped(AutoMarketOrderDropReason dropReason) {
            return new QuantityDecision(0, dropReason);
        }

        private boolean accepted() {
            return quantity > 0 && dropReason == null;
        }
    }

}
