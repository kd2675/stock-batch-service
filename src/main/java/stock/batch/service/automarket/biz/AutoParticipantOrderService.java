package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import stock.batch.service.automarket.profile.AutoProfileBehavior;
import stock.batch.service.automarket.profile.ProfilePolicy;
import stock.batch.service.automarket.profile.ProfileSignalContext;
import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;
import stock.batch.service.batch.automarket.reader.AutoMarketReader;
import stock.batch.service.simulation.SimulationClockService;
import web.common.core.simulation.SimulationClockSnapshot;

import static stock.batch.service.automarket.biz.AutoMarketPricePolicy.dailyLowerLimit;
import static stock.batch.service.automarket.support.AutoMarketRandomSupport.nextInt;
import static stock.batch.service.automarket.support.AutoMarketRandomSupport.noise;

@Component
@RequiredArgsConstructor
class AutoParticipantOrderService {

    private static final String BUY = "BUY";
    private static final String SELL = "SELL";
    private static final Duration PROJECT_DIVIDEND_REINVESTMENT_SIGNAL_WINDOW = Duration.ofDays(7);

    private final AutoMarketReader autoMarketReader;
    private final AutoMarketOrderExecutor autoMarketOrderExecutor;
    private final AutoParticipantOrderPricing autoParticipantOrderPricing;
    private final AutoProfileBehaviorSupport autoProfileBehaviorSupport;
    private final SimulationClockService simulationClockService;

    AutoParticipantOrderGenerationResult placeAutoOrders(
            List<AutoParticipantStrategy> strategies,
            AutoMarketConfig config,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            double momentumPressure
    ) {
        int placed = 0;
        int reservedBuy = 0;
        int reservedSell = 0;
        int failedReserve = 0;
        Map<Long, AutoParticipantTradingState> tradingStates = loadTradingStates(strategies, config);
        AutoMarketOrderBookState orderBookState = autoMarketOrderExecutor.loadOrderBookState(config.symbol());
        for (AutoParticipantStrategy strategy : strategies) {
            AutoParticipantTradingState tradingState = tradingStates.getOrDefault(
                    strategy.accountId(),
                    AutoParticipantTradingState.empty(strategy.accountId())
            );
            AutoProfileBehavior behavior = autoProfileBehaviorSupport.behavior(strategy.profileType());
            ProfilePolicy policy = autoProfileBehaviorSupport.policy(profilePolicies, strategy.profileType());
            int effectiveIntensity = behavior.effectiveIntensity(strategy, config, policy);
            double unrealizedReturn = unrealizedReturn(tradingState, config);
            ProfileSignalContext baseContext = profileContext(
                    strategy,
                    config,
                    policy,
                    effectiveIntensity,
                    momentumPressure,
                    unrealizedReturn,
                    0,
                    tradingState,
                    orderBookState
            );
            int orderCount = behavior.orderCount(baseContext);
            for (int index = 0; index < orderCount; index++) {
                ProfileSignalContext context = baseContext.withOrderIndex(index);
                String side = behavior.chooseSide(context);
                if (side == null) {
                    continue;
                }
                BigDecimal price = autoParticipantOrderPricing.createAutoPrice(config, effectiveIntensity, side, policy, orderBookState);
                long quantity = createQuantity(tradingState, side, price, policy, behavior, config.maxOrderQuantity());
                if (quantity <= 0) {
                    continue;
                }
                if (!autoMarketOrderExecutor.placeOrder(strategy.accountId(), config.symbol(), side, price, quantity)) {
                    failedReserve++;
                    continue;
                }
                orderBookState = orderBookState.withPlacedOrder(side, price, quantity);
                tradingState.reserve(side, price, quantity);
                placed++;
                if (BUY.equals(side)) {
                    reservedBuy++;
                } else {
                    reservedSell++;
                }
            }
        }
        return new AutoParticipantOrderGenerationResult(placed, reservedBuy, reservedSell, failedReserve);
    }

    private Map<Long, AutoParticipantTradingState> loadTradingStates(
            List<AutoParticipantStrategy> strategies,
            AutoMarketConfig config
    ) {
        if (strategies.isEmpty()) {
            return Map.of();
        }
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        var recentDividendSince = clock.simulationDateTime().minus(PROJECT_DIVIDEND_REINVESTMENT_SIGNAL_WINDOW);
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
            AutoMarketOrderBookState orderBookState
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
                isAtLowerPriceLimit(config),
                orderIndex,
                noise(policy.noiseWeight(), 0.18)
        );
    }

    private long createQuantity(
            AutoParticipantTradingState tradingState,
            String side,
            BigDecimal price,
            ProfilePolicy policy,
            AutoProfileBehavior behavior,
            int maxOrderQuantity
    ) {
        int maxQuantity = Math.max(1, maxOrderQuantity);
        long profileQuantity;
        if (policy.quantityMultiplier() >= 1.5 && maxQuantity > 1) {
            profileQuantity = nextInt(Math.max(1, maxQuantity / 2), behavior.quantityUpperBound(maxQuantity, policy));
        } else {
            int upperBound = behavior.quantityUpperBound(maxQuantity, policy);
            profileQuantity = nextInt(1, upperBound);
        }
        if (BUY.equals(side)) {
            if (price.compareTo(BigDecimal.ZERO) <= 0) {
                return 0;
            }
            long affordableQuantity = tradingState.cashBalance()
                    .divide(price, 0, RoundingMode.DOWN)
                    .longValue();
            return Math.min(profileQuantity, affordableQuantity);
        }
        return Math.min(profileQuantity, tradingState.availableQuantity());
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

}
