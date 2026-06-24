package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoOrder;
import stock.batch.service.batch.automarket.model.AutoParticipant;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;
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
    private static final Map<AutoParticipantProfileType, ProfilePolicy> PROFILE_POLICIES = createProfilePolicies();

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
        if (!participants.isEmpty()) {
            ensureAccounts(participants);
        }
        for (AutoMarketConfig config : configs) {
            List<AutoParticipantStrategy> strategies = autoMarketReader.findEnabledParticipantStrategies(config);
            if (strategies.isEmpty()) {
                processed += runListingAutoAccounts(config);
                continue;
            }
            processed += expireOldAutoOrders(config);
            processed += runListingAutoAccounts(config);
            processed += placeAutoOrders(strategies, config);
        }
        return processed;
    }

    private void ensureAccounts(List<AutoParticipant> participants) {
        LocalDateTime now = LocalDateTime.now();
        for (AutoParticipant participant : participants) {
            if (autoMarketReader.accountExists(participant.userKey())) {
                continue;
            }
            autoMarketWriter.insertAccount(participant, now);
        }
    }

    private int expireOldAutoOrders(AutoMarketConfig config) {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(config.orderTtlSeconds());
        List<AutoOrder> orders = autoMarketReader.findExpiredAutoOrders(config, threshold);

        int expired = 0;
        LocalDateTime now = LocalDateTime.now();
        for (AutoOrder order : orders) {
            if (BUY.equals(order.side()) && order.reservedCash().compareTo(BigDecimal.ZERO) > 0) {
                autoMarketWriter.creditCash(order.accountId(), order.reservedCash(), now);
            }
            if (SELL.equals(order.side())) {
                autoMarketWriter.releaseReservedSellQuantity(order, now);
            }
            autoMarketWriter.cancelOrder(order, now);
            expired++;
        }
        return expired;
    }

    private int runListingAutoAccounts(AutoMarketConfig config) {
        int processed = 0;
        for (ListingAutoAccountConfig listingConfig : autoMarketReader.findEnabledListingAutoAccountConfigs(config)) {
            processed += expireOldListingAutoOrders(listingConfig);
            if (placeListingAutoOrder(listingConfig)) {
                processed++;
            }
        }
        return processed;
    }

    private int expireOldListingAutoOrders(ListingAutoAccountConfig config) {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(config.orderTtlSeconds());
        List<AutoOrder> orders = autoMarketReader.findExpiredListingAutoOrders(config, threshold);

        int expired = 0;
        LocalDateTime now = LocalDateTime.now();
        for (AutoOrder order : orders) {
            if (BUY.equals(order.side()) && order.reservedCash().compareTo(BigDecimal.ZERO) > 0) {
                autoMarketWriter.creditCash(order.accountId(), order.reservedCash(), now);
            }
            if (SELL.equals(order.side())) {
                autoMarketWriter.releaseReservedSellQuantity(order, now);
            }
            autoMarketWriter.cancelOrder(order, now);
            expired++;
        }
        return expired;
    }

    private boolean placeListingAutoOrder(ListingAutoAccountConfig config) {
        String side = listingOrderSide(config);
        if (side == null || autoMarketReader.getOpenOrderQuantity(config.accountId(), config.symbol(), side) > 0) {
            return false;
        }
        long quantity = listingOrderQuantity(config, side);
        if (quantity <= 0) {
            return false;
        }
        BigDecimal price = listingOrderPrice(config, side);
        return placeOrder(config.accountId(), config.symbol(), side, price, quantity);
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

    private long listingOrderQuantity(ListingAutoAccountConfig config, String side) {
        long maxQuantity = Math.max(1, config.maxOrderQuantity());
        if (SELL.equals(side)) {
            long availableQuantity = autoMarketReader.getAvailableQuantity(config.accountId(), config.symbol());
            return Math.min(maxQuantity, availableQuantity);
        }
        BigDecimal cashBalance = autoMarketReader.getCashBalance(config.accountId());
        BigDecimal price = listingOrderPrice(config, side);
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        long affordableQuantity = cashBalance.divide(price, 0, RoundingMode.DOWN).longValue();
        return Math.min(maxQuantity, affordableQuantity);
    }

    private BigDecimal listingOrderPrice(ListingAutoAccountConfig config, String side) {
        BigDecimal tick = positiveOrDefault(config.tickSize(), DEFAULT_TICK_SIZE);
        int offsetTicks = randomInt(0, Math.max(0, config.priceOffsetTicks()));
        BigDecimal offset = tick.multiply(BigDecimal.valueOf(offsetTicks));
        BigDecimal bestBid = autoMarketReader.findBestPrice(config.symbol(), BUY);
        BigDecimal bestAsk = autoMarketReader.findBestPrice(config.symbol(), SELL);
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
        return normalizePrice(rawPrice.max(tick), tick);
    }

    private int placeAutoOrders(List<AutoParticipantStrategy> strategies, AutoMarketConfig config) {
        int placed = 0;
        for (AutoParticipantStrategy strategy : strategies) {
            int effectiveIntensity = effectiveIntensity(strategy, config);
            ProfilePolicy policy = profilePolicy(strategy.profileType());
            double unrealizedReturn = unrealizedReturn(strategy.accountId(), config);
            int orderCount = orderCount(effectiveIntensity, policy, unrealizedReturn);
            for (int index = 0; index < orderCount; index++) {
                String side = chooseSide(strategy, config, effectiveIntensity, policy, unrealizedReturn);
                long quantity = createQuantity(config, policy);
                BigDecimal price = createAutoPrice(config, effectiveIntensity, side, policy);
                if (placeOrder(strategy.accountId(), config.symbol(), side, price, quantity)) {
                    placed++;
                }
            }
        }
        return placed;
    }

    private String chooseSide(
            AutoParticipantStrategy strategy,
            AutoMarketConfig config,
            int effectiveIntensity,
            ProfilePolicy policy,
            double unrealizedReturn
    ) {
        long availableQuantity = autoMarketReader.getAvailableQuantity(strategy.accountId(), config.symbol());
        if (availableQuantity <= 0) {
            return BUY;
        }
        BigDecimal cashBalance = autoMarketReader.getCashBalance(strategy.accountId());
        BigDecimal oneOrderBudget = config.currentPrice().multiply(BigDecimal.valueOf(config.maxOrderQuantity()));
        if (cashBalance.compareTo(oneOrderBudget) < 0) {
            return SELL;
        }
        if (effectiveIntensity >= 9 && policy.contrarianWeight() < 0.5) {
            return BUY;
        }
        if (effectiveIntensity <= 2 && policy.dipBuyWeight() < 0.5 && policy.lossAversionWeight() < 0.7) {
            return SELL;
        }
        double buyBias = buyBias(
                effectiveIntensity,
                policy,
                priceMomentum(config),
                herdPressure(config.symbol()),
                unrealizedReturn,
                availableQuantity
        );
        return ThreadLocalRandom.current().nextDouble() < buyBias ? BUY : SELL;
    }

    int effectiveIntensity(AutoParticipantStrategy strategy, AutoMarketConfig config) {
        Integer reportScore = config.reportScore();
        if (reportScore == null) {
            return clamp(strategy.intensity(), 1, 10);
        }
        double reportWeight = profilePolicy(strategy.profileType()).newsWeight();
        double blended = strategy.intensity() * (1.0 - reportWeight) + clamp(reportScore, 1, 10) * reportWeight;
        return clamp((int) Math.round(blended), 1, 10);
    }

    double buyBiasForProfile(
            AutoParticipantProfileType profileType,
            int effectiveIntensity,
            double momentumPressure,
            double herdPressure,
            double unrealizedReturn,
            long availableQuantity
    ) {
        return calculateBuyBias(
                effectiveIntensity,
                profilePolicy(profileType),
                momentumPressure,
                herdPressure,
                unrealizedReturn,
                availableQuantity,
                0
        );
    }

    int orderCountForProfile(AutoParticipantProfileType profileType, int effectiveIntensity, double unrealizedReturn) {
        return orderCount(effectiveIntensity, profilePolicy(profileType), unrealizedReturn);
    }

    private double buyBias(
            int effectiveIntensity,
            ProfilePolicy policy,
            double momentumPressure,
            double herdPressure,
            double unrealizedReturn,
            long availableQuantity
    ) {
        return calculateBuyBias(
                effectiveIntensity,
                policy,
                momentumPressure,
                herdPressure,
                unrealizedReturn,
                availableQuantity,
                randomNoise(policy.noiseWeight(), 0.18)
        );
    }

    private double calculateBuyBias(
            int effectiveIntensity,
            ProfilePolicy policy,
            double momentumPressure,
            double herdPressure,
            double unrealizedReturn,
            long availableQuantity,
            double noise
    ) {
        double pressure = pricePressure(effectiveIntensity);
        double inventoryPenalty = Math.min(availableQuantity, 20) * 0.01;
        double buyBias = 0.5 + pressure * 0.35 - inventoryPenalty;
        buyBias += momentumPressure * policy.momentumWeight() * 0.24;
        buyBias -= momentumPressure * policy.contrarianWeight() * 0.24;
        buyBias += herdPressure * policy.herdingWeight() * 0.22;
        buyBias -= herdPressure * policy.marketMakingWeight() * 0.22;
        if (unrealizedReturn < 0) {
            buyBias += policy.lossAversionWeight() * 0.18;
            buyBias += policy.dipBuyWeight() * Math.min(0.25, -unrealizedReturn * 1.6);
        } else if (unrealizedReturn > 0) {
            buyBias -= policy.lossAversionWeight() * Math.min(0.12, unrealizedReturn * 0.6);
        }
        if (momentumPressure < -0.35) {
            buyBias -= policy.panicSellWeight() * 0.24;
            buyBias += policy.dipBuyWeight() * 0.24;
        }
        buyBias += noise;
        return clampDouble(0.08, 0.92, buyBias);
    }

    private BigDecimal createAutoPrice(AutoMarketConfig config, int intensity, String side, ProfilePolicy policy) {
        BigDecimal bestBid = autoMarketReader.findBestPrice(config.symbol(), BUY);
        BigDecimal bestAsk = autoMarketReader.findBestPrice(config.symbol(), SELL);
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
            return normalizePrice(bestAsk.add(tick.multiply(BigDecimal.valueOf(randomInt(0, 1)))), tick);
        }
        if (SELL.equals(side) && downwardAggressive && bestBid != null) {
            return normalizePrice(bestBid.subtract(tick.multiply(BigDecimal.valueOf(randomInt(0, 1)))).max(tick), tick);
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
        return normalizePrice(rawPrice.max(tick), tick);
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
        return normalizePrice(rawPrice.max(tick), tick);
    }

    private int orderCount(int effectiveIntensity, ProfilePolicy policy, double unrealizedReturn) {
        int movementStrength = Math.max(effectiveIntensity, 11 - effectiveIntensity);
        int baseOrderCount = Math.max(1, (int) Math.ceil(movementStrength / 3.5));
        double profitBoost = 1.0;
        if (unrealizedReturn > 0) {
            profitBoost += Math.min(0.75, unrealizedReturn * 4.0 * policy.overconfidenceWeight());
        }
        return clamp((int) Math.round(baseOrderCount * policy.orderMultiplier() * profitBoost), 1, 8);
    }

    private long createQuantity(AutoMarketConfig config, ProfilePolicy policy) {
        int maxQuantity = Math.max(1, config.maxOrderQuantity());
        if (policy.quantityMultiplier() >= 1.5 && maxQuantity > 1) {
            return randomInt(Math.max(1, maxQuantity / 2), maxQuantity);
        }
        int upperBound = Math.max(1, (int) Math.floor(maxQuantity * Math.min(1.0, policy.quantityMultiplier())));
        return randomInt(1, upperBound);
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

    private BigDecimal positiveOrDefault(BigDecimal value, BigDecimal defaultValue) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0 ? defaultValue : value;
    }

    private double priceMomentum(AutoMarketConfig config) {
        BigDecimal previousClose = positiveOrDefault(config.previousClose(), config.currentPrice());
        if (previousClose.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        double rate = config.currentPrice()
                .subtract(previousClose)
                .divide(previousClose, 6, RoundingMode.HALF_UP)
                .doubleValue();
        return clampDouble(-1, 1, rate / 0.05);
    }

    private double herdPressure(String symbol) {
        long buyQuantity = autoMarketReader.getOpenOrderQuantity(symbol, BUY);
        long sellQuantity = autoMarketReader.getOpenOrderQuantity(symbol, SELL);
        long totalQuantity = buyQuantity + sellQuantity;
        if (totalQuantity <= 0) {
            return 0;
        }
        return clampDouble(-1, 1, (double) (buyQuantity - sellQuantity) / totalQuantity);
    }

    private double unrealizedReturn(long accountId, AutoMarketConfig config) {
        BigDecimal averagePrice = autoMarketReader.getAveragePrice(accountId, config.symbol());
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
        return PROFILE_POLICIES.getOrDefault(profileType, PROFILE_POLICIES.get(AutoParticipantProfileType.defaultType()));
    }

    private static Map<AutoParticipantProfileType, ProfilePolicy> createProfilePolicies() {
        Map<AutoParticipantProfileType, ProfilePolicy> policies = new EnumMap<>(AutoParticipantProfileType.class);
        policies.put(AutoParticipantProfileType.NEWS_REACTIVE, new ProfilePolicy(0.65, 0.15, 0.00, 0.25, 0.20, 0.00, 0.10, 1.10, 1.15, 0.10, 1.00, 0.00, 0.05));
        policies.put(AutoParticipantProfileType.MOMENTUM_FOLLOWER, new ProfilePolicy(0.25, 0.85, 0.00, 0.20, 0.35, 0.00, 0.15, 1.20, 1.25, 0.15, 1.00, 0.05, 0.00));
        policies.put(AutoParticipantProfileType.CONTRARIAN, new ProfilePolicy(0.20, 0.00, 0.85, 0.25, 0.00, 0.10, 0.05, 1.00, 0.90, 0.12, 1.00, 0.00, 0.35));
        policies.put(AutoParticipantProfileType.LOSS_AVERSE, new ProfilePolicy(0.25, 0.10, 0.00, 0.95, 0.10, 0.00, 0.05, 0.85, 0.80, 0.08, 0.80, 0.05, 0.00));
        policies.put(AutoParticipantProfileType.OVERCONFIDENT, new ProfilePolicy(0.35, 0.45, 0.00, 0.20, 0.25, 0.00, 0.95, 1.60, 1.35, 0.20, 1.25, 0.05, 0.05));
        policies.put(AutoParticipantProfileType.HERD_FOLLOWER, new ProfilePolicy(0.25, 0.25, 0.00, 0.15, 0.90, 0.00, 0.15, 1.25, 1.20, 0.15, 1.00, 0.15, 0.00));
        policies.put(AutoParticipantProfileType.MARKET_MAKER, new ProfilePolicy(0.15, 0.05, 0.00, 0.10, 0.10, 0.95, 0.00, 1.25, 0.65, 0.08, 1.00, 0.00, 0.00));
        policies.put(AutoParticipantProfileType.NOISE_TRADER, new ProfilePolicy(0.35, 0.20, 0.10, 0.20, 0.15, 0.00, 0.10, 1.00, 1.00, 0.45, 1.00, 0.05, 0.05));
        policies.put(AutoParticipantProfileType.VALUE_ANCHOR, new ProfilePolicy(0.20, 0.00, 0.45, 0.55, 0.00, 0.10, 0.00, 0.80, 0.75, 0.08, 0.80, 0.00, 0.25));
        policies.put(AutoParticipantProfileType.SCALPER, new ProfilePolicy(0.25, 0.60, 0.00, 0.10, 0.40, 0.00, 0.25, 2.00, 1.50, 0.35, 0.70, 0.10, 0.00));
        policies.put(AutoParticipantProfileType.PANIC_SELLER, new ProfilePolicy(0.25, 0.25, 0.00, 0.20, 0.45, 0.00, 0.10, 1.40, 1.40, 0.25, 1.10, 0.90, 0.00));
        policies.put(AutoParticipantProfileType.DIP_BUYER, new ProfilePolicy(0.25, 0.00, 0.65, 0.35, 0.10, 0.00, 0.05, 1.15, 1.05, 0.15, 1.00, 0.00, 0.90));
        policies.put(AutoParticipantProfileType.LIQUIDITY_AVOIDANT, new ProfilePolicy(0.20, 0.10, 0.00, 0.35, 0.00, 0.00, 0.00, 0.55, 0.55, 0.05, 0.60, 0.10, 0.00));
        policies.put(AutoParticipantProfileType.WHALE, new ProfilePolicy(0.30, 0.35, 0.00, 0.20, 0.25, 0.00, 0.20, 1.20, 0.85, 0.10, 1.80, 0.05, 0.00));
        policies.put(AutoParticipantProfileType.SMALL_DIVERSIFIER, new ProfilePolicy(0.25, 0.20, 0.10, 0.30, 0.10, 0.00, 0.05, 1.45, 0.70, 0.12, 0.45, 0.00, 0.05));
        policies.put(AutoParticipantProfileType.OBSERVER, new ProfilePolicy(0.15, 0.10, 0.00, 0.20, 0.00, 0.00, 0.00, 0.30, 0.40, 0.03, 0.40, 0.00, 0.00));
        return Map.copyOf(policies);
    }

    private record ProfilePolicy(
            double newsWeight,
            double momentumWeight,
            double contrarianWeight,
            double lossAversionWeight,
            double herdingWeight,
            double marketMakingWeight,
            double overconfidenceWeight,
            double orderMultiplier,
            double aggressionMultiplier,
            double noiseWeight,
            double quantityMultiplier,
            double panicSellWeight,
            double dipBuyWeight
    ) {
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
}
