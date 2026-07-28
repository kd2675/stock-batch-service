package stock.batch.service.automarket.biz;

import jakarta.annotation.PostConstruct;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import stock.batch.service.automarket.profile.AutoProfileBehavior;
import stock.batch.service.automarket.profile.BehavioralMemory;
import stock.batch.service.automarket.profile.FundingBudgetSnapshot;
import stock.batch.service.automarket.profile.ProfilePolicy;
import stock.batch.service.automarket.profile.ParticipantPortfolioSnapshot;
import stock.batch.service.automarket.profile.MarketSignalSnapshot;
import stock.batch.service.automarket.profile.ProfileDecision;
import stock.batch.service.automarket.profile.ProfileDecisionAction;
import stock.batch.service.automarket.profile.ProfileDecisionReason;
import stock.batch.service.automarket.profile.ProfileSignalContext;
import stock.batch.service.automarket.support.AutoMarketDeterministicRandom;
import stock.batch.service.automarket.v3.AutoParticipantDecisionUrgency;
import stock.batch.service.automarket.v3.AutoParticipantBehaviorKernel;
import stock.batch.service.automarket.v3.AutoParticipantV3Policy;
import stock.batch.service.automarket.v3.SafeQuantityBindingReason;
import stock.batch.service.automarket.v3.SafeQuantityCalculator;
import stock.batch.service.automarket.v3.SafeQuantityLimit;
import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoMarketHistoricalSignal;
import stock.batch.service.batch.automarket.model.AutoParticipantBehaviorModelVersion;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;
import stock.batch.service.batch.automarket.model.AutoParticipantFundingBudgetType;
import stock.batch.service.batch.automarket.model.AutoParticipantTradingSnapshot;
import stock.batch.service.batch.automarket.reader.AutoMarketReader;

import static stock.batch.service.automarket.biz.AutoMarketPricePolicy.dailyLowerLimit;
import static stock.batch.service.automarket.support.AutoMarketRandomSupport.withSeed;

@Component
class AutoParticipantOrderService {

    private static final String BUY = "BUY";
    private static final String SELL = "SELL";
    private static final Duration PROJECT_DIVIDEND_REINVESTMENT_SIGNAL_WINDOW = Duration.ofDays(7);
    private static final int MAX_OPEN_ORDER_QUANTITY_MULTIPLIER = 100;

    private final AutoMarketReader autoMarketReader;
    private final AutoMarketOrderExecutor autoMarketOrderExecutor;
    private final AutoParticipantOrderPricing autoParticipantOrderPricing;
    private final AutoProfileBehaviorSupport autoProfileBehaviorSupport;
    private final AutoMarketExecutionStylePlanner executionStylePlanner = new AutoMarketExecutionStylePlanner();
    private final AutoParticipantBehaviorKernel behaviorKernel = new AutoParticipantBehaviorKernel();
    private final SafeQuantityCalculator safeQuantityCalculator = new SafeQuantityCalculator();
    private MeterRegistry meterRegistry;
    private RecentMarketActivityTracker recentMarketActivityTracker;
    private AutoParticipantPositionActivityTracker positionActivityTracker;
    private LocalTime marketCloseTime = LocalTime.of(18, 0);

    @Autowired(required = false)
    void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Autowired(required = false)
    void setRecentMarketActivityTracker(RecentMarketActivityTracker recentMarketActivityTracker) {
        this.recentMarketActivityTracker = recentMarketActivityTracker;
    }

    @Autowired(required = false)
    void setPositionActivityTracker(AutoParticipantPositionActivityTracker positionActivityTracker) {
        this.positionActivityTracker = positionActivityTracker;
    }

    @Value("${stock.market-session.close-time:18:00}")
    void setMarketCloseTime(String marketCloseTimeValue) {
        this.marketCloseTime = LocalTime.parse(marketCloseTimeValue);
    }

    @Value("${stock.batch.auto-market.max-open-order-quantity-multiplier:10}")
    private int maxOpenOrderQuantityMultiplier = 10;

    @Value("${stock.batch.execution.fee-rate:0.00015}")
    private BigDecimal buyFeeRate = new BigDecimal("0.00015");

