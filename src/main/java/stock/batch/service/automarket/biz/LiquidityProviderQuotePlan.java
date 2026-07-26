package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.util.List;

import stock.batch.service.batch.automarket.model.AutoOrder;

record LiquidityProviderQuotePlan(
        String stateStatus,
        String gateReason,
        boolean limitBreached,
        boolean submitOrders,
        List<AutoOrder> cancellationOrders,
        List<AutoMarketPlannedOrder> proposedOrders,
        long referenceDailyVolume,
        long executionQuantityLimit,
        long submissionQuantityLimit,
        long targetBuyOpenQuantity,
        long targetSellOpenQuantity,
        long retainedBuyOpenQuantity,
        long retainedSellOpenQuantity,
        BigDecimal bidPrice,
        BigDecimal askPrice,
        long inventoryQuantity,
        long projectedInventoryQuantity,
        long externalBuyDepthQuantity,
        long externalSellDepthQuantity,
        double blendedPricePressure,
        double blendedVolatilityPressure,
        double blendedLiquidityPressure,
        BigDecimal unrealizedProfit,
        BigDecimal openingNetAssetValue,
        BigDecimal currentNetAssetValue,
        BigDecimal riskProfit
) {

    LiquidityProviderQuotePlan {
        cancellationOrders = cancellationOrders == null ? List.of() : List.copyOf(cancellationOrders);
        proposedOrders = proposedOrders == null ? List.of() : List.copyOf(proposedOrders);
    }

    List<AutoMarketPlannedOrder> executableOrders() {
        return submitOrders ? proposedOrders : List.of();
    }

    long cancelledQuantity(String side) {
        return cancellationOrders.stream()
                .filter(order -> side.equals(order.side()))
                .mapToLong(AutoOrder::remainingQuantity)
                .sum();
    }

    long submittedQuantity(String side) {
        return executableOrders().stream()
                .filter(order -> side.equals(order.side()))
                .mapToLong(AutoMarketPlannedOrder::quantity)
                .sum();
    }

    BigDecimal submittedAmount(String side) {
        return executableOrders().stream()
                .filter(order -> side.equals(order.side()))
                .map(order -> order.price().multiply(BigDecimal.valueOf(order.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }

    long resultingOpenQuantity(String side) {
        long retained = "BUY".equals(side) ? retainedBuyOpenQuantity : retainedSellOpenQuantity;
        long proposed = proposedOrders.stream()
                .filter(order -> side.equals(order.side()))
                .mapToLong(AutoMarketPlannedOrder::quantity)
                .sum();
        if (retained > Long.MAX_VALUE - proposed) {
            return Long.MAX_VALUE;
        }
        return retained + proposed;
    }
}
