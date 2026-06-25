package stock.batch.service.batch.automarket.model;

import java.math.BigDecimal;

public record AutoMarketConfig(
        String symbol,
        int intensity,
        int maxOrderQuantity,
        int orderTtlSeconds,
        long tradableShares,
        BigDecimal tickSize,
        BigDecimal currentPrice,
        BigDecimal previousClose,
        BigDecimal priceLimitRate,
        Integer reportScore
) {
    public AutoMarketConfig(
            String symbol,
            int intensity,
            int maxOrderQuantity,
            int orderTtlSeconds,
            long tradableShares,
            BigDecimal tickSize,
            BigDecimal currentPrice,
            BigDecimal previousClose,
            Integer reportScore
    ) {
        this(
                symbol,
                intensity,
                maxOrderQuantity,
                orderTtlSeconds,
                tradableShares,
                tickSize,
                currentPrice,
                previousClose,
                BigDecimal.valueOf(30),
                reportScore
        );
    }
}