    AutoParticipantOrderService(
            AutoMarketReader autoMarketReader,
            AutoMarketOrderExecutor autoMarketOrderExecutor,
            AutoParticipantOrderPricing autoParticipantOrderPricing,
            AutoProfileBehaviorSupport autoProfileBehaviorSupport
    ) {
        this.autoMarketReader = autoMarketReader;
        this.autoMarketOrderExecutor = autoMarketOrderExecutor;
        this.autoParticipantOrderPricing = autoParticipantOrderPricing;
        this.autoProfileBehaviorSupport = autoProfileBehaviorSupport;
    }

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
        return placeAutoOrders(
                strategies,
                config,
                profilePolicies,
                momentumPressure,
                momentumPressure,
                AutoMarketHistoricalSignal.EMPTY,
                businessEffectiveAt,
                Map.of()
        );
    }

    AutoParticipantOrderGenerationResult placeAutoOrders(
            List<AutoParticipantStrategy> strategies,
            AutoMarketConfig config,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            double momentumPressure,
            double shortMomentumPressure,
            AutoMarketHistoricalSignal historicalSignal,
            LocalDateTime businessEffectiveAt
    ) {
        return placeAutoOrders(
                strategies,
                config,
                profilePolicies,
                momentumPressure,
                shortMomentumPressure,
                historicalSignal,
                businessEffectiveAt,
                Map.of()
        );
    }

    AutoParticipantOrderGenerationResult placeAutoOrders(
            List<AutoParticipantStrategy> strategies,
            AutoMarketConfig config,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            double momentumPressure,
            double shortMomentumPressure,
            AutoMarketHistoricalSignal historicalSignal,
            LocalDateTime businessEffectiveAt,
            Map<String, Integer> eligibleSymbolCountsByUserKey
    ) {
        Map<Long, AutoParticipantTradingState> tradingStates = loadTradingStates(strategies, config, businessEffectiveAt);
        registerIntradayPositionClocks(strategies, tradingStates, config.symbol(), businessEffectiveAt);
        AutoMarketOrderBookState priceReferenceOrderBookState =
                autoMarketOrderExecutor.loadOrderBookState(config.symbol());
        AutoMarketOrderBookState planningOrderBookState = priceReferenceOrderBookState;
        RecentMarketActivityTracker.RecentMarketActivitySnapshot recentMarketActivity =
                recentMarketActivity(config.symbol(), businessEffectiveAt);
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
            ProfilePolicy executionPolicy = policy;
            String v3PolicyVersion = v3PolicyVersion(strategy, policy);
            int activityLevel = behavior.activityLevel(strategy);
            double unrealizedReturn = unrealizedReturn(tradingState, config);
            double profileNoise = AutoMarketDeterministicRandom.symmetricNoise(
                    strategy,
                    config.symbol(),
                    businessEffectiveAt,
                    Math.max(0.0, policy.noiseWeight()) * 0.18,
                    v3PolicyVersion
            );
            ProfileSignalContext initialContext = profileContext(
                    strategy,
                    config,
                    executionPolicy,
                    activityLevel,
                    momentumPressure,
                    unrealizedReturn,
                    0,
                    tradingState,
                    planningOrderBookState,
                    profileNoise,
                    atLowerPriceLimit,
                    historicalSignal,
                    shortMomentumPressure,
                    businessEffectiveAt,
                    recentMarketActivity,
                    eligibleSymbolCount(strategy, eligibleSymbolCountsByUserKey)
            );
            ProfileDecision v3Decision = decideV3(
                    strategy,
                    config.symbol(),
                    businessEffectiveAt,
                    behavior,
                    policy,
                    v3PolicyVersion,
                    initialContext
            );
            recordProfileDecision(strategy, v3Decision);
            AutoParticipantDecisionUrgency decisionUrgency = decisionUrgency(
                    strategy.profileType(),
                    v3Decision
            );
            AutoParticipantV3Policy activeV3Policy = strategy.v3Policy() == null
                    ? AutoParticipantV3Policy.defaults(Math.max(1L, strategy.policyVersion()))
                    : strategy.v3Policy();
            if (v3Decision.action() != ProfileDecisionAction.HOLD
                    && decisionUrgency == AutoParticipantDecisionUrgency.VOLUNTARY
                    && !shouldExecuteVoluntaryOrder(
                            strategy,
                            v3Decision,
                            profileNoise,
                            businessEffectiveAt,
                            activeV3Policy
                    )) {
                incrementDropCount(
                        planningDropCounts,
                        AutoMarketOrderDropReason.FOLLOW_THROUGH_SKIPPED
                );
                decisionCount++;
                continue;
            }
            int orderTtlSeconds = behavior.orderTtlSeconds(config.orderTtlSeconds(), executionPolicy);
            LocalDateTime orderExpiresAt = businessEffectiveAt.plusSeconds(Math.max(1, orderTtlSeconds));
            int orderCount = v3Decision.action() == ProfileDecisionAction.HOLD ? 0 : 1;
            decisionCount++;
            for (int index = 0; index < orderCount; index++) {
                ProfileSignalContext context = profileContext(
                        strategy,
                        config,
                        executionPolicy,
                        activityLevel,
                        momentumPressure,
                        unrealizedReturn,
                        index,
                        tradingState,
                        planningOrderBookState,
                        profileNoise,
                        atLowerPriceLimit,
                        historicalSignal,
                        shortMomentumPressure,
                        businessEffectiveAt,
                        recentMarketActivity,
                        eligibleSymbolCount(strategy, eligibleSymbolCountsByUserKey)
                );
                AutoMarketExecutionIntent v3Intent = executionStylePlanner.intentFor(
                        strategy.profileType(),
                        context,
                        v3Decision,
                        index,
                        orderCount
                );
                String side = v3Intent.action() == ProfileDecisionAction.BUY ? BUY : SELL;
                if (side == null) {
                    incrementDropCount(planningDropCounts, AutoMarketOrderDropReason.SIDE_NOT_SELECTED);
                    continue;
                }
                AutoMarketOrderDropReason infeasibleReason = infeasibleIntentReason(context, side);
                if (infeasibleReason != null) {
                    incrementDropCount(planningDropCounts, infeasibleReason);
                    continue;
                }
                BigDecimal price = withSeed(
                                AutoMarketDeterministicRandom.seed(
                                        strategy,
                                        config.symbol(),
                                        businessEffectiveAt,
                                        v3PolicyVersion + ":PRICE:" + index
                                ),
                                () -> autoParticipantOrderPricing.createAutoPrice(
                                        config,
                                        activityLevel,
                                        side,
                                        executionPolicy,
                                        priceReferenceOrderBookState,
                                        v3Intent
                                )
                        );
                if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                    incrementDropCount(planningDropCounts, AutoMarketOrderDropReason.INVALID_PRICE);
                    continue;
                }
                QuantityDecision quantityDecision = createQuantity(
                        strategy,
                        tradingState,
                        context,
                        priceReferenceOrderBookState,
                        side,
                        price,
                        config.maxOrderQuantity(),
                        v3Intent.requestedQuantity(),
                        fundingBudgetTypeForIntent(strategy, side),
                        decisionUrgency,
                        activeV3Policy
                );
                if (!quantityDecision.accepted()) {
                    incrementDropCount(planningDropCounts, quantityDecision.dropReason());
                    continue;
                }
                long profileRiskQuantity = applyProfileRiskQuantity(
                        strategy.profileType(),
                        context,
                        v3Decision,
                        side,
                        price,
                        quantityDecision.quantity()
                );
                if (profileRiskQuantity <= 0) {
                    incrementDropCount(planningDropCounts, AutoMarketOrderDropReason.PROFILE_RISK_LIMIT);
                    continue;
                }
                long quantity = adjustQuantityForOrderPressure(
                        side,
                        profileRiskQuantity,
                        initialOrderPressure,
                        v3Decision.reason(),
                        v3Decision.signalStrength(),
                        strategy.profileType()
                );
                AutoParticipantFundingBudgetType fundingBudgetType = fundingBudgetType(strategy, side);
                if (fundingBudgetType != null) {
                    BigDecimal availableBudget = fundingBudgetType == AutoParticipantFundingBudgetType.PAYDAY
                            ? tradingState.paydayAvailableBudget()
                            : tradingState.dividendAvailableBudget();
                    long budgetQuantity = availableBudget.divide(price, 0, RoundingMode.DOWN).longValue();
                    quantity = Math.min(quantity, budgetQuantity);
                    if (quantity <= 0) {
                        incrementDropCount(planningDropCounts, AutoMarketOrderDropReason.FUNDING_BUDGET_EMPTY);
                        continue;
                    }
                }
                plannedOrders.add(new AutoMarketPlannedOrder(
                        strategy.accountId(),
                        config.symbol(),
                        side,
                        price,
                        quantity,
                        fundingBudgetType,
                        v3Decision.reason(),
                        orderExpiresAt,
                        strategy.profileType(),
                        strategy.behaviorModelVersion(),
                        stock.batch.service.batch.automarket.model.StockOrderOriginType.AUTO_PARTICIPANT,
                        null,
                        activeV3Policy.policyVersion(),
                        strategy.behaviorEventSequence(),
                        decisionUrgency
                ));
                planningOrderBookState = planningOrderBookState.withPlacedOrder(side, price, quantity);
                tradingState.reserve(side, price, quantity);
                tradingState.reserveFundingBudget(fundingBudgetType, price.multiply(BigDecimal.valueOf(quantity)));
            }
        }
        return autoMarketOrderExecutor.placeOrders(plannedOrders)
                .withPlanning(decisionCount, planningDropCounts);
    }

    private int eligibleSymbolCount(
            AutoParticipantStrategy strategy,
            Map<String, Integer> eligibleSymbolCountsByUserKey
    ) {
        if (strategy == null || strategy.userKey() == null || eligibleSymbolCountsByUserKey == null) {
            return 1;
        }
        return Math.max(1, eligibleSymbolCountsByUserKey.getOrDefault(strategy.userKey(), 1));
    }

    private void recordProfileDecision(AutoParticipantStrategy strategy, ProfileDecision decision) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter(
                "stock.auto.market.profile.decisions",
                "profile", strategy.profileType().name(),
                "model", strategy.behaviorModelVersion().name(),
                "action", decision.action().name(),
                "reason", decision.reason().name()
        ).increment();
    }

    private ProfileDecision decideV3(
            AutoParticipantStrategy strategy,
            String symbol,
            LocalDateTime businessEffectiveAt,
            AutoProfileBehavior behavior,
            ProfilePolicy policy,
            String policyVersion,
            ProfileSignalContext context
    ) {
        return withSeed(
                AutoMarketDeterministicRandom.seed(strategy, symbol, businessEffectiveAt, policyVersion),
                () -> behavior.decide(context)
        );
    }

    private String v3PolicyVersion(AutoParticipantStrategy strategy, ProfilePolicy policy) {
        return "V3:" + Math.max(1L, strategy.policyVersion()) + ":"
                + strategy.profileType().name() + ":" + policy.behaviorSeedVersion();
    }

    private AutoParticipantDecisionUrgency decisionUrgency(
            AutoParticipantProfileType profileType,
            ProfileDecision decision
    ) {
        if (decision.reason() == ProfileDecisionReason.SESSION_CLOSE) {
            return AutoParticipantDecisionUrgency.MANDATORY_CLOSE;
        }
        if (decision.reason() == ProfileDecisionReason.HOLDING_PERIOD
                || decision.reason() == ProfileDecisionReason.RISK_LIMIT
                || decision.reason() == ProfileDecisionReason.EXIT_THRESHOLD
                && profileType == AutoParticipantProfileType.STOP_LOSS_TRADER) {
            return AutoParticipantDecisionUrgency.RISK_REDUCTION;
        }
        return AutoParticipantDecisionUrgency.VOLUNTARY;
    }

    private boolean shouldExecuteVoluntaryOrder(
            AutoParticipantStrategy strategy,
            ProfileDecision decision,
            double independentNoise,
            LocalDateTime businessEffectiveAt,
            AutoParticipantV3Policy policy
    ) {
        LocalDateTime lastOrderAt = strategy.lastOrderAt();
        long elapsedSeconds = lastOrderAt == null
                ? 86_400L
                : Math.max(0L, Duration.between(lastOrderAt, businessEffectiveAt).toSeconds());
        return behaviorKernel.shouldExecuteVoluntaryOrder(
                decision.signalStrength(),
                Math.clamp(independentNoise * 5.0, -1.0, 1.0),
                strategy.fatigueScore(),
                behaviorKernel.reentryFactor(elapsedSeconds, policy),
                strategy.activityState(),
                strategy.behaviorSeed(),
                businessEffectiveAt.toLocalDate(),
                strategy.behaviorEventSequence(),
                policy
        );
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
        List<Long> accountIds = strategies.stream()
                .map(AutoParticipantStrategy::accountId)
                .distinct()
                .sorted()
                .toList();
        List<AutoParticipantTradingSnapshot> snapshots = autoMarketReader.findTradingSnapshots(
                accountIds,
                config.symbol(),
                recentDividendSince,
                businessEffectiveAt.toLocalDate()
        );
        return snapshots.stream()
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
            int activityLevel,
            double momentumPressure,
            double unrealizedReturn,
            int orderIndex,
            AutoParticipantTradingState tradingState,
            AutoMarketOrderBookState orderBookState,
            double profileNoise,
            boolean atLowerPriceLimit,
            AutoMarketHistoricalSignal historicalSignal,
            double shortMomentumPressure,
            LocalDateTime businessEffectiveAt,
            RecentMarketActivityTracker.RecentMarketActivitySnapshot recentMarketActivity,
            int eligibleSymbolCount
    ) {
        AutoParticipantPositionActivityTracker.PositionAgeSnapshot positionAge =
                intradayPositionAge(
                                strategy.accountId(),
                                config.symbol(),
                                businessEffectiveAt
                        );
        return new ProfileSignalContext(
                strategy,
                config,
                policy,
                activityLevel,
                momentumPressure,
                orderBookState.orderPressure(),
                unrealizedReturn,
                tradingState.availableQuantity(),
                tradingState.cashBalance(),
                tradingState.recentDividendCashAmount(),
                atLowerPriceLimit,
                orderIndex,
                profileNoise,
                new ParticipantPortfolioSnapshot(
                        tradingState.holdingQuantity(),
                        tradingState.reservedQuantity(),
                        tradingState.openBuyReservedCash(),
                        tradingState.portfolioHoldingMarketValue(),
                        tradingState.liquidPortfolioAsset(),
                        tradingState.portfolioPositionCount(),
                        tradingState.openBuyQuantity(),
                        eligibleSymbolCount
                ),
                marketSignalSnapshot(
                        config,
                        orderBookState,
                        historicalSignal,
                        shortMomentumPressure,
                        momentumPressure,
                        businessEffectiveAt,
                        recentMarketActivity
                ),
                new FundingBudgetSnapshot(
                        tradingState.paydayAvailableBudget(),
                        tradingState.dividendAvailableBudget()
                ),
                new BehavioralMemory(
                        tradingState.positionOpenedBusinessDate(),
                        tradingState.holdingTradingDays(),
                        tradingState.averageDownRounds(),
                        tradingState.lastAverageDownBusinessDate(),
                        tradingState.peakClosePrice(),
                        tradingState.recentProfitableTradingDays(),
                        tradingState.recentClosedTradingDays(),
                        positionAge.ageSeconds(),
                        positionAge.available()
                )
        );
    }

    private void registerIntradayPositionClocks(
            List<AutoParticipantStrategy> strategies,
            Map<Long, AutoParticipantTradingState> tradingStates,
            String symbol,
            LocalDateTime businessEffectiveAt
    ) {
        if (positionActivityTracker == null) {
            return;
        }
        for (AutoParticipantStrategy strategy : strategies) {
            AutoParticipantTradingState state = tradingStates.get(strategy.accountId());
            positionActivityTracker.register(
                    strategy.accountId(),
                    symbol,
                    state == null ? 0L : state.holdingQuantity(),
                    businessEffectiveAt
            );
        }
    }

    private AutoParticipantPositionActivityTracker.PositionAgeSnapshot intradayPositionAge(
            long accountId,
            String symbol,
            LocalDateTime businessEffectiveAt
    ) {
        return positionActivityTracker == null
                ? AutoParticipantPositionActivityTracker.PositionAgeSnapshot.UNAVAILABLE
                : positionActivityTracker.snapshot(accountId, symbol, businessEffectiveAt);
    }

    private RecentMarketActivityTracker.RecentMarketActivitySnapshot recentMarketActivity(
            String symbol,
            LocalDateTime businessEffectiveAt
    ) {
        if (recentMarketActivityTracker == null) {
            return RecentMarketActivityTracker.RecentMarketActivitySnapshot.EMPTY;
        }
        recentMarketActivityTracker.observe(symbol, businessEffectiveAt);
        return recentMarketActivityTracker.snapshot(symbol, businessEffectiveAt);
    }

    private MarketSignalSnapshot marketSignalSnapshot(
            AutoMarketConfig config,
            AutoMarketOrderBookState orderBookState,
            AutoMarketHistoricalSignal historicalSignal,
            double shortMomentumPressure,
            double momentumPressure,
            LocalDateTime businessEffectiveAt,
            RecentMarketActivityTracker.RecentMarketActivitySnapshot recentMarketActivity
    ) {
        long secondsToClose = businessEffectiveAt == null
                ? Long.MAX_VALUE
                : Math.max(0L, Duration.between(
                        businessEffectiveAt,
                        businessEffectiveAt.toLocalDate().atTime(marketCloseTime)
                ).toSeconds());
        double spreadTicks = orderBookState.spreadTicks(config.tickSize());
        AutoMarketHistoricalSignal normalizedHistory = historicalSignal == null
                ? AutoMarketHistoricalSignal.EMPTY
                : historicalSignal;
        long reportAgeSeconds = config.reportCreatedAt() == null || businessEffectiveAt == null
                ? Long.MAX_VALUE
                : Math.max(0L, Duration.between(config.reportCreatedAt(), businessEffectiveAt).toSeconds());
        return new MarketSignalSnapshot(
                shortMomentumPressure,
                momentumPressure,
                normalizedHistory.return1Day(),
                normalizedHistory.return3Day(),
                normalizedHistory.return5Day(),
                normalizedHistory.return10Day(),
                normalizedHistory.return20Day(),
                normalizedHistory.averageVolume5Day(),
                normalizedHistory.averageVolume20Day(),
                secondsToClose,
                spreadTicks,
                orderBookState.openBuyQuantity(),
                orderBookState.openSellQuantity(),
                config.reportPricePressure(),
                reportAgeSeconds,
                recentMarketActivity.executionQuantity(),
                recentMarketActivity.participantCount(),
                recentMarketActivity.available(),
                normalizedHistory.completedTradingDayCount()
        );
    }

    private QuantityDecision createQuantity(
            AutoParticipantStrategy strategy,
            AutoParticipantTradingState tradingState,
            ProfileSignalContext context,
            AutoMarketOrderBookState orderBookState,
            String side,
            BigDecimal price,
            int maxOrderQuantity,
            long requestedQuantity,
            AutoParticipantFundingBudgetType fundingBudgetType,
            AutoParticipantDecisionUrgency urgency,
            AutoParticipantV3Policy policy
    ) {
        long maxQuantity = Math.max(1, maxOrderQuantity);
        long maxOpenQuantity = maxQuantity * Math.max(1, maxOpenOrderQuantityMultiplier);
        long remainingOpenCapacity = tradingState.remainingOpenCapacity(side, maxOpenQuantity);
        ProfileDecision quantityCapacityDecision = new ProfileDecision(
                BUY.equals(side) ? ProfileDecisionAction.BUY : ProfileDecisionAction.SELL,
                ProfileDecisionReason.SIGNAL,
                1,
                0.0
        );
        long allocationCapacity = applyProfileRiskQuantity(
                strategy.profileType(),
                context,
                quantityCapacityDecision,
                side,
                price,
                Long.MAX_VALUE
        );
        if (requestedQuantity > 0) {
            allocationCapacity = Math.min(allocationCapacity, requestedQuantity);
        }
        BigDecimal budget = fundingBudgetType == AutoParticipantFundingBudgetType.PAYDAY
                ? tradingState.paydayAvailableBudget()
                : fundingBudgetType == AutoParticipantFundingBudgetType.DIVIDEND
                        ? tradingState.dividendAvailableBudget()
                        : tradingState.liquidPortfolioAsset();
        long budgetCapacity = budget.signum() <= 0
                ? fundingBudgetType == null ? Long.MAX_VALUE : 0L
                : budget.divide(price, 0, RoundingMode.DOWN).longValue();
        boolean aggressive = isAggressive(side, price, orderBookState);
        long oppositeDepth = BUY.equals(side)
                ? orderBookState.openSellQuantity()
                : orderBookState.openBuyQuantity();
        long averageDailyVolumeCapacity = context.marketSignals().averageVolume5Day() > 0
                ? Math.max(1L, Math.round(context.marketSignals().averageVolume5Day() * 0.02))
                : Long.MAX_VALUE;
        SafeQuantityLimit limit = safeQuantityCalculator.calculate(new SafeQuantityCalculator.LimitInput(
                BUY.equals(side),
                aggressive,
                tradingState.cashBalance(),
                price,
                buyFeeRate,
                maxQuantity,
                remainingOpenCapacity,
                tradingState.availableQuantity(),
                allocationCapacity,
                budgetCapacity,
                oppositeDepth,
                averageDailyVolumeCapacity,
                Long.MAX_VALUE
        ));
        if (limit.safeMaximum() <= 0) {
            return QuantityDecision.dropped(dropReason(limit.bindingReason(), side));
        }
        long quantity = safeQuantityCalculator.sample(
                limit,
                urgency,
                strategy.behaviorSeed(),
                strategy.decisionSlotAt() == null
                        ? java.time.LocalDate.of(1970, 1, 1)
                        : strategy.decisionSlotAt().toLocalDate(),
                strategy.behaviorEventSequence(),
                policy
        ).quantity();
        return quantity <= 0
                ? QuantityDecision.dropped(dropReason(limit.bindingReason(), side))
                : QuantityDecision.accepted(quantity);
    }

    private boolean isAggressive(String side, BigDecimal price, AutoMarketOrderBookState orderBookState) {
        if (BUY.equals(side)) {
            return orderBookState.bestAsk() != null && price.compareTo(orderBookState.bestAsk()) >= 0;
        }
        return orderBookState.bestBid() != null && price.compareTo(orderBookState.bestBid()) <= 0;
    }

    private AutoMarketOrderDropReason dropReason(SafeQuantityBindingReason bindingReason, String side) {
        if (bindingReason == SafeQuantityBindingReason.OPEN_ORDER_ALLOWANCE) {
            return AutoMarketOrderDropReason.OPEN_QUANTITY_LIMIT;
        }
        if (bindingReason == SafeQuantityBindingReason.BUY_AFFORDABILITY) {
            return AutoMarketOrderDropReason.INSUFFICIENT_CASH;
        }
        if (bindingReason == SafeQuantityBindingReason.SELLABLE_HOLDING) {
            return AutoMarketOrderDropReason.INSUFFICIENT_HOLDING;
        }
        return BUY.equals(side)
                ? AutoMarketOrderDropReason.PROFILE_RISK_LIMIT
                : AutoMarketOrderDropReason.INSUFFICIENT_HOLDING;
    }

    private AutoParticipantFundingBudgetType fundingBudgetTypeForIntent(
            AutoParticipantStrategy strategy,
            String side
    ) {
        return fundingBudgetType(strategy, side);
    }

    private void incrementDropCount(
            Map<AutoMarketOrderDropReason, Integer> dropCounts,
            AutoMarketOrderDropReason reason
    ) {
        dropCounts.merge(reason, 1, Integer::sum);
    }

    private AutoMarketOrderDropReason infeasibleIntentReason(ProfileSignalContext context, String side) {
        if (SELL.equals(side) && !context.hasAvailableHolding()) {
            return AutoMarketOrderDropReason.INSUFFICIENT_HOLDING;
        }
        if (BUY.equals(side) && (context.cashBalance() == null || context.cashBalance().signum() <= 0)) {
            return AutoMarketOrderDropReason.INSUFFICIENT_CASH;
        }
        return null;
    }

    private AutoParticipantFundingBudgetType fundingBudgetType(
            AutoParticipantStrategy strategy,
            String side
    ) {
        if (!BUY.equals(side)) {
            return null;
        }
        return switch (strategy.profileType()) {
            case PAYDAY_ACCUMULATOR -> AutoParticipantFundingBudgetType.PAYDAY;
            case DIVIDEND_REINVESTOR -> AutoParticipantFundingBudgetType.DIVIDEND;
            default -> null;
        };
    }

    long adjustQuantityForOrderPressure(
            String side,
            long quantity,
            double orderPressure,
            ProfileDecisionReason decisionReason,
            double signalStrength,
            AutoParticipantProfileType profileType
    ) {
        if (quantity <= 1) {
            return quantity;
        }
        if (decisionReason == ProfileDecisionReason.SESSION_CLOSE
                || decisionReason == ProfileDecisionReason.HOLDING_PERIOD
                || decisionReason == ProfileDecisionReason.EXIT_THRESHOLD
                || decisionReason == ProfileDecisionReason.RISK_LIMIT) {
            return quantity;
        }
        boolean addsToDominantSide = orderPressure >= 0.85 && BUY.equals(side)
                || orderPressure <= -0.85 && SELL.equals(side);
        if (!addsToDominantSide) {
            return quantity;
        }
        boolean directionalCrowdProfile = profileType == AutoParticipantProfileType.FOMO_BUYER
                || profileType == AutoParticipantProfileType.HERD_FOLLOWER
                || profileType == AutoParticipantProfileType.PANIC_SELLER;
        long divisor = directionalCrowdProfile && signalStrength >= 0.50 ? 2L : 4L;
        return Math.max(1L, quantity / divisor);
    }

    long applyProfileRiskQuantity(
            AutoParticipantProfileType profileType,
            ProfileSignalContext context,
            ProfileDecision decision,
            String side,
            BigDecimal price,
            long proposedQuantity
    ) {
        if (proposedQuantity <= 0 || price == null || price.signum() <= 0) {
            return 0L;
        }
        long boundedQuantity = proposedQuantity;
        BigDecimal liquidAsset = context.portfolio().liquidAsset();
        boolean exitOrder = SELL.equals(side) && switch (decision.reason()) {
            case SESSION_CLOSE, HOLDING_PERIOD, EXIT_THRESHOLD, RISK_LIMIT -> true;
            default -> false;
        };
        if (!exitOrder && liquidAsset.signum() > 0) {
            double capitalRate = switch (profileType) {
                case SMALL_DIVERSIFIER -> 0.01;
                case AVERAGE_DOWN_BUYER -> 0.01;
                case PASSIVE_LIMIT_TRADER -> 0.05;
                case WHALE -> 0.10;
                default -> 0.05;
            };
            long capitalCapacity = liquidAsset.multiply(BigDecimal.valueOf(capitalRate))
                    .divide(price, 0, RoundingMode.DOWN)
                    .longValue();
            boundedQuantity = Math.min(boundedQuantity, Math.max(0L, capitalCapacity));
        }
        if (BUY.equals(side) && liquidAsset.signum() > 0) {
            double maximumAllocation = switch (profileType) {
                case SMALL_DIVERSIFIER -> 0.15;
                case AVERAGE_DOWN_BUYER -> 0.25;
                case CASH_DEFENSIVE -> 0.35;
                case PAYDAY_ACCUMULATOR, DIVIDEND_REINVESTOR -> 0.35;
                case LONG_TERM_HOLDER -> 0.45;
                case WHALE -> 0.50;
                case PASSIVE_LIMIT_TRADER -> 0.40;
                default -> 0.40;
            };
            BigDecimal maximumStockValue = liquidAsset.multiply(BigDecimal.valueOf(maximumAllocation));
            BigDecimal projectedStockValue = price
                    .multiply(BigDecimal.valueOf(context.portfolio().holdingQuantity()))
                    .add(price.multiply(BigDecimal.valueOf(context.portfolio().symbolOpenBuyQuantity())));
            BigDecimal remainingStockValue = maximumStockValue.subtract(projectedStockValue).max(BigDecimal.ZERO);
            long allocationCapacity = remainingStockValue.divide(price, 0, RoundingMode.DOWN).longValue();
            boundedQuantity = Math.min(boundedQuantity, allocationCapacity);
        }
        if (profileType == AutoParticipantProfileType.WHALE) {
            long averageVolume = context.marketSignals().averageVolume5Day();
            if (averageVolume > 0) {
                boundedQuantity = Math.min(boundedQuantity, Math.max(1L, Math.round(averageVolume * 0.02)));
            }
            long oppositeDepth = BUY.equals(side)
                    ? context.marketSignals().askDepth()
                    : context.marketSignals().bidDepth();
            if (oppositeDepth > 0) {
                boundedQuantity = Math.min(boundedQuantity, Math.max(1L, Math.round(oppositeDepth * 0.25)));
            }
        }
        return Math.max(0L, boundedQuantity);
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
