package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.batch.service.automarket.profile.ProfilePolicy;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantRecentCashFlow;
import stock.batch.service.batch.automarket.model.AutoParticipantRecurringCashTarget;
import stock.batch.service.batch.automarket.model.RecurringCashIntervalUnit;
import stock.batch.service.batch.automarket.reader.AutoParticipantCashFlowReader;
import stock.batch.service.batch.automarket.reader.AutoMarketReader;
import stock.batch.service.batch.automarket.writer.AutoMarketWriter;
import stock.batch.service.simulation.SimulationClockService;
import web.common.core.simulation.SimulationClockSnapshot;

@Service
@RequiredArgsConstructor
public class AutoParticipantCashFlowService {

    private static final String AUTO_PROFILE_RECURRING_DEPOSIT_REASON = "AUTO_PROFILE_RECURRING_DEPOSIT";
    private static final String AUTO_PARTICIPANT_RECURRING_DEPOSIT_REASON = "AUTO_PARTICIPANT_RECURRING_DEPOSIT";
    private static final String AUTO_MARKET_CREATED_BY = "AUTO_MARKET";
    private static final String MANUAL_CREATED_BY = "AUTO_MARKET_MANUAL";

    private final AutoMarketReader autoMarketReader;
    private final AutoParticipantCashFlowReader autoParticipantCashFlowReader;
    private final AutoMarketWriter autoMarketWriter;
    private final AutoProfileBehaviorSupport autoProfileBehaviorSupport;
    private final SimulationClockService simulationClockService;

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
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        LocalDateTime now = clock.simulationDateTime();
        List<RecurringCashCandidate> candidates = recurringCashCandidates(profilePolicies, clock);
        Map<Long, List<AutoParticipantRecentCashFlow>> recentCashFlowsByAccountId = manualRun
                ? Map.of()
                : loadRecentAutomaticCashFlows(candidates);
        int funded = 0;

        for (RecurringCashCandidate candidate : candidates) {
            if (!manualRun && hasRecentAutomaticCashFlow(candidate, recentCashFlowsByAccountId)) {
                continue;
            }
            String createdBy = manualRun ? MANUAL_CREATED_BY : AUTO_MARKET_CREATED_BY;
            autoMarketWriter.depositCashFlow(candidate.accountId(), candidate.policy().amount(), candidate.policy().reason(), createdBy, now);
            funded++;
        }

        return funded;
    }

    private List<RecurringCashCandidate> recurringCashCandidates(
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            SimulationClockSnapshot clock
    ) {
        Set<Long> candidateAccountIds = new HashSet<>();
        List<RecurringCashCandidate> candidates = new ArrayList<>();

        for (AutoParticipantRecurringCashTarget target : autoParticipantCashFlowReader.findRecurringCashTargets()) {
            if (!candidateAccountIds.add(target.accountId())) {
                continue;
            }
            ProfilePolicy policy = profilePolicy(profilePolicies, target.profileType());
            RecurringCashPolicy recurringPolicy = recurringCashPolicy(target, policy, clock);
            if (recurringPolicy == null) {
                continue;
            }
            candidates.add(new RecurringCashCandidate(target.accountId(), recurringPolicy));
        }
        return candidates;
    }

    private Map<Long, List<AutoParticipantRecentCashFlow>> loadRecentAutomaticCashFlows(List<RecurringCashCandidate> candidates) {
        if (candidates.isEmpty()) {
            return Map.of();
        }
        LocalDateTime earliestWindowStart = candidates.stream()
                .map(candidate -> candidate.policy().windowStart())
                .min(LocalDateTime::compareTo)
                .orElseThrow();
        Set<String> reasons = candidates.stream()
                .map(candidate -> candidate.policy().reason())
                .collect(Collectors.toSet());
        List<Long> accountIds = candidates.stream()
                .map(RecurringCashCandidate::accountId)
                .toList();
        return autoParticipantCashFlowReader.findRecentCashFlows(accountIds, reasons, AUTO_MARKET_CREATED_BY, earliestWindowStart)
                .stream()
                .collect(Collectors.groupingBy(AutoParticipantRecentCashFlow::accountId));
    }

    private boolean hasRecentAutomaticCashFlow(
            RecurringCashCandidate candidate,
            Map<Long, List<AutoParticipantRecentCashFlow>> recentCashFlowsByAccountId
    ) {
        List<AutoParticipantRecentCashFlow> recentCashFlows = recentCashFlowsByAccountId.getOrDefault(candidate.accountId(), List.of());
        return recentCashFlows.stream().anyMatch(cashFlow ->
                candidate.policy().reason().equals(cashFlow.reason())
                        && !cashFlow.createdAt().isBefore(candidate.policy().windowStart())
        );
    }

    private RecurringCashPolicy recurringCashPolicy(
            AutoParticipantRecurringCashTarget target,
            ProfilePolicy profilePolicy,
            SimulationClockSnapshot clock
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
                    clock.simulationDateTime().minus(recurringCashDuration(
                            target.recurringCashIntervalValue(),
                            target.recurringCashIntervalUnit()
                    ))
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
        return new RecurringCashPolicy(
                amount,
                AUTO_PROFILE_RECURRING_DEPOSIT_REASON,
                clock.simulationDateTime().minus(recurringCashDuration(
                        intervalValue,
                        intervalUnit
                ))
        );
    }

    private Duration recurringCashDuration(
            BigDecimal intervalValue,
            RecurringCashIntervalUnit intervalUnit
    ) {
        BigDecimal seconds = intervalValue.multiply(projectSecondsPerUnit(intervalUnit));
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

    private BigDecimal projectSecondsPerUnit(RecurringCashIntervalUnit intervalUnit) {
        return switch (intervalUnit) {
            case SECOND -> BigDecimal.ONE;
            case MINUTE -> BigDecimal.valueOf(60);
            case HOUR -> BigDecimal.valueOf(3_600);
            case DAY -> BigDecimal.valueOf(86_400);
            case MONTH -> BigDecimal.valueOf(2_592_000);
            case YEAR -> BigDecimal.valueOf(31_536_000);
        };
    }

    private ProfilePolicy profilePolicy(
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            AutoParticipantProfileType profileType
    ) {
        return autoProfileBehaviorSupport.policy(profilePolicies, profileType);
    }

    private Map<AutoParticipantProfileType, ProfilePolicy> loadProfilePolicies() {
        return autoProfileBehaviorSupport.policiesWithOverrides(autoMarketReader.findParticipantProfileConfigs());
    }

    private record RecurringCashPolicy(
            BigDecimal amount,
            String reason,
            LocalDateTime windowStart
    ) {
    }

    private record RecurringCashCandidate(
            long accountId,
            RecurringCashPolicy policy
    ) {
    }
}
