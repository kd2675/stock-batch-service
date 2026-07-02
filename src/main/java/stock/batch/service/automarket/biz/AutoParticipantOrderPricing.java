package stock.batch.service.automarket.biz;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

import stock.batch.service.automarket.profile.ProfilePolicy;
import stock.batch.service.batch.automarket.model.AutoMarketConfig;

import static stock.batch.service.automarket.biz.AutoMarketPricePolicy.normalizePriceWithinDailyLimit;
import static stock.batch.service.automarket.support.AutoMarketRandomSupport.chance;
import static stock.batch.service.automarket.support.AutoMarketRandomSupport.nextInt;
import static stock.batch.service.automarket.support.AutoMarketRandomSupport.noise;

@Component
class AutoParticipantOrderPricing {

    private static final String BUY = "BUY";
    private static final String SELL = "SELL";

    BigDecimal createAutoPrice(
            AutoMarketConfig config,
            int intensity,
            String side,
            ProfilePolicy policy,
            AutoMarketOrderBookState orderBookState
    ) {
        BigDecimal bestBid = orderBookState.bestBid();
        BigDecimal bestAsk = orderBookState.bestAsk();
        BigDecimal tick = config.tickSize();
        if (policy.marketMakingWeight() >= 0.8) {
            return createMarketMakingPrice(config, side, bestBid, bestAsk, tick);
        }
        double pressure = Math.clamp(pricePressure(intensity) + noise(policy.noiseWeight(), 0.12), -1, 1);
        double pressureStrength = Math.abs(pressure);
        double aggressiveChance = Math.clamp((0.35 + pressureStrength * 0.45) * policy.aggressionMultiplier(), 0.05, 0.95);
        boolean upwardAggressive = pressure > 0 && chance(aggressiveChance);
        boolean downwardAggressive = pressure < 0 && chance(aggressiveChance);

        if (BUY.equals(side) && upwardAggressive && bestAsk != null) {
            return normalizePriceWithinDailyLimit(bestAsk.add(tick.multiply(BigDecimal.valueOf(nextInt(0, 1)))), config, tick);
        }
        if (SELL.equals(side) && downwardAggressive && bestBid != null) {
            return normalizePriceWithinDailyLimit(bestBid.subtract(tick.multiply(BigDecimal.valueOf(nextInt(0, 1)))).max(tick), config, tick);
        }

        int maxSpreadTicks = 2 + (int) Math.ceil(pressureStrength * 6);
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

    private double pricePressure(int intensity) {
        return (Math.clamp(intensity, 1, 10) - 5.5) / 4.5;
    }

}
