package stock.batch.service.batch.automarket.model;

import java.math.BigDecimal;

public record ListingAutoAccountConfig(
        String symbol,
        String market,
        long accountId,
        String userKey,
        String positionSide,
        int maxOrderQuantity,
        int orderTtlSeconds,
        int priceOffsetTicks,
        BigDecimal tickSize,
        BigDecimal currentPrice,
        BigDecimal previousClose,
        BigDecimal priceLimitRate
) {
    public ListingAutoAccountConfig(
            String symbol,
            long accountId,
            String userKey,
            String positionSide,
            int maxOrderQuantity,
            int orderTtlSeconds,
            int priceOffsetTicks,
            BigDecimal tickSize,
            BigDecimal currentPrice,
            BigDecimal previousClose,
            BigDecimal priceLimitRate
    ) {
        this(
                symbol,
                "ORDERBOOK",
                accountId,
                userKey,
                positionSide,
                maxOrderQuantity,
                orderTtlSeconds,
                priceOffsetTicks,
                tickSize,
                currentPrice,
                previousClose,
                priceLimitRate
        );
    }
}
