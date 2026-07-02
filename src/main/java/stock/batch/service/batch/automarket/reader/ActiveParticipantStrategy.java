package stock.batch.service.batch.automarket.reader;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;
import stock.batch.service.batch.automarket.model.RecurringCashIntervalUnit;

record ActiveParticipantStrategy(
        String userKey,
        long accountId,
        AutoParticipantProfileType profileType,
        BigDecimal recurringCashAmount,
        BigDecimal recurringCashIntervalValue,
        RecurringCashIntervalUnit recurringCashIntervalUnit
) {
    AutoParticipantStrategy toStrategy(int intensity) {
        return new AutoParticipantStrategy(
                userKey,
                accountId,
                intensity,
                profileType,
                recurringCashAmount,
                recurringCashIntervalValue,
                recurringCashIntervalUnit
        );
    }
}
