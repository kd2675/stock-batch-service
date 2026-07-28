package stock.batch.service.batch.automarket.model;

import web.common.core.utils.DeterministicSeed;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import stock.batch.service.automarket.v3.AutoParticipantActivityState;
import stock.batch.service.automarket.v3.AutoParticipantV3Policy;

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
        LocalDateTime decisionSlotAt,
        long policyVersion,
        long behaviorEventSequence,
        AutoParticipantActivityState activityState,
        double fatigueScore,
        LocalDateTime lastOrderAt,
        AutoParticipantV3Policy v3Policy
) {
    public AutoParticipantStrategy(
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
        this(
                userKey,
                accountId,
                intensity,
                profileType,
                recurringCashAmount,
                recurringCashIntervalValue,
                recurringCashIntervalUnit,
                behaviorModelVersion,
                behaviorSeed,
                decisionSlotAt,
                0L,
                0L,
                AutoParticipantActivityState.NORMAL,
                0.0,
                null,
                null
        );
    }

    public AutoParticipantStrategy(long accountId, int intensity, AutoParticipantProfileType profileType) {
        this("", accountId, intensity, profileType, null, null, null, AutoParticipantBehaviorModelVersion.V3, 0L, null);
    }

    public AutoParticipantStrategy(String userKey, long accountId, int intensity, AutoParticipantProfileType profileType) {
        this(userKey, accountId, intensity, profileType, null, null, null, AutoParticipantBehaviorModelVersion.V3, DeterministicSeed.fromUtf8(userKey), null);
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
                AutoParticipantBehaviorModelVersion.V3,
                DeterministicSeed.fromUtf8(userKey),
                null
        );
    }

    public AutoParticipantStrategy {
        if (behaviorModelVersion != AutoParticipantBehaviorModelVersion.V3) {
            throw new IllegalArgumentException("Only auto participant behavior model V3 is supported");
        }
        activityState = activityState == null ? AutoParticipantActivityState.NORMAL : activityState;
        if (policyVersion < 0
                || behaviorEventSequence < 0
                || !Double.isFinite(fatigueScore)
                || fatigueScore < 0) {
            throw new IllegalArgumentException("V3 decision context values must be non-negative and finite");
        }
    }

}
