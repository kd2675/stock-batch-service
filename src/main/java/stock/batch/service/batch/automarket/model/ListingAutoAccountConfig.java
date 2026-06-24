package stock.batch.service.batch.automarket.model;

import java.math.BigDecimal;

public record ListingAutoAccountConfig(
        String symbol,
        long accountId,
        String userKey,
        String positionSide,
        int maxOrderQuantity,
        int orderTtlSeconds,
        int priceOffsetTicks,
        BigDecimal tickSize,
        BigDecimal currentPrice
) {
}
