package stock.batch.service.batch.automarket.model;

import java.math.BigDecimal;

public record AutoParticipantRecurringCashTarget(
        long accountId,
        AutoParticipantProfileType profileType,
        BigDecimal recurringCashAmount,
        BigDecimal recurringCashIntervalValue,
        RecurringCashIntervalUnit recurringCashIntervalUnit
) {
}
