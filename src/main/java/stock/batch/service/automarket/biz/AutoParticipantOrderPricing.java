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
        BigDecimal tick = KoreanStockTickSizePolicy.tickSizeForQuotePrice(config.market(), config.currentPrice());
        if (policy.marketMakingWeight() >= 0.8) {
            return createMarketMakingPrice(config, side, bestBid, bestAsk);
        }
        double activityStrength = Math.clamp(strategyActivityLevel, 1, 10) / 10.0;
        double directionalPressure = config.dailyPricePressure()
                + config.reportPricePressure() * policy.newsWeight() * 0.55;
        double pressure = Math.clamp(directionalPressure * activityStrength + noise(policy.noiseWeight(), 0.12), -1, 1);
        double volatility = config.volatilityMultiplier();
        boolean crossesOppositeQuote = chance(crossingChance(config, side, policy, pressure));

        if (BUY.equals(side) && crossesOppositeQuote && bestAsk != null) {
            return normalizePriceWithinDailyLimit(moveByTicks(config.market(), bestAsk, nextInt(0, 1)), config, tick);
        }
        if (SELL.equals(side) && crossesOppositeQuote && bestBid != null) {
            return normalizePriceWithinDailyLimit(moveByTicks(config.market(), bestBid, -nextInt(0, 1)), config, tick);
        }

        return createPassivePrice(config, side, pressure, volatility, orderBookState);
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

    BigDecimal createPassivePrice(
            AutoMarketConfig config,
            String side,
            double pressure,
            double volatility,
            AutoMarketOrderBookState orderBookState
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
        BigDecimal rawPrice;
        if (BUY.equals(side)) {
            rawPrice = config.currentPrice().add(directionalOffset).subtract(pressure < 0 ? spread : BigDecimal.ZERO);
        } else {
            rawPrice = config.currentPrice().add(directionalOffset).add(pressure > 0 ? spread : BigDecimal.ZERO);
        }
        BigDecimal normalizedPrice = normalizePriceWithinDailyLimit(rawPrice.max(tick), config, tick);
        return keepOutsideOppositeQuote(config, side, normalizedPrice, orderBookState, tick);
    }

    BigDecimal avoidSelfCross(
            AutoMarketConfig config,
            String side,
            BigDecimal proposedPrice,
            BigDecimal ownBestBid,
            BigDecimal ownBestAsk
    ) {
        BigDecimal adjustedPrice = proposedPrice;
        if (BUY.equals(side) && ownBestAsk != null && adjustedPrice.compareTo(ownBestAsk) >= 0) {
            adjustedPrice = moveByTicks(config.market(), ownBestAsk, -1);
        }
        if (SELL.equals(side) && ownBestBid != null && adjustedPrice.compareTo(ownBestBid) <= 0) {
            adjustedPrice = moveByTicks(config.market(), ownBestBid, 1);
        }
        BigDecimal tick = KoreanStockTickSizePolicy.tickSizeForQuotePrice(config.market(), adjustedPrice);
        BigDecimal normalizedPrice = normalizePriceWithinDailyLimit(adjustedPrice.max(tick), config, tick);
        if (BUY.equals(side) && ownBestAsk != null && normalizedPrice.compareTo(ownBestAsk) >= 0) {
            return BigDecimal.ZERO;
        }
        if (SELL.equals(side) && ownBestBid != null && normalizedPrice.compareTo(ownBestBid) <= 0) {
            return BigDecimal.ZERO;
        }
        return normalizedPrice;
    }

    private BigDecimal createMarketMakingPrice(
            AutoMarketConfig config,
            String side,
            BigDecimal bestBid,
            BigDecimal bestAsk
    ) {
        BigDecimal rawPrice;
        if (BUY.equals(side)) {
            rawPrice = bestBid == null ? moveByTicks(config.market(), config.currentPrice(), -1) : bestBid;
            if (bestAsk != null && rawPrice.compareTo(bestAsk) >= 0) {
                rawPrice = moveByTicks(config.market(), bestAsk, -1);
            }
        } else {
            rawPrice = bestAsk == null ? moveByTicks(config.market(), config.currentPrice(), 1) : bestAsk;
            if (bestBid != null && rawPrice.compareTo(bestBid) <= 0) {
                rawPrice = moveByTicks(config.market(), bestBid, 1);
            }
        }
        BigDecimal tick = KoreanStockTickSizePolicy.tickSizeForQuotePrice(config.market(), rawPrice);
        BigDecimal normalizedPrice = normalizePriceWithinDailyLimit(rawPrice.max(tick), config, tick);
        if (BUY.equals(side) && bestAsk != null && normalizedPrice.compareTo(bestAsk) >= 0) {
            return BigDecimal.ZERO;
        }
        if (SELL.equals(side) && bestBid != null && normalizedPrice.compareTo(bestBid) <= 0) {
            return BigDecimal.ZERO;
        }
        return normalizedPrice;
    }

    private BigDecimal keepOutsideOppositeQuote(
            AutoMarketConfig config,
            String side,
            BigDecimal proposedPrice,
            AutoMarketOrderBookState orderBookState,
            BigDecimal tick
    ) {
        BigDecimal bestBid = orderBookState.bestBid();
        BigDecimal bestAsk = orderBookState.bestAsk();
        if (BUY.equals(side) && bestAsk != null && proposedPrice.compareTo(bestAsk) >= 0) {
            BigDecimal passivePrice = moveByTicks(config.market(), bestAsk, -1);
            BigDecimal normalizedPrice = normalizePriceWithinDailyLimit(passivePrice, config, tick);
            return normalizedPrice.compareTo(bestAsk) < 0 ? normalizedPrice : BigDecimal.ZERO;
        }
        if (SELL.equals(side) && bestBid != null && proposedPrice.compareTo(bestBid) <= 0) {
            BigDecimal passivePrice = moveByTicks(config.market(), bestBid, 1);
            BigDecimal normalizedPrice = normalizePriceWithinDailyLimit(passivePrice, config, tick);
            return normalizedPrice.compareTo(bestBid) > 0 ? normalizedPrice : BigDecimal.ZERO;
        }
        return proposedPrice;
    }

}
