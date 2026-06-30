package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.batch.service.automarket.profile.AutoProfileBehavior;
import stock.batch.service.automarket.profile.AutoProfileBehaviorRegistry;
import stock.batch.service.automarket.profile.ProfilePolicy;
import stock.batch.service.automarket.profile.ProfileSignalContext;
import stock.batch.service.automarket.support.SimulationTimeScale;
import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoOrder;
import stock.batch.service.batch.automarket.model.AutoParticipant;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;
import stock.batch.service.batch.automarket.model.AutoParticipantTradingSnapshot;
import stock.batch.service.batch.automarket.model.ListingAutoAccountConfig;
import stock.batch.service.batch.automarket.reader.AutoMarketReader;
import stock.batch.service.batch.automarket.writer.AutoMarketWriter;

@Service
@RequiredArgsConstructor
public class AutoMarketService {

    private static final String BUY = "BUY";
    private static final String SELL = "SELL";
    private static final String SELL_ONLY = "SELL_ONLY";
    private static final String BUY_ONLY = "BUY_ONLY";
    private static final BigDecimal DEFAULT_TICK_SIZE = BigDecimal.valueOf(100);
    private static final Duration PROJECT_DIVIDEND_REINVESTMENT_SIGNAL_WINDOW = Duration.ofDays(7);
    private static final Duration PROJECT_SHORT_MOMENTUM_WINDOW = Duration.ofHours(1);
    private static final Duration DIVIDEND_REINVESTMENT_SIGNAL_WINDOW =
            SimulationTimeScale.projectDurationToRuntime(PROJECT_DIVIDEND_REINVESTMENT_SIGNAL_WINDOW);
    private static final Duration SHORT_MOMENTUM_WINDOW =
            SimulationTimeScale.projectDurationToRuntime(PROJECT_SHORT_MOMENTUM_WINDOW);
    private static final int EXPIRED_AUTO_ORDER_LIMIT_PER_PROFILE = 200;
    private static final AutoProfileBehaviorRegistry PROFILE_BEHAVIORS = AutoProfileBehaviorRegistry.createDefault();

    private final AutoMarketReader autoMarketReader;
    private final AutoMarketWriter autoMarketWriter;

    @Transactional
    public int runAutoMarketStep() {
        List<AutoParticipant> participants = autoMarketReader.findEnabledParticipants();
        List<AutoMarketConfig> configs = autoMarketReader.findEnabledConfigs();
        if (configs.isEmpty()) {
            return 0;
        }

        int processed = 0;
        Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies = loadProfilePolicies();
        if (!participants.isEmpty()) {
            ensureAccounts(participants);
        }
        Map<String, List<AutoParticipantStrategy>> strategiesBySymbol = participants.isEmpty()
                ? Map.of()
                : autoMarketReader.findEnabledParticipantStrategiesBySymbol(configs);
        for (AutoMarketConfig config : configs) {
            List<AutoParticipantStrategy> strategies = strategiesBySymbol.getOrDefault(config.symbol(), List.of());
            if (strategies.isEmpty()) {
                processed += runListingAutoAccounts(config);
                continue;
            }
            processed += expireOldAutoOrders(config, profilePolicies);
            processed += runListingAutoAccounts(config);
            processed += placeAutoOrders(strategies, config, profilePolicies);
        }
        return processed;
    }

    private void ensureAccounts(List<AutoParticipant> participants) {
        LocalDateTime now = LocalDateTime.now();
        Set<String> existingAccountUserKeys = new HashSet<>(autoMarketReader.findExistingAccountUserKeys(
                participants.stream()
                        .map(AutoParticipant::userKey)
                        .toList()
        ));
        for (AutoParticipant participant : participants) {
            if (existingAccountUserKeys.contains(participant.userKey())) {
                continue;
            }
            autoMarketWriter.insertAccount(participant, now);
        }
    }

