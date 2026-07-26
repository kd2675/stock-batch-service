package stock.batch.service.automarket.biz;

import java.math.BigDecimal;

record InstitutionDailyBudgetSnapshot(
        long referenceDailyVolume,
        long grossQuantityLimit,
        BigDecimal grossNotionalLimit,
        long plannedBuyQuantity,
        long plannedSellQuantity,
        BigDecimal plannedBuyAmount,
        BigDecimal plannedSellAmount,
        long policyVersion,
        long version
) {

    InstitutionDailyBudgetSnapshot {
        referenceDailyVolume = Math.max(1L, referenceDailyVolume);
        grossQuantityLimit = Math.max(1L, grossQuantityLimit);
        grossNotionalLimit = nonNegative(grossNotionalLimit);
        plannedBuyQuantity = Math.max(0L, plannedBuyQuantity);
        plannedSellQuantity = Math.max(0L, plannedSellQuantity);
        plannedBuyAmount = nonNegative(plannedBuyAmount);
        plannedSellAmount = nonNegative(plannedSellAmount);
        version = Math.max(0L, version);
    }

    long plannedGrossQuantity() {
        return saturatingAdd(plannedBuyQuantity, plannedSellQuantity);
    }

    BigDecimal plannedGrossAmount() {
        return plannedBuyAmount.add(plannedSellAmount);
    }

    long remainingQuantity() {
        return Math.max(0L, grossQuantityLimit - Math.min(grossQuantityLimit, plannedGrossQuantity()));
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
    }

    private static long saturatingAdd(long left, long right) {
        if (right > Long.MAX_VALUE - left) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
