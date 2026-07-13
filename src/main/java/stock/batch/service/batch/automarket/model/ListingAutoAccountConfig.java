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
        long targetBuyQuantity,
        long targetSellQuantity,
        long targetHoldingQuantity,
        long inventoryBandQuantity,
        String buyPriceOffsetDirection,
        String sellPriceOffsetDirection,
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
                accountId,
                userKey,
                positionSide,
                maxOrderQuantity,
                orderTtlSeconds,
                priceOffsetTicks,
                "BUY_ONLY".equals(positionSide) ? maxOrderQuantity : 0L,
                "SELL_ONLY".equals(positionSide) ? maxOrderQuantity : 0L,
                0L,
                0L,
                "DOWN",
                "UP",
                tickSize,
                currentPrice,
                previousClose,
                priceLimitRate
        );
    }

    public ListingAutoAccountConfig(
            String symbol,
            long accountId,
            String userKey,
            String positionSide,
            int maxOrderQuantity,
            int orderTtlSeconds,
            int priceOffsetTicks,
            long targetBuyQuantity,
            long targetSellQuantity,
            long targetHoldingQuantity,
            String buyPriceOffsetDirection,
            String sellPriceOffsetDirection,
            BigDecimal tickSize,
            BigDecimal currentPrice,
            BigDecimal previousClose,
            BigDecimal priceLimitRate
    ) {
        this(
                symbol,
                accountId,
                userKey,
                positionSide,
                maxOrderQuantity,
                orderTtlSeconds,
                priceOffsetTicks,
                targetBuyQuantity,
                targetSellQuantity,
                targetHoldingQuantity,
                0L,
                buyPriceOffsetDirection,
                sellPriceOffsetDirection,
                tickSize,
                currentPrice,
                previousClose,
                priceLimitRate
        );
    }

    public ListingAutoAccountConfig(
            String symbol,
            long accountId,
            String userKey,
            String positionSide,
            int maxOrderQuantity,
            int orderTtlSeconds,
            int priceOffsetTicks,
            long targetBuyQuantity,
            long targetSellQuantity,
            long targetHoldingQuantity,
            long inventoryBandQuantity,
            String buyPriceOffsetDirection,
            String sellPriceOffsetDirection,
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
                targetBuyQuantity,
                targetSellQuantity,
                targetHoldingQuantity,
                inventoryBandQuantity,
                buyPriceOffsetDirection,
                sellPriceOffsetDirection,
                tickSize,
                currentPrice,
                previousClose,
                priceLimitRate
        );
    }
}
