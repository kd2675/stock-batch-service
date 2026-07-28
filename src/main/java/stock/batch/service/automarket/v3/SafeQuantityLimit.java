package stock.batch.service.automarket.v3;

public record SafeQuantityLimit(
        long symbolMaximum,
        long remainingOpenOrderAllowance,
        long buyAffordability,
        long sellableHolding,
        long allocationCapacity,
        long fundingBudgetCapacity,
        long oppositeDepthCapacity,
        long averageDailyVolumeCapacity,
        long emergencyDailyTurnoverCapacity,
        long safeMaximum,
        SafeQuantityBindingReason bindingReason
) {
}
