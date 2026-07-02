package stock.batch.service.automarket.biz;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.ListingAutoAccountConfig;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class AutoMarketPricePolicy {

    static final BigDecimal DEFAULT_TICK_SIZE = BigDecimal.valueOf(100);
    private static final BigDecimal DEFAULT_PRICE_LIMIT_RATE = BigDecimal.valueOf(30);
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private AutoMarketPricePolicy() {
    }

    static BigDecimal normalizePrice(BigDecimal rawPrice, BigDecimal tick) {
        BigDecimal normalizedTick = positiveOrDefault(tick, DEFAULT_TICK_SIZE);
        BigDecimal ticks = rawPrice.divide(normalizedTick, 0, RoundingMode.HALF_UP);
        return ticks.multiply(normalizedTick).max(normalizedTick).setScale(2, RoundingMode.UNNECESSARY);
    }

    static BigDecimal normalizePriceWithinDailyLimit(BigDecimal rawPrice, AutoMarketConfig config, BigDecimal tick) {
        return normalizePriceWithinDailyLimit(
                rawPrice,
                tick,
                config.currentPrice(),
                config.previousClose(),
                config.priceLimitRate()
        );
    }

    static BigDecimal normalizePriceWithinDailyLimit(BigDecimal rawPrice, ListingAutoAccountConfig config, BigDecimal tick) {
        return normalizePriceWithinDailyLimit(
                rawPrice,
                tick,
                config.currentPrice(),
                config.previousClose(),
                config.priceLimitRate()
        );
    }

    static BigDecimal dailyLowerLimit(AutoMarketConfig config) {
        return dailyLowerLimit(config.currentPrice(), config.previousClose(), config.priceLimitRate());
    }

    static BigDecimal positiveOrDefault(BigDecimal value, BigDecimal defaultValue) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0 ? defaultValue : value;
    }

    private static BigDecimal normalizePriceWithinDailyLimit(
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

    private static BigDecimal dailyLowerLimit(BigDecimal currentPrice, BigDecimal previousClose, BigDecimal priceLimitRate) {
        BigDecimal normalizedPreviousClose = positiveOrDefault(previousClose, currentPrice);
        BigDecimal normalizedPriceLimitRate = positiveOrDefault(priceLimitRate, DEFAULT_PRICE_LIMIT_RATE);
        return normalizedPreviousClose
                .multiply(ONE_HUNDRED.subtract(normalizedPriceLimitRate))
                .divide(ONE_HUNDRED, 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal dailyUpperLimit(BigDecimal currentPrice, BigDecimal previousClose, BigDecimal priceLimitRate) {
        BigDecimal normalizedPreviousClose = positiveOrDefault(previousClose, currentPrice);
        BigDecimal normalizedPriceLimitRate = positiveOrDefault(priceLimitRate, DEFAULT_PRICE_LIMIT_RATE);
        return normalizedPreviousClose
                .multiply(ONE_HUNDRED.add(normalizedPriceLimitRate))
                .divide(ONE_HUNDRED, 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal ceilToTick(BigDecimal value, BigDecimal tick) {
        return value.divide(tick, 0, RoundingMode.CEILING)
                .multiply(tick)
                .setScale(2, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal floorToTick(BigDecimal value, BigDecimal tick) {
        return value.divide(tick, 0, RoundingMode.FLOOR)
                .multiply(tick)
                .setScale(2, RoundingMode.UNNECESSARY);
    }
}
