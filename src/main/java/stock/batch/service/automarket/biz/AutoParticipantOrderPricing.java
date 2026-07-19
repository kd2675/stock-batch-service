package stock.batch.service.automarket.biz;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

import stock.batch.service.automarket.profile.ProfilePolicy;
import stock.batch.service.batch.automarket.model.AutoMarketConfig;

import static stock.batch.service.automarket.biz.AutoMarketPricePolicy.normalizePriceWithinDailyLimit;
import static stock.batch.service.automarket.biz.AutoMarketPricePolicy.moveByTicks;
import static stock.batch.service.automarket.support.AutoMarketRandomSupport.chance;
import static stock.batch.service.automarket.support.AutoMarketRandomSupport.nextInt;
import static stock.batch.service.automarket.support.AutoMarketRandomSupport.noise;

@Component
class AutoParticipantOrderPricing {

    private static final String BUY = "BUY";
    private static final String SELL = "SELL";
    private static final double BASE_DIRECTIONAL_PRICE_MOVE_RATE = 0.006;
    private static final double MAX_DIRECTIONAL_PRICE_MOVE_RATE = 0.008;
    private static final double DIRECTIONAL_CROSSING_ADJUSTMENT = 0.25;
    private static final double MIN_CROSSING_CHANCE = 0.02;
    private static final double MAX_CROSSING_CHANCE = 0.85;

    BigDecimal createAutoPrice(
            AutoMarketConfig config,
            int strategyActivityLevel,
            String side,
            ProfilePolicy policy,
            AutoMarketOrderBookState orderBookState
    ) {
        BigDecimal bestBid = orderBookState.bestBid();
        BigDecimal bestAsk = orderBookState.bestAsk();
        double activityStrength = Math.clamp(strategyActivityLevel, 1, 10) / 10.0;
        double directionalPressure = config.dailyPricePressure()
                + config.reportPricePressure() * policy.newsWeight() * 0.55;
        double pressure = Math.clamp(directionalPressure * activityStrength + noise(policy.noiseWeight(), 0.12), -1, 1);
        double volatility = config.volatilityMultiplier();
        BigDecimal tick = KoreanStockTickSizePolicy.tickSizeForQuotePrice(config.market(), config.currentPrice());
        if (policy.marketMakingWeight() >= 0.8) {
            int maxDepthTicks = Math.clamp((int) Math.round(1.0 + volatility), 1, 3);
            return createMarketMakingPrice(
                    config,
                    side,
                    bestBid,
                    bestAsk,
                    pressure,
                    nextInt(0, maxDepthTicks)
            );
        }
        boolean crossesOppositeQuote = chance(crossingChance(config, side, policy, pressure));

        if (BUY.equals(side) && crossesOppositeQuote && bestAsk != null) {
            return normalizePriceWithinDailyLimit(moveByTicks(config.market(), bestAsk, nextInt(0, 1)), config, tick);
        }
        if (SELL.equals(side) && crossesOppositeQuote && bestBid != null) {
            return normalizePriceWithinDailyLimit(moveByTicks(config.market(), bestBid, -nextInt(0, 1)), config, tick);
        }

        return createDirectionalLimitPrice(config, side, pressure, volatility);
    }

    double crossingChance(
            AutoMarketConfig config,
            String side,
            ProfilePolicy policy,
            double pressure
    ) {
        double profileAggression = Math.max(0.0, policy.aggressionMultiplier());
        if (profileAggression == 0.0) {
            return 0.0;
        }
        double pressureStrength = Math.abs(pressure);
        double baseChance = config.executionAggressionStrength() * 0.58
                + config.effectiveLiquidityLevel() / 10.0 * 0.18
                + pressureStrength * 0.10;
        double sideDirection = BUY.equals(side) ? 1.0 : SELL.equals(side) ? -1.0 : 0.0;
        double directionalAdjustment = sideDirection
                * pressure
                * DIRECTIONAL_CROSSING_ADJUSTMENT
                * config.executionAggressionMultiplier()
                * Math.min(1.0, profileAggression);
        double maximumChance = config.effectiveExecutionAggressionLevel() >= 10
                ? 1.0
                : MAX_CROSSING_CHANCE;
        double scaledBaseChance = Math.clamp(
                baseChance * profileAggression,
                MIN_CROSSING_CHANCE,
                maximumChance
        );
        return Math.clamp(
                scaledBaseChance + directionalAdjustment,
                MIN_CROSSING_CHANCE,
                maximumChance
        );
    }

    BigDecimal createDirectionalLimitPrice(
            AutoMarketConfig config,
            String side,
            double pressure,
            double volatility
    ) {
        BigDecimal tick = KoreanStockTickSizePolicy.tickSizeForQuotePrice(config.market(), config.currentPrice());
        double pressureStrength = Math.abs(pressure);
        int maxSpreadTicks = 2 + (int) Math.ceil(pressureStrength * 6 * volatility);
        BigDecimal spread = tick.multiply(BigDecimal.valueOf(nextInt(1, maxSpreadTicks)));
        double directionalMoveRate = Math.clamp(
                pressure * BASE_DIRECTIONAL_PRICE_MOVE_RATE * volatility,
                -MAX_DIRECTIONAL_PRICE_MOVE_RATE,
                MAX_DIRECTIONAL_PRICE_MOVE_RATE
        );
        BigDecimal directionalOffset = config.currentPrice().multiply(BigDecimal.valueOf(directionalMoveRate));
        BigDecimal centerPrice = config.currentPrice().add(directionalOffset);
        double quoteDistanceFactor;
        BigDecimal rawPrice;
        if (BUY.equals(side)) {
            quoteDistanceFactor = 1.0 - Math.max(0.0, pressure);
            rawPrice = centerPrice.subtract(spread.multiply(BigDecimal.valueOf(quoteDistanceFactor)));
        } else {
            quoteDistanceFactor = 1.0 + Math.min(0.0, pressure);
            rawPrice = centerPrice.add(spread.multiply(BigDecimal.valueOf(quoteDistanceFactor)));
        }
        return normalizePriceWithinDailyLimit(rawPrice.max(tick), config, tick);
    }

    BigDecimal createMarketMakingPrice(
            AutoMarketConfig config,
            String side,
            BigDecimal bestBid,
            BigDecimal bestAsk,
            double pressure,
            int depthTicks
    ) {
        int directionalTicks = (int) Math.round(Math.clamp(pressure, -1.0, 1.0) * 2.0);
        int normalizedDepthTicks = Math.max(0, depthTicks);
        BigDecimal anchorPrice = BUY.equals(side)
                ? (bestBid == null ? moveByTicks(config.market(), config.currentPrice(), -1) : bestBid)
                : (bestAsk == null ? moveByTicks(config.market(), config.currentPrice(), 1) : bestAsk);
        int quoteOffsetTicks = BUY.equals(side)
                ? directionalTicks - normalizedDepthTicks
                : directionalTicks + normalizedDepthTicks;
        BigDecimal rawPrice = moveByTicks(config.market(), anchorPrice, quoteOffsetTicks);
        BigDecimal tick = KoreanStockTickSizePolicy.tickSizeForQuotePrice(config.market(), rawPrice);
        return normalizePriceWithinDailyLimit(rawPrice.max(tick), config, tick);
    }

}
