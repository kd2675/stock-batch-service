package stock.batch.service.batch.automarket.model;

import java.math.BigDecimal;

public record ListingAutoAccountConfig(
        String symbol,
        String market,
        long accountId,
        String userKey,
        String positionSide,
        String operationMode,
        String strategyProfile,
        long initialInventoryQuantity,
        BigDecimal initialIssuePrice,
        int maxOrderQuantity,
        int orderTtlSeconds,
        int priceOffsetTicks,
        int targetSpreadTicks,
        int inventorySkewTicks,
        BigDecimal minimumProfitRate,
        BigDecimal aggressiveUnwindThreshold,
        BigDecimal aggressiveOrderRatio,
        long targetBuyQuantity,
        long targetSellQuantity,
        long targetHoldingQuantity,
        long inventoryBandQuantity,
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
            String operationMode,
            String strategyProfile,
            long initialInventoryQuantity,
            BigDecimal initialIssuePrice,
            int maxOrderQuantity,
            int orderTtlSeconds,
            int priceOffsetTicks,
            int targetSpreadTicks,
            int inventorySkewTicks,
            BigDecimal minimumProfitRate,
            BigDecimal aggressiveUnwindThreshold,
            BigDecimal aggressiveOrderRatio,
            long targetBuyQuantity,
            long targetSellQuantity,
            long targetHoldingQuantity,
            long inventoryBandQuantity,
            BigDecimal tickSize,
            BigDecimal currentPrice,
            BigDecimal previousClose,
            BigDecimal priceLimitRate
    ) {
        this(symbol, "ORDERBOOK", accountId, userKey, positionSide, operationMode, strategyProfile,
                initialInventoryQuantity, initialIssuePrice, maxOrderQuantity, orderTtlSeconds, priceOffsetTicks,
                targetSpreadTicks, inventorySkewTicks, minimumProfitRate, aggressiveUnwindThreshold,
                aggressiveOrderRatio, targetBuyQuantity, targetSellQuantity, targetHoldingQuantity,
                inventoryBandQuantity, tickSize, currentPrice, previousClose, priceLimitRate);
    }
}
