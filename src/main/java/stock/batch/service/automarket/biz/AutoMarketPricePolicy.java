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

    static BigDecimal normalizePrice(String market, BigDecimal rawPrice) {
        return KoreanStockTickSizePolicy.nearestValidQuotePrice(market, rawPrice);
    }

    static BigDecimal moveByTicks(String market, BigDecimal price, int ticks) {
        BigDecimal currentPrice = normalizePrice(market, price);
        int steps = Math.abs(ticks);
        for (int index = 0; index < steps; index++) {
            currentPrice = ticks >= 0
                    ? KoreanStockTickSizePolicy.ceilingValidQuotePrice(market, currentPrice.add(BigDecimal.ONE))
                    : KoreanStockTickSizePolicy.floorValidQuotePrice(market, currentPrice.subtract(BigDecimal.ONE));
        }
        return currentPrice;
    }

    static BigDecimal normalizePriceWithinDailyLimit(BigDecimal rawPrice, AutoMarketConfig config, BigDecimal tick) {
        return normalizePriceWithinDailyLimit(
                config.market(),
                rawPrice,
                config.currentPrice(),
                config.previousClose(),
                config.priceLimitRate()
        );
    }

    static BigDecimal normalizePriceWithinDailyLimit(BigDecimal rawPrice, ListingAutoAccountConfig config, BigDecimal tick) {
        return normalizePriceWithinDailyLimit(
                config.market(),
                rawPrice,
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
            String market,
            BigDecimal rawPrice,
            BigDecimal currentPrice,
            BigDecimal previousClose,
            BigDecimal priceLimitRate
    ) {
        BigDecimal lowerLimit = dailyLowerLimit(currentPrice, previousClose, priceLimitRate);
        BigDecimal upperLimit = dailyUpperLimit(currentPrice, previousClose, priceLimitRate);
        BigDecimal clampedPrice = rawPrice.max(lowerLimit).min(upperLimit);
        BigDecimal normalizedPrice = normalizePrice(market, clampedPrice);
        if (normalizedPrice.compareTo(lowerLimit) < 0) {
            normalizedPrice = KoreanStockTickSizePolicy.ceilingValidQuotePrice(market, lowerLimit);
        }
        if (normalizedPrice.compareTo(upperLimit) > 0) {
            normalizedPrice = KoreanStockTickSizePolicy.floorValidQuotePrice(market, upperLimit);
        }
        BigDecimal minimumTick = KoreanStockTickSizePolicy.tickSizeForQuotePrice(market, normalizedPrice);
        return normalizedPrice.max(minimumTick).setScale(2, RoundingMode.UNNECESSARY);
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

}
