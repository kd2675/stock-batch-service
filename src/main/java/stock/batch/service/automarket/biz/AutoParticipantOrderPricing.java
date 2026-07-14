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
        double pressureStrength = Math.abs(pressure);
        double volatility = config.volatilityMultiplier();
        double executionAggression = config.executionAggressionStrength();
        double aggressiveChance = Math.clamp(
                (0.18 + pressureStrength * 0.35 * volatility + executionAggression * 0.38)
                        * config.executionAggressionMultiplier()
                        * policy.aggressionMultiplier(),
                0.05,
                config.effectiveExecutionAggressionLevel() >= 10 ? 1.0 : 0.95
        );
        double crossingChance = Math.clamp(
                (executionAggression * 0.58 + config.effectiveLiquidityLevel() / 10.0 * 0.18 + pressureStrength * 0.10)
                        * policy.aggressionMultiplier(),
                0.02,
                config.effectiveExecutionAggressionLevel() >= 10 ? 1.0 : 0.85
        );
        boolean upwardAggressive = pressure > 0 && chance(aggressiveChance);
        boolean downwardAggressive = pressure < 0 && chance(aggressiveChance);
        boolean executionAggressive = chance(crossingChance);

        if (BUY.equals(side) && (upwardAggressive || executionAggressive) && bestAsk != null) {
            return normalizePriceWithinDailyLimit(moveByTicks(config.market(), bestAsk, nextInt(0, 1)), config, tick);
        }
        if (SELL.equals(side) && (downwardAggressive || executionAggressive) && bestBid != null) {
            return normalizePriceWithinDailyLimit(moveByTicks(config.market(), bestBid, -nextInt(0, 1)), config, tick);
        }

        int maxSpreadTicks = 2 + (int) Math.ceil(pressureStrength * 6 * volatility);
        BigDecimal spread = tick.multiply(BigDecimal.valueOf(nextInt(1, maxSpreadTicks)));
        BigDecimal directionalOffset = tick.multiply(BigDecimal.valueOf(Math.round(pressure * 2)));
        BigDecimal rawPrice;
        if (BUY.equals(side)) {
            rawPrice = config.currentPrice().add(directionalOffset).subtract(pressure < 0 ? spread : BigDecimal.ZERO);
        } else {
            rawPrice = config.currentPrice().add(directionalOffset).add(pressure > 0 ? spread : BigDecimal.ZERO);
        }
        return normalizePriceWithinDailyLimit(rawPrice.max(tick), config, tick);
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
        return normalizePriceWithinDailyLimit(adjustedPrice.max(tick), config, tick);
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
        return normalizePriceWithinDailyLimit(rawPrice.max(tick), config, tick);
    }

}
