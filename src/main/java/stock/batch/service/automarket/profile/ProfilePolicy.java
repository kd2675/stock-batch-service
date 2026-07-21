package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileConfig;
import stock.batch.service.batch.automarket.model.RecurringCashIntervalUnit;

public record ProfilePolicy(
        double newsWeight,
        double momentumWeight,
        double contrarianWeight,
        double lossAversionWeight,
        double herdingWeight,
        double marketMakingWeight,
        double overconfidenceWeight,
        double profitTakingWeight,
        double orderMultiplier,
        double aggressionMultiplier,
        double pricePressureSensitivity,
        double orderTtlMultiplier,
        double noiseWeight,
        double quantityMultiplier,
        double panicSellWeight,
        double dipBuyWeight,
        double holdingPatienceWeight,
        double deepLossHoldWeight,
        BigDecimal recurringDepositAmount,
        BigDecimal recurringDepositIntervalValue,
        RecurringCashIntervalUnit recurringDepositIntervalUnit
) {
    public ProfilePolicy(
            double newsWeight,
            double momentumWeight,
            double contrarianWeight,
            double lossAversionWeight,
            double herdingWeight,
            double marketMakingWeight,
            double overconfidenceWeight,
            double profitTakingWeight,
            double orderMultiplier,
            double aggressionMultiplier,
            double orderTtlMultiplier,
            double noiseWeight,
            double quantityMultiplier,
            double panicSellWeight,
            double dipBuyWeight,
            double holdingPatienceWeight,
            double deepLossHoldWeight,
            BigDecimal recurringDepositAmount,
            BigDecimal recurringDepositIntervalValue,
            RecurringCashIntervalUnit recurringDepositIntervalUnit
    ) {
        this(
                newsWeight,
                momentumWeight,
                contrarianWeight,
                lossAversionWeight,
                herdingWeight,
                marketMakingWeight,
                overconfidenceWeight,
                profitTakingWeight,
                orderMultiplier,
                aggressionMultiplier,
                1.0,
                orderTtlMultiplier,
                noiseWeight,
                quantityMultiplier,
                panicSellWeight,
                dipBuyWeight,
                holdingPatienceWeight,
                deepLossHoldWeight,
                recurringDepositAmount,
                recurringDepositIntervalValue,
                recurringDepositIntervalUnit
        );
    }

    public ProfilePolicy(
            double newsWeight,
            double momentumWeight,
            double contrarianWeight,
            double lossAversionWeight,
            double herdingWeight,
            double marketMakingWeight,
            double overconfidenceWeight,
            double profitTakingWeight,
            double orderMultiplier,
            double aggressionMultiplier,
            double orderTtlMultiplier,
            double noiseWeight,
            double quantityMultiplier,
            double panicSellWeight,
            double dipBuyWeight,
            double holdingPatienceWeight,
            double deepLossHoldWeight,
            BigDecimal recurringDepositAmount,
            int recurringDepositIntervalDays
    ) {
        this(
                newsWeight,
                momentumWeight,
                contrarianWeight,
                lossAversionWeight,
                herdingWeight,
                marketMakingWeight,
                overconfidenceWeight,
                profitTakingWeight,
                orderMultiplier,
                aggressionMultiplier,
                1.0,
                orderTtlMultiplier,
                noiseWeight,
                quantityMultiplier,
                panicSellWeight,
                dipBuyWeight,
                holdingPatienceWeight,
                deepLossHoldWeight,
                recurringDepositAmount,
                recurringDepositAmount == null || recurringDepositAmount.compareTo(BigDecimal.ZERO) <= 0
                        ? BigDecimal.ZERO
                        : BigDecimal.valueOf(recurringDepositIntervalDays),
                RecurringCashIntervalUnit.DAY
        );
    }

    public ProfilePolicy overrideWith(AutoParticipantProfileConfig config) {
        return new ProfilePolicy(
                ratioOrDefault(config.newsWeight(), newsWeight),
                ratioOrDefault(config.momentumWeight(), momentumWeight),
                ratioOrDefault(config.contrarianWeight(), contrarianWeight),
                ratioOrDefault(config.lossAversionWeight(), lossAversionWeight),
                ratioOrDefault(config.herdingWeight(), herdingWeight),
                ratioOrDefault(config.marketMakingWeight(), marketMakingWeight),
                ratioOrDefault(config.overconfidenceWeight(), overconfidenceWeight),
                ratioOrDefault(config.profitTakingWeight(), profitTakingWeight),
                positiveOrDefault(config.orderMultiplier(), orderMultiplier),
                positiveOrDefault(config.aggressionMultiplier(), aggressionMultiplier),
                boundedOrDefault(config.pricePressureSensitivity(), pricePressureSensitivity, 0.0, 2.0),
                positiveOrDefault(config.orderTtlMultiplier(), orderTtlMultiplier),
                ratioOrDefault(config.noiseWeight(), noiseWeight),
                positiveOrDefault(config.quantityMultiplier(), quantityMultiplier),
                ratioOrDefault(config.panicSellWeight(), panicSellWeight),
                ratioOrDefault(config.dipBuyWeight(), dipBuyWeight),
                ratioOrDefault(config.holdingPatienceWeight(), holdingPatienceWeight),
                ratioOrDefault(config.deepLossHoldWeight(), deepLossHoldWeight),
                nonNegativeOrDefault(config.recurringDepositAmount(), recurringDepositAmount),
                nonNegativeOrDefault(config.recurringDepositIntervalValue(), recurringDepositIntervalValue),
                unitOrDefault(config.recurringDepositIntervalUnit(), recurringDepositIntervalUnit)
        );
    }

    public ProfilePolicy withPricePressureSensitivity(double sensitivity) {
        return new ProfilePolicy(
                newsWeight,
                momentumWeight,
                contrarianWeight,
                lossAversionWeight,
                herdingWeight,
                marketMakingWeight,
                overconfidenceWeight,
                profitTakingWeight,
                orderMultiplier,
                aggressionMultiplier,
                Math.clamp(sensitivity, 0.0, 2.0),
                orderTtlMultiplier,
                noiseWeight,
                quantityMultiplier,
                panicSellWeight,
                dipBuyWeight,
                holdingPatienceWeight,
                deepLossHoldWeight,
                recurringDepositAmount,
                recurringDepositIntervalValue,
                recurringDepositIntervalUnit
        );
    }

    private static double positiveOrDefault(BigDecimal value, double defaultValue) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            return defaultValue;
        }
        return value.doubleValue();
    }

    private static double boundedOrDefault(
            BigDecimal value,
            double defaultValue,
            double minimum,
            double maximum
    ) {
        if (value == null) {
            return defaultValue;
        }
        double candidate = value.doubleValue();
        return candidate < minimum || candidate > maximum ? defaultValue : candidate;
    }

    private static double ratioOrDefault(BigDecimal value, double defaultValue) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            return defaultValue;
        }
        return value.doubleValue();
    }

    private static BigDecimal nonNegativeOrDefault(BigDecimal value, BigDecimal defaultValue) {
        return value == null || value.compareTo(BigDecimal.ZERO) < 0 ? defaultValue : value;
    }

    private static RecurringCashIntervalUnit unitOrDefault(RecurringCashIntervalUnit value, RecurringCashIntervalUnit defaultValue) {
        return value == null ? defaultValue : value;
    }
}
