package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;

final class KoreanStockTickSizePolicy {

    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal FIVE = BigDecimal.valueOf(5);
    private static final BigDecimal TEN = BigDecimal.TEN;
    private static final BigDecimal FIFTY = BigDecimal.valueOf(50);
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal FIVE_HUNDRED = BigDecimal.valueOf(500);
    private static final BigDecimal ONE_THOUSAND = BigDecimal.valueOf(1_000);
    private static final BigDecimal TWO_THOUSAND = BigDecimal.valueOf(2_000);
    private static final BigDecimal FIVE_THOUSAND = BigDecimal.valueOf(5_000);
    private static final BigDecimal TWENTY_THOUSAND = BigDecimal.valueOf(20_000);
    private static final BigDecimal FIFTY_THOUSAND = BigDecimal.valueOf(50_000);
    private static final BigDecimal TWO_HUNDRED_THOUSAND = BigDecimal.valueOf(200_000);
    private static final BigDecimal FIVE_HUNDRED_THOUSAND = BigDecimal.valueOf(500_000);
    private static final Set<String> FIXED_FIVE_TICK_MARKETS = Set.of("ETF", "ETN", "ELW");

    private KoreanStockTickSizePolicy() {
    }

    static BigDecimal tickSizeForQuotePrice(String market, BigDecimal quotePrice) {
        if (isFixedFiveTickMarket(market)) {
            return FIVE;
        }
        BigDecimal normalizedPrice = positiveOrOne(quotePrice);
        if (normalizedPrice.compareTo(TWO_THOUSAND) < 0) {
            return ONE;
        }
        if (normalizedPrice.compareTo(FIVE_THOUSAND) < 0) {
            return FIVE;
        }
        if (normalizedPrice.compareTo(TWENTY_THOUSAND) < 0) {
            return TEN;
        }
        if (normalizedPrice.compareTo(FIFTY_THOUSAND) < 0) {
            return FIFTY;
        }
        if (normalizedPrice.compareTo(TWO_HUNDRED_THOUSAND) < 0) {
            return ONE_HUNDRED;
        }
        if (normalizedPrice.compareTo(FIVE_HUNDRED_THOUSAND) < 0) {
            return FIVE_HUNDRED;
        }
        return ONE_THOUSAND;
    }

    static BigDecimal nearestValidQuotePrice(String market, BigDecimal rawPrice) {
        return alignToValidQuotePrice(market, rawPrice, RoundingMode.HALF_UP);
    }

    static BigDecimal ceilingValidQuotePrice(String market, BigDecimal rawPrice) {
        return alignToValidQuotePrice(market, rawPrice, RoundingMode.CEILING);
    }

    static BigDecimal floorValidQuotePrice(String market, BigDecimal rawPrice) {
        return alignToValidQuotePrice(market, rawPrice, RoundingMode.FLOOR);
    }

    private static BigDecimal alignToValidQuotePrice(String market, BigDecimal rawPrice, RoundingMode roundingMode) {
        BigDecimal candidate = positiveOrOne(rawPrice);
        for (int attempt = 0; attempt < 3; attempt++) {
            BigDecimal tickSize = tickSizeForQuotePrice(market, candidate);
            candidate = candidate.divide(tickSize, 0, roundingMode)
                    .multiply(tickSize)
                    .max(tickSize);
            if (isValidQuotePrice(market, candidate)) {
                return candidate.setScale(2, RoundingMode.UNNECESSARY);
            }
        }
        return candidate.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static boolean isValidQuotePrice(String market, BigDecimal quotePrice) {
        if (quotePrice == null || quotePrice.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        BigDecimal tickSize = tickSizeForQuotePrice(market, quotePrice);
        return quotePrice.remainder(tickSize).compareTo(BigDecimal.ZERO) == 0;
    }

    private static BigDecimal positiveOrOne(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0 ? ONE : value;
    }

    private static boolean isFixedFiveTickMarket(String market) {
        if (market == null) {
            return false;
        }
        return FIXED_FIVE_TICK_MARKETS.contains(market.trim().toUpperCase(Locale.ROOT));
    }
}
