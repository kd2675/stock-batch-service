package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.batch.service.automarket.profile.AutoProfileBehaviorRegistry;
import stock.batch.service.automarket.profile.ProfilePolicy;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantRecurringCashTarget;
import stock.batch.service.batch.automarket.model.RecurringCashIntervalUnit;
import stock.batch.service.batch.automarket.reader.AutoMarketReader;
import stock.batch.service.batch.automarket.writer.AutoMarketWriter;

@Service
@RequiredArgsConstructor
public class AutoParticipantCashFlowService {

    private static final String AUTO_PROFILE_RECURRING_DEPOSIT_REASON = "AUTO_PROFILE_RECURRING_DEPOSIT";
    private static final String AUTO_PARTICIPANT_RECURRING_DEPOSIT_REASON = "AUTO_PARTICIPANT_RECURRING_DEPOSIT";
    private static final String AUTO_MARKET_CREATED_BY = "AUTO_MARKET";
    private static final String MANUAL_CREATED_BY = "AUTO_MARKET_MANUAL";
    private static final AutoProfileBehaviorRegistry PROFILE_BEHAVIORS = AutoProfileBehaviorRegistry.createDefault();

    private final AutoMarketReader autoMarketReader;
    private final AutoMarketWriter autoMarketWriter;

    @Transactional
    public int fundRecurringCash() {
        return fundRecurringCash(false);
    }

    @Transactional
    public int fundRecurringCashManually() {
        return fundRecurringCash(true);
    }

    private int fundRecurringCash(boolean manualRun) {
        Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies = loadProfilePolicies();
        LocalDateTime now = LocalDateTime.now();
        Set<Long> fundedAccountIds = new HashSet<>();
        int funded = 0;

        for (AutoParticipantRecurringCashTarget target : autoMarketReader.findRecurringCashTargets()) {
            if (!fundedAccountIds.add(target.accountId())) {
                continue;
            }
            ProfilePolicy policy = profilePolicy(profilePolicies, target.profileType());
            RecurringCashPolicy recurringPolicy = recurringCashPolicy(target, policy, now);
            if (recurringPolicy == null) {
                continue;
            }
            if (!manualRun && autoMarketReader.hasCashFlowSince(target.accountId(), recurringPolicy.reason(), AUTO_MARKET_CREATED_BY, recurringPolicy.windowStart())) {
                continue;
            }
            String createdBy = manualRun ? MANUAL_CREATED_BY : AUTO_MARKET_CREATED_BY;
            autoMarketWriter.depositCashFlow(target.accountId(), recurringPolicy.amount(), recurringPolicy.reason(), createdBy, now);
            funded++;
        }

        return funded;
    }

    private RecurringCashPolicy recurringCashPolicy(
            AutoParticipantRecurringCashTarget target,
            ProfilePolicy profilePolicy,
            LocalDateTime now
    ) {
        if (AutoParticipantProfileType.DIVIDEND_REINVESTOR.equals(target.profileType())) {
            return null;
        }
        if (target.recurringCashAmount() != null) {
            if (target.recurringCashAmount().compareTo(BigDecimal.ZERO) <= 0
                    || target.recurringCashIntervalValue() == null
                    || target.recurringCashIntervalValue().compareTo(BigDecimal.ZERO) <= 0
                    || target.recurringCashIntervalUnit() == null) {
                return null;
            }
            return new RecurringCashPolicy(
                    target.recurringCashAmount(),
                    AUTO_PARTICIPANT_RECURRING_DEPOSIT_REASON,
                    now.minus(recurringCashDuration(target.recurringCashIntervalValue(), target.recurringCashIntervalUnit()))
            );
        }

        BigDecimal amount = profilePolicy.recurringDepositAmount();
        BigDecimal intervalValue = profilePolicy.recurringDepositIntervalValue();
        RecurringCashIntervalUnit intervalUnit = profilePolicy.recurringDepositIntervalUnit();
        if (amount.compareTo(BigDecimal.ZERO) <= 0
                || intervalValue == null
                || intervalValue.compareTo(BigDecimal.ZERO) <= 0
                || intervalUnit == null) {
            return null;
        }
        return new RecurringCashPolicy(amount, AUTO_PROFILE_RECURRING_DEPOSIT_REASON, now.minus(recurringCashDuration(intervalValue, intervalUnit)));
    }

    private Duration recurringCashDuration(BigDecimal intervalValue, RecurringCashIntervalUnit intervalUnit) {
        BigDecimal seconds = intervalValue.multiply(intervalUnit.seconds());
        BigDecimal wholeSeconds = seconds.setScale(0, RoundingMode.DOWN);
        BigDecimal fractionalSeconds = seconds.subtract(wholeSeconds);
        long secondPart = wholeSeconds.longValueExact();
        long nanoPart = fractionalSeconds
                .multiply(BigDecimal.valueOf(1_000_000_000L))
                .setScale(0, RoundingMode.CEILING)
                .longValueExact();
        Duration duration = Duration.ofSeconds(secondPart, nanoPart);
        return duration.isZero() ? Duration.ofNanos(1) : duration;
    }

    private ProfilePolicy profilePolicy(
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            AutoParticipantProfileType profileType
    ) {
        return profilePolicies.getOrDefault(profileType, profilePolicies.get(AutoParticipantProfileType.defaultType()));
    }

    private Map<AutoParticipantProfileType, ProfilePolicy> loadProfilePolicies() {
        return PROFILE_BEHAVIORS.policiesWithOverrides(autoMarketReader.findParticipantProfileConfigs());
    }

    private record RecurringCashPolicy(
            BigDecimal amount,
            String reason,
            LocalDateTime windowStart
    ) {
    }
}
