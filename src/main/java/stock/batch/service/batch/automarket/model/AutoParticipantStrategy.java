package stock.batch.service.batch.automarket.model;

import web.common.core.utils.DeterministicSeed;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AutoParticipantStrategy(
        String userKey,
        long accountId,
        int intensity,
        AutoParticipantProfileType profileType,
        BigDecimal recurringCashAmount,
        BigDecimal recurringCashIntervalValue,
        RecurringCashIntervalUnit recurringCashIntervalUnit,
        AutoParticipantBehaviorModelVersion behaviorModelVersion,
        long behaviorSeed,
        LocalDateTime decisionSlotAt
) {
    public AutoParticipantStrategy(long accountId, int intensity, AutoParticipantProfileType profileType) {
        this("", accountId, intensity, profileType, null, null, null, AutoParticipantBehaviorModelVersion.V1, 0L, null);
    }

    public AutoParticipantStrategy(String userKey, long accountId, int intensity, AutoParticipantProfileType profileType) {
        this(userKey, accountId, intensity, profileType, null, null, null, AutoParticipantBehaviorModelVersion.V1, DeterministicSeed.fromUtf8(userKey), null);
    }

    public AutoParticipantStrategy(
            String userKey,
            long accountId,
            int intensity,
            AutoParticipantProfileType profileType,
            BigDecimal recurringCashAmount,
            BigDecimal recurringCashIntervalValue,
            RecurringCashIntervalUnit recurringCashIntervalUnit
    ) {
        this(
                userKey,
                accountId,
                intensity,
                profileType,
                recurringCashAmount,
                recurringCashIntervalValue,
                recurringCashIntervalUnit,
                AutoParticipantBehaviorModelVersion.V1,
                DeterministicSeed.fromUtf8(userKey),
                null
        );
    }

    public AutoParticipantStrategy {
        behaviorModelVersion = behaviorModelVersion == null ? AutoParticipantBehaviorModelVersion.V1 : behaviorModelVersion;
    }

}
