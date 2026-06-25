package stock.batch.service.batch.automarket.model;

import java.math.BigDecimal;

public record AutoParticipantStrategy(
        String userKey,
        long accountId,
        int intensity,
        AutoParticipantProfileType profileType,
        BigDecimal recurringCashAmount,
        BigDecimal recurringCashIntervalValue,
        RecurringCashIntervalUnit recurringCashIntervalUnit
) {
    public AutoParticipantStrategy(long accountId, int intensity, AutoParticipantProfileType profileType) {
        this("", accountId, intensity, profileType, null, null, null);
    }

    public AutoParticipantStrategy(String userKey, long accountId, int intensity, AutoParticipantProfileType profileType) {
        this(userKey, accountId, intensity, profileType, null, null, null);
    }
}
