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
        Integer reportScore
) {
}
