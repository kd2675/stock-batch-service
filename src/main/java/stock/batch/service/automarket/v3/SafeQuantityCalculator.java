package stock.batch.service.automarket.v3;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;

public final class SafeQuantityCalculator {

    private static final long UNBOUNDED = Long.MAX_VALUE;

    public SafeQuantityLimit calculate(LimitInput input) {
        if (input == null) {
            throw new IllegalArgumentException("Quantity limit input is required");
        }
        long buyAffordability = input.buy()
                ? affordableQuantity(input.cashAvailable(), input.price(), input.buyFeeRate())
                : UNBOUNDED;
        long sellableHolding = input.buy() ? UNBOUNDED : nonNegative(input.sellableHolding());
        long oppositeDepth = input.aggressive()
                ? nonNegative(input.oppositeDepthCapacity())
                : UNBOUNDED;
        LinkedHashMap<SafeQuantityBindingReason, Long> limits = new LinkedHashMap<>();
        limits.put(SafeQuantityBindingReason.SYMBOL_MAX, nonNegative(input.symbolMaximum()));
        limits.put(SafeQuantityBindingReason.OPEN_ORDER_ALLOWANCE, nonNegative(input.remainingOpenOrderAllowance()));
        limits.put(SafeQuantityBindingReason.BUY_AFFORDABILITY, buyAffordability);
        limits.put(SafeQuantityBindingReason.SELLABLE_HOLDING, sellableHolding);
        limits.put(SafeQuantityBindingReason.ALLOCATION, nonNegative(input.allocationCapacity()));
        limits.put(SafeQuantityBindingReason.FUNDING_BUDGET, nonNegative(input.fundingBudgetCapacity()));
        limits.put(SafeQuantityBindingReason.OPPOSITE_DEPTH, oppositeDepth);
        limits.put(SafeQuantityBindingReason.AVERAGE_DAILY_VOLUME, nonNegative(input.averageDailyVolumeCapacity()));
        limits.put(
                SafeQuantityBindingReason.EMERGENCY_DAILY_TURNOVER,
                nonNegative(input.emergencyDailyTurnoverCapacity())
        );
        Map.Entry<SafeQuantityBindingReason, Long> binding = limits.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .orElseThrow();
        return new SafeQuantityLimit(
                limits.get(SafeQuantityBindingReason.SYMBOL_MAX),
                limits.get(SafeQuantityBindingReason.OPEN_ORDER_ALLOWANCE),
                buyAffordability,
                sellableHolding,
                limits.get(SafeQuantityBindingReason.ALLOCATION),
                limits.get(SafeQuantityBindingReason.FUNDING_BUDGET),
                oppositeDepth,
                limits.get(SafeQuantityBindingReason.AVERAGE_DAILY_VOLUME),
                limits.get(SafeQuantityBindingReason.EMERGENCY_DAILY_TURNOVER),
                binding.getValue(),
                binding.getKey()
        );
    }

    public QuantitySample sample(
            SafeQuantityLimit limit,
            AutoParticipantDecisionUrgency urgency,
            long behaviorSeed,
            LocalDate tradeDate,
            long eventSequence,
            AutoParticipantV3Policy policy
    ) {
        if (limit == null || limit.safeMaximum() <= 0) {
            return new QuantitySample(0L, false, limit == null ? null : limit.bindingReason());
        }
        AutoParticipantDecisionUrgency resolvedUrgency = urgency == null
                ? AutoParticipantDecisionUrgency.VOLUNTARY
                : urgency;
        SplittableRandom largeOrderRandom = AutoParticipantV3Random.stream(
                behaviorSeed,
                tradeDate,
                policy.policyVersion(),
                eventSequence,
                AutoParticipantRandomStream.LARGE_ORDER,
                resolvedUrgency.name()
        );
        boolean rareLargeOrder = resolvedUrgency == AutoParticipantDecisionUrgency.VOLUNTARY
                && largeOrderRandom.nextDouble() < policy.rareLargeOrderProbability();
        double gamma = switch (resolvedUrgency) {
            case VOLUNTARY -> rareLargeOrder ? 0.70 : policy.ordinaryQuantityGamma();
            case RISK_REDUCTION -> 1.35;
            case MANDATORY_CLOSE -> 0.75;
            case OPERATIONAL_QUOTE -> 2.0;
        };
        double unit = AutoParticipantV3Random.stream(
                behaviorSeed,
                tradeDate,
                policy.policyVersion(),
                eventSequence,
                AutoParticipantRandomStream.QUANTITY,
                resolvedUrgency.name() + ":" + rareLargeOrder
        ).nextDouble();
        long sampled = Math.max(1L, (long) Math.floor(limit.safeMaximum() * Math.pow(unit, gamma)));
        return new QuantitySample(Math.min(sampled, limit.safeMaximum()), rareLargeOrder, limit.bindingReason());
    }

    private long affordableQuantity(BigDecimal cash, BigDecimal price, BigDecimal feeRate) {
        if (cash == null || price == null || feeRate == null
                || cash.signum() <= 0 || price.signum() <= 0 || feeRate.signum() < 0) {
            return 0L;
        }
        BigDecimal unitCost = price.multiply(BigDecimal.ONE.add(feeRate));
        return cash.divide(unitCost, 0, RoundingMode.DOWN).longValue();
    }

    private long nonNegative(long value) {
        return Math.max(0L, value);
    }

    public record LimitInput(
            boolean buy,
            boolean aggressive,
            BigDecimal cashAvailable,
            BigDecimal price,
            BigDecimal buyFeeRate,
            long symbolMaximum,
            long remainingOpenOrderAllowance,
            long sellableHolding,
            long allocationCapacity,
            long fundingBudgetCapacity,
            long oppositeDepthCapacity,
            long averageDailyVolumeCapacity,
            long emergencyDailyTurnoverCapacity
    ) {
        public LimitInput {
            if (price == null || price.signum() <= 0) {
                throw new IllegalArgumentException("Positive order price is required");
            }
            if (buyFeeRate == null || buyFeeRate.signum() < 0) {
                throw new IllegalArgumentException("Non-negative buy fee rate is required");
            }
        }
    }

    public record QuantitySample(
            long quantity,
            boolean rareLargeOrder,
            SafeQuantityBindingReason bindingReason
    ) {
    }
}
