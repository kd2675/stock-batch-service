package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.RecurringCashIntervalUnit;

public record ProfileFundingPolicy(
        BigDecimal recurringDepositAmount,
        BigDecimal recurringDepositIntervalValue,
        RecurringCashIntervalUnit recurringDepositIntervalUnit
) {
    public ProfileFundingPolicy {
        recurringDepositAmount = nonNegative(recurringDepositAmount);
        recurringDepositIntervalValue = nonNegative(recurringDepositIntervalValue);
        recurringDepositIntervalUnit = recurringDepositIntervalUnit == null
                ? RecurringCashIntervalUnit.DAY
                : recurringDepositIntervalUnit;
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
    }
}