    private int expireOldAutoOrders(
            AutoMarketConfig config,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies
    ) {
        int expired = 0;
        LocalDateTime now = LocalDateTime.now();
        Map<AutoParticipantProfileType, LocalDateTime> thresholdsByProfile = expiryThresholdsByProfile(config, profilePolicies, now);
        LocalDateTime candidateThreshold = thresholdsByProfile.values().stream()
                .max(LocalDateTime::compareTo)
                .orElse(now);
        int candidateLimit = Math.max(
                EXPIRED_AUTO_ORDER_LIMIT_PER_PROFILE,
                thresholdsByProfile.size() * EXPIRED_AUTO_ORDER_LIMIT_PER_PROFILE
        );
        List<AutoOrder> orders = autoMarketReader.findExpiredAutoOrders(config, candidateThreshold, candidateLimit);
        for (AutoOrder order : orders) {
            LocalDateTime threshold = thresholdsByProfile.getOrDefault(order.profileType(), candidateThreshold);
            if (order.createdAt() != null && !order.createdAt().isBefore(threshold)) {
                continue;
            }
            expireOrder(order, now);
            expired++;
        }
        return expired;
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
                            ProfilePolicy policy = profilePolicy(profilePolicies, profileType);
                            AutoProfileBehavior behavior = PROFILE_BEHAVIORS.behavior(profileType);
                            int projectTtlSeconds = behavior.orderTtlSeconds(config.orderTtlSeconds(), policy);
                            return now.minusSeconds(runtimeAutoOrderTtlSeconds(projectTtlSeconds));
                        }
                ));
    }

    private void expireOrder(AutoOrder order, LocalDateTime now) {
        if (BUY.equals(order.side()) && order.reservedCash().compareTo(BigDecimal.ZERO) > 0) {
            autoMarketWriter.creditCash(order.accountId(), order.reservedCash(), now);
        }
        if (SELL.equals(order.side())) {
            autoMarketWriter.releaseReservedSellQuantity(order, now);
        }
        autoMarketWriter.cancelOrder(order, now);
    }

    private int runListingAutoAccounts(AutoMarketConfig config) {
        int processed = 0;
        List<ListingAutoAccountConfig> listingConfigs = autoMarketReader.findEnabledListingAutoAccountConfigs(config);
        if (listingConfigs.isEmpty()) {
            return 0;
        }
        for (ListingAutoAccountConfig listingConfig : listingConfigs) {
            processed += expireOldListingAutoOrders(listingConfig);
        }
        BestPrices bestPrices = loadBestPrices(config.symbol());
        for (ListingAutoAccountConfig listingConfig : listingConfigs) {
            PlacedAutoOrder placedOrder = placeListingAutoOrder(listingConfig, bestPrices);
            if (placedOrder != null) {
                bestPrices = bestPrices.withPlacedOrder(placedOrder.side(), placedOrder.price());
                processed++;
            }
        }
        return processed;
    }

    private int expireOldListingAutoOrders(ListingAutoAccountConfig config) {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(runtimeAutoOrderTtlSeconds(config.orderTtlSeconds()));
        List<AutoOrder> orders = autoMarketReader.findExpiredListingAutoOrders(config, threshold);

        int expired = 0;
        LocalDateTime now = LocalDateTime.now();
        for (AutoOrder order : orders) {
            expireOrder(order, now);
            expired++;
        }
        return expired;
    }

    private PlacedAutoOrder placeListingAutoOrder(ListingAutoAccountConfig config, BestPrices bestPrices) {
        String side = listingOrderSide(config);
        if (side == null || autoMarketReader.getOpenOrderQuantity(config.accountId(), config.symbol(), side) > 0) {
            return null;
        }
        BigDecimal price = listingOrderPrice(config, side, bestPrices);
        long quantity = listingOrderQuantity(config, side, price);
        if (quantity <= 0) {
            return null;
        }
        return placeOrder(config.accountId(), config.symbol(), side, price, quantity)
                ? new PlacedAutoOrder(side, price)
                : null;
    }

    private String listingOrderSide(ListingAutoAccountConfig config) {
        if (SELL_ONLY.equals(config.positionSide())) {
            return SELL;
        }
        if (BUY_ONLY.equals(config.positionSide())) {
            return BUY;
        }
        return null;
    }

    private long listingOrderQuantity(ListingAutoAccountConfig config, String side, BigDecimal price) {
        long maxQuantity = Math.max(1, config.maxOrderQuantity());
        if (SELL.equals(side)) {
            long availableQuantity = autoMarketReader.getAvailableQuantity(config.accountId(), config.symbol());
            return Math.min(maxQuantity, availableQuantity);
        }
        BigDecimal cashBalance = autoMarketReader.getCashBalance(config.accountId());
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        long affordableQuantity = cashBalance.divide(price, 0, RoundingMode.DOWN).longValue();
        return Math.min(maxQuantity, affordableQuantity);
    }

    private BigDecimal listingOrderPrice(ListingAutoAccountConfig config, String side, BestPrices bestPrices) {
        BigDecimal tick = positiveOrDefault(config.tickSize(), DEFAULT_TICK_SIZE);
        int offsetTicks = randomInt(0, Math.max(0, config.priceOffsetTicks()));
        BigDecimal offset = tick.multiply(BigDecimal.valueOf(offsetTicks));
        BigDecimal bestBid = bestPrices.bestBid();
        BigDecimal bestAsk = bestPrices.bestAsk();
        BigDecimal rawPrice;
        if (SELL.equals(side)) {
            rawPrice = bestAsk == null ? config.currentPrice().add(offset) : bestAsk.add(offset);
            if (bestBid != null && rawPrice.compareTo(bestBid) <= 0) {
                rawPrice = bestBid.add(tick);
            }
        } else {
            rawPrice = bestBid == null ? config.currentPrice().subtract(offset) : bestBid.subtract(offset);
            if (bestAsk != null && rawPrice.compareTo(bestAsk) >= 0) {
                rawPrice = bestAsk.subtract(tick);
            }
        }
        return normalizePriceWithinDailyLimit(rawPrice.max(tick), config, tick);
    }

    private int placeAutoOrders(
            List<AutoParticipantStrategy> strategies,
            AutoMarketConfig config,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies
    ) {
        int placed = 0;
        Map<Long, AutoParticipantTradingState> tradingStates = loadTradingStates(strategies, config);
        BestPrices bestPrices = loadBestPrices(config.symbol());
        OpenOrderQuantities openOrderQuantities = loadOpenOrderQuantities(config.symbol());
        double momentumPressure = priceMomentum(config);
        for (AutoParticipantStrategy strategy : strategies) {
            AutoParticipantTradingState tradingState = tradingStates.getOrDefault(strategy.accountId(), AutoParticipantTradingState.empty(strategy.accountId()));
            AutoProfileBehavior behavior = PROFILE_BEHAVIORS.behavior(strategy.profileType());
            ProfilePolicy policy = profilePolicy(profilePolicies, strategy.profileType());
            int effectiveIntensity = behavior.effectiveIntensity(strategy, config, policy);
            double unrealizedReturn = unrealizedReturn(tradingState, config);
            ProfileSignalContext baseContext = profileContext(strategy, config, policy, effectiveIntensity, momentumPressure, unrealizedReturn, 0, tradingState, openOrderQuantities);
            int orderCount = behavior.orderCount(baseContext);
            for (int index = 0; index < orderCount; index++) {
                ProfileSignalContext context = baseContext.withOrderIndex(index);
                String side = behavior.chooseSide(context);
                if (side == null) {
                    continue;
                }
                BigDecimal price = createAutoPrice(config, effectiveIntensity, side, policy, bestPrices);
                long quantity = createQuantity(tradingState, side, price, policy, behavior, config.maxOrderQuantity());
                if (quantity <= 0) {
                    continue;
                }
                if (placeOrder(strategy.accountId(), config.symbol(), side, price, quantity)) {
                    bestPrices = bestPrices.withPlacedOrder(side, price);
                    openOrderQuantities = openOrderQuantities.withPlacedOrder(side, quantity);
                    tradingState.reserve(side, price, quantity);
                    placed++;
                }
            }
        }
        return placed;
    }

    private Map<Long, AutoParticipantTradingState> loadTradingStates(
            List<AutoParticipantStrategy> strategies,
            AutoMarketConfig config
    ) {
        if (strategies.isEmpty()) {
            return Map.of();
        }
        LocalDateTime recentDividendSince = LocalDateTime.now().minus(DIVIDEND_REINVESTMENT_SIGNAL_WINDOW);
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
            OpenOrderQuantities openOrderQuantities
    ) {
        return new ProfileSignalContext(
                strategy,
                config,
                policy,
                effectiveIntensity,
                momentumPressure,
                herdPressure(openOrderQuantities),
                unrealizedReturn,
                tradingState.availableQuantity(),
                tradingState.cashBalance(),
                tradingState.recentDividendCashAmount(),
                isAtLowerPriceLimit(config),
                orderIndex,
                randomNoise(policy.noiseWeight(), 0.18)
        );
    }

    int effectiveIntensity(AutoParticipantStrategy strategy, AutoMarketConfig config) {
        return PROFILE_BEHAVIORS.behavior(strategy.profileType()).effectiveIntensity(strategy, config, profilePolicy(strategy.profileType()));
    }

    double buyBiasForProfile(
            AutoParticipantProfileType profileType,
            int effectiveIntensity,
            double momentumPressure,
            double herdPressure,
            double unrealizedReturn,
            long availableQuantity
    ) {
        ProfilePolicy policy = profilePolicy(profileType);
        ProfileSignalContext context = new ProfileSignalContext(null, null, policy, effectiveIntensity, momentumPressure, herdPressure, unrealizedReturn, availableQuantity, BigDecimal.ZERO, false, 0, 0);
        return PROFILE_BEHAVIORS.behavior(profileType).buyBias(context);
    }

    int orderCountForProfile(AutoParticipantProfileType profileType, int effectiveIntensity, double unrealizedReturn) {
        ProfilePolicy policy = profilePolicy(profileType);
        ProfileSignalContext context = new ProfileSignalContext(null, null, policy, effectiveIntensity, 0, 0, unrealizedReturn, 0, BigDecimal.ZERO, false, 0, 0);
        return PROFILE_BEHAVIORS.behavior(profileType).orderCount(context);
    }

    int quantityUpperBoundForProfile(AutoParticipantProfileType profileType, int maxOrderQuantity) {
        return PROFILE_BEHAVIORS.behavior(profileType).quantityUpperBound(Math.max(1, maxOrderQuantity), profilePolicy(profileType));
    }

    int orderTtlSecondsForProfile(AutoParticipantProfileType profileType, int baseTtlSeconds) {
        return PROFILE_BEHAVIORS.behavior(profileType).orderTtlSeconds(Math.max(1, baseTtlSeconds), profilePolicy(profileType));
    }

    int runtimeOrderTtlSecondsForProfile(AutoParticipantProfileType profileType, int baseTtlSeconds) {
        return runtimeAutoOrderTtlSeconds(orderTtlSecondsForProfile(profileType, baseTtlSeconds));
    }

    private BigDecimal createAutoPrice(
            AutoMarketConfig config,
            int intensity,
            String side,
            ProfilePolicy policy,
            BestPrices bestPrices
    ) {
        BigDecimal bestBid = bestPrices.bestBid();
        BigDecimal bestAsk = bestPrices.bestAsk();
        BigDecimal tick = config.tickSize();
        if (policy.marketMakingWeight() >= 0.8) {
            return createMarketMakingPrice(config, side, bestBid, bestAsk, tick);
        }
        double pressure = clampDouble(-1, 1, pricePressure(intensity) + randomNoise(policy.noiseWeight(), 0.12));
        double pressureStrength = Math.abs(pressure);
        double aggressiveChance = clampDouble(0.05, 0.95, (0.35 + pressureStrength * 0.45) * policy.aggressionMultiplier());
        boolean upwardAggressive = pressure > 0 && ThreadLocalRandom.current().nextDouble() < aggressiveChance;
        boolean downwardAggressive = pressure < 0 && ThreadLocalRandom.current().nextDouble() < aggressiveChance;

        if (BUY.equals(side) && upwardAggressive && bestAsk != null) {
            return normalizePriceWithinDailyLimit(bestAsk.add(tick.multiply(BigDecimal.valueOf(randomInt(0, 1)))), config, tick);
        }
        if (SELL.equals(side) && downwardAggressive && bestBid != null) {
            return normalizePriceWithinDailyLimit(bestBid.subtract(tick.multiply(BigDecimal.valueOf(randomInt(0, 1)))).max(tick), config, tick);
        }

        int maxSpreadTicks = 2 + (int) Math.ceil(pressureStrength * 6);
        BigDecimal spread = tick.multiply(BigDecimal.valueOf(randomInt(1, maxSpreadTicks)));
        BigDecimal directionalOffset = tick.multiply(BigDecimal.valueOf(Math.round(pressure * 2)));
        BigDecimal rawPrice;
        if (BUY.equals(side)) {
            rawPrice = config.currentPrice().add(directionalOffset).subtract(pressure < 0 ? spread : BigDecimal.ZERO);
        } else {
            rawPrice = config.currentPrice().add(directionalOffset).add(pressure > 0 ? spread : BigDecimal.ZERO);
        }
        return normalizePriceWithinDailyLimit(rawPrice.max(tick), config, tick);
    }

    private BestPrices loadBestPrices(String symbol) {
        return new BestPrices(
                autoMarketReader.findBestPrice(symbol, BUY),
                autoMarketReader.findBestPrice(symbol, SELL)
        );
    }

    private OpenOrderQuantities loadOpenOrderQuantities(String symbol) {
        return new OpenOrderQuantities(
                autoMarketReader.getOpenOrderQuantity(symbol, BUY),
                autoMarketReader.getOpenOrderQuantity(symbol, SELL)
        );
    }

    private BigDecimal createMarketMakingPrice(
            AutoMarketConfig config,
            String side,
            BigDecimal bestBid,
            BigDecimal bestAsk,
            BigDecimal tick
    ) {
        BigDecimal rawPrice;
        if (BUY.equals(side)) {
            rawPrice = bestBid == null ? config.currentPrice().subtract(tick) : bestBid;
            if (bestAsk != null && rawPrice.compareTo(bestAsk) >= 0) {
                rawPrice = bestAsk.subtract(tick);
            }
        } else {
            rawPrice = bestAsk == null ? config.currentPrice().add(tick) : bestAsk;
            if (bestBid != null && rawPrice.compareTo(bestBid) <= 0) {
                rawPrice = bestBid.add(tick);
            }
        }
        return normalizePriceWithinDailyLimit(rawPrice.max(tick), config, tick);
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
            profileQuantity = randomInt(Math.max(1, maxQuantity / 2), behavior.quantityUpperBound(maxQuantity, policy));
        } else {
            int upperBound = behavior.quantityUpperBound(maxQuantity, policy);
            profileQuantity = randomInt(1, upperBound);
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

    private boolean placeOrder(long accountId, String symbol, String side, BigDecimal price, long quantity) {
        LocalDateTime now = LocalDateTime.now();
        BigDecimal reservedCash = BigDecimal.ZERO;
        if (BUY.equals(side)) {
            reservedCash = price.multiply(BigDecimal.valueOf(quantity));
            if (!autoMarketWriter.reserveBuyCash(accountId, reservedCash, now)) {
                return false;
            }
        } else {
            if (!autoMarketWriter.reserveSellQuantity(accountId, symbol, quantity, now)) {
                return false;
            }
        }

        autoMarketWriter.insertLimitOrder(
                nextClientOrderId(),
                accountId,
                symbol,
                side,
                price,
                quantity,
                reservedCash,
                now
        );
        return true;
    }

    private BigDecimal normalizePrice(BigDecimal rawPrice, BigDecimal tick) {
        BigDecimal normalizedTick = positiveOrDefault(tick, DEFAULT_TICK_SIZE);
        BigDecimal ticks = rawPrice.divide(normalizedTick, 0, RoundingMode.HALF_UP);
        return ticks.multiply(normalizedTick).max(normalizedTick).setScale(2, RoundingMode.UNNECESSARY);
    }

    private BigDecimal normalizePriceWithinDailyLimit(BigDecimal rawPrice, AutoMarketConfig config, BigDecimal tick) {
        return normalizePriceWithinDailyLimit(rawPrice, tick, config.currentPrice(), config.previousClose(), config.priceLimitRate());
    }

    private BigDecimal normalizePriceWithinDailyLimit(BigDecimal rawPrice, ListingAutoAccountConfig config, BigDecimal tick) {
        return normalizePriceWithinDailyLimit(rawPrice, tick, config.currentPrice(), config.previousClose(), config.priceLimitRate());
    }

    private BigDecimal normalizePriceWithinDailyLimit(
            BigDecimal rawPrice,
            BigDecimal tick,
            BigDecimal currentPrice,
            BigDecimal previousClose,
            BigDecimal priceLimitRate
    ) {
        BigDecimal normalizedTick = positiveOrDefault(tick, DEFAULT_TICK_SIZE);
        BigDecimal lowerLimit = dailyLowerLimit(currentPrice, previousClose, priceLimitRate);
        BigDecimal upperLimit = dailyUpperLimit(currentPrice, previousClose, priceLimitRate);
        BigDecimal clampedPrice = rawPrice.max(lowerLimit).min(upperLimit);
        BigDecimal normalizedPrice = normalizePrice(clampedPrice, normalizedTick);
        if (normalizedPrice.compareTo(lowerLimit) < 0) {
            normalizedPrice = ceilToTick(lowerLimit, normalizedTick);
        }
        if (normalizedPrice.compareTo(upperLimit) > 0) {
            normalizedPrice = floorToTick(upperLimit, normalizedTick);
        }
        return normalizedPrice.max(normalizedTick).setScale(2, RoundingMode.UNNECESSARY);
    }

    private BigDecimal dailyLowerLimit(AutoMarketConfig config) {
        return dailyLowerLimit(config.currentPrice(), config.previousClose(), config.priceLimitRate());
    }

    private BigDecimal dailyLowerLimit(BigDecimal currentPrice, BigDecimal previousClose, BigDecimal priceLimitRate) {
        BigDecimal normalizedPreviousClose = positiveOrDefault(previousClose, currentPrice);
        BigDecimal normalizedPriceLimitRate = positiveOrDefault(priceLimitRate, BigDecimal.valueOf(30));
        return normalizedPreviousClose
                .multiply(BigDecimal.valueOf(100).subtract(normalizedPriceLimitRate))
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal dailyUpperLimit(BigDecimal currentPrice, BigDecimal previousClose, BigDecimal priceLimitRate) {
        BigDecimal normalizedPreviousClose = positiveOrDefault(previousClose, currentPrice);
        BigDecimal normalizedPriceLimitRate = positiveOrDefault(priceLimitRate, BigDecimal.valueOf(30));
        return normalizedPreviousClose
                .multiply(BigDecimal.valueOf(100).add(normalizedPriceLimitRate))
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal ceilToTick(BigDecimal value, BigDecimal tick) {
        return value.divide(tick, 0, RoundingMode.CEILING)
                .multiply(tick)
                .setScale(2, RoundingMode.UNNECESSARY);
    }

    private BigDecimal floorToTick(BigDecimal value, BigDecimal tick) {
        return value.divide(tick, 0, RoundingMode.FLOOR)
                .multiply(tick)
                .setScale(2, RoundingMode.UNNECESSARY);
    }

    private BigDecimal positiveOrDefault(BigDecimal value, BigDecimal defaultValue) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0 ? defaultValue : value;
    }

    double priceMomentum(AutoMarketConfig config) {
        BigDecimal referencePrice = autoMarketReader.findLatestPriceAtOrBefore(
                        config.symbol(),
                        LocalDateTime.now().minus(SHORT_MOMENTUM_WINDOW)
                )
                .orElseGet(() -> positiveOrDefault(config.previousClose(), config.currentPrice()));
        if (referencePrice.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        double rate = config.currentPrice()
                .subtract(referencePrice)
                .divide(referencePrice, 6, RoundingMode.HALF_UP)
                .doubleValue();
        return clampDouble(-1, 1, rate / 0.05);
    }

    private int runtimeAutoOrderTtlSeconds(int projectTtlSeconds) {
        return SimulationTimeScale.projectAutoOrderTtlToRuntimeSeconds(projectTtlSeconds);
    }

    private double herdPressure(OpenOrderQuantities openOrderQuantities) {
        return openOrderQuantities.pressure();
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

    private double pricePressure(int intensity) {
        return (clamp(intensity, 1, 10) - 5.5) / 4.5;
    }

    private double randomNoise(double weight, double scale) {
        if (weight <= 0) {
            return 0;
        }
        return ThreadLocalRandom.current().nextDouble(-1, 1) * weight * scale;
    }

    private double clampDouble(double min, double max, double value) {
        return Math.max(min, Math.min(max, value));
    }

    private ProfilePolicy profilePolicy(AutoParticipantProfileType profileType) {
        return PROFILE_BEHAVIORS.behavior(profileType).defaultPolicy();
    }

    private ProfilePolicy profilePolicy(
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            AutoParticipantProfileType profileType
    ) {
        return profilePolicies.getOrDefault(profileType, profilePolicies.get(AutoParticipantProfileType.defaultType()));
    }

    private Map<AutoParticipantProfileType, ProfilePolicy> loadProfilePolicies() {
        return PROFILE_BEHAVIORS.policiesWithOverrides(autoMarketReader.findParticipantProfileConfigs());
    }

    private String nextClientOrderId() {
        return "auto-" + UUID.randomUUID().toString().replace("-", "");
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int randomInt(int minInclusive, int maxInclusive) {
        return ThreadLocalRandom.current().nextInt(minInclusive, maxInclusive + 1);
    }

    private record PlacedAutoOrder(String side, BigDecimal price) {
    }

    private record BestPrices(BigDecimal bestBid, BigDecimal bestAsk) {
        private BestPrices withPlacedOrder(String side, BigDecimal price) {
            if (BUY.equals(side)) {
                return new BestPrices(bestBid == null || price.compareTo(bestBid) > 0 ? price : bestBid, bestAsk);
            }
            if (SELL.equals(side)) {
                return new BestPrices(bestBid, bestAsk == null || price.compareTo(bestAsk) < 0 ? price : bestAsk);
            }
            return this;
        }
    }

    private record OpenOrderQuantities(long buyQuantity, long sellQuantity) {
        private OpenOrderQuantities withPlacedOrder(String side, long quantity) {
            long normalizedQuantity = Math.max(0L, quantity);
            if (BUY.equals(side)) {
                return new OpenOrderQuantities(buyQuantity + normalizedQuantity, sellQuantity);
            }
            if (SELL.equals(side)) {
                return new OpenOrderQuantities(buyQuantity, sellQuantity + normalizedQuantity);
            }
            return this;
        }

        private double pressure() {
            long totalQuantity = buyQuantity + sellQuantity;
            if (totalQuantity <= 0) {
                return 0;
            }
            return Math.max(-1, Math.min(1, (double) (buyQuantity - sellQuantity) / totalQuantity));
        }
    }

    private static final class AutoParticipantTradingState {
        private final long accountId;
        private BigDecimal cashBalance;
        private long availableQuantity;
        private final BigDecimal averagePrice;
        private final BigDecimal recentDividendCashAmount;

        private AutoParticipantTradingState(
                long accountId,
                BigDecimal cashBalance,
                long availableQuantity,
                BigDecimal averagePrice,
                BigDecimal recentDividendCashAmount
        ) {
            this.accountId = accountId;
            this.cashBalance = zeroIfNull(cashBalance);
            this.availableQuantity = Math.max(0L, availableQuantity);
            this.averagePrice = zeroIfNull(averagePrice);
            this.recentDividendCashAmount = zeroIfNull(recentDividendCashAmount);
        }

        static AutoParticipantTradingState from(AutoParticipantTradingSnapshot snapshot) {
            return new AutoParticipantTradingState(
                    snapshot.accountId(),
                    snapshot.cashBalance(),
                    snapshot.availableQuantity(),
                    snapshot.averagePrice(),
                    snapshot.recentDividendCashAmount()
            );
        }

        static AutoParticipantTradingState empty(long accountId) {
            return new AutoParticipantTradingState(accountId, BigDecimal.ZERO, 0L, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        long accountId() {
            return accountId;
        }

        BigDecimal cashBalance() {
            return cashBalance;
        }

        long availableQuantity() {
            return availableQuantity;
        }

        BigDecimal averagePrice() {
            return averagePrice;
        }

        BigDecimal recentDividendCashAmount() {
            return recentDividendCashAmount;
        }

        void reserve(String side, BigDecimal price, long quantity) {
            if (BUY.equals(side)) {
                cashBalance = cashBalance.subtract(price.multiply(BigDecimal.valueOf(quantity))).max(BigDecimal.ZERO);
                return;
            }
            if (SELL.equals(side)) {
                availableQuantity = Math.max(0L, availableQuantity - quantity);
            }
        }

        private static BigDecimal zeroIfNull(BigDecimal value) {
            return value == null ? BigDecimal.ZERO : value;
        }
    }
}
