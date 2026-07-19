package stock.batch.service.automarket.biz;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import stock.batch.service.automarket.profile.ProfilePolicy;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantCashDeposit;
import stock.batch.service.batch.automarket.model.AutoParticipantRecentCashFlow;
import stock.batch.service.batch.automarket.model.AutoParticipantRecurringCashTarget;
import stock.batch.service.batch.automarket.model.RecurringCashIntervalUnit;
import stock.batch.service.batch.automarket.reader.AutoParticipantCashFlowReader;
import stock.batch.service.batch.automarket.reader.AutoMarketReader;
import stock.batch.service.batch.automarket.writer.AutoMarketWriter;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;
import stock.batch.service.simulation.SimulationClockService;
import stock.batch.service.simulation.SimulationMarketSessionService;
import web.common.core.simulation.SimulationClockSnapshot;

@Service
@RequiredArgsConstructor
public class AutoParticipantCashFlowService {

    private static final String AUTO_PROFILE_RECURRING_DEPOSIT_REASON = "AUTO_PROFILE_RECURRING_DEPOSIT";
    private static final String AUTO_PARTICIPANT_RECURRING_DEPOSIT_REASON = "AUTO_PARTICIPANT_RECURRING_DEPOSIT";
    private static final String AUTO_MARKET_CREATED_BY = "AUTO_MARKET";
    private static final String MANUAL_CREATED_BY = "AUTO_MARKET_MANUAL";
    private static final int MAX_ACCOUNT_CHUNK_SIZE = 1_000;
    private static final Set<String> RECURRING_CASH_CREATED_BY_VALUES = Set.of(
            AUTO_MARKET_CREATED_BY,
            MANUAL_CREATED_BY
    );

    private final AutoMarketReader autoMarketReader;
    private final AutoParticipantCashFlowReader autoParticipantCashFlowReader;
    private final AutoMarketWriter autoMarketWriter;
    private final AutoProfileBehaviorSupport autoProfileBehaviorSupport;
    private final SimulationClockService simulationClockService;
    private final SimulationMarketSessionService simulationMarketSessionService;
    private final AutoParticipantCashFlowTransactionExecutor transactionExecutor;
    private final MarketSessionFenceService marketSessionFenceService;
    private final AutoParticipantCashFlowRunLedger runLedger;

    @Value("${stock.batch.auto-participant-cash-flow.account-chunk-size:200}")
    private int accountChunkSize = 200;

    @PostConstruct
    void validateVolumeConfiguration() {
        validateAccountChunkSize(accountChunkSize);
    }

    int fundRecurringCash() {
        CashFlowRunResult result = fundRecurringCash(false, null);
        return result.newlyProcessedCount();
    }

    public int fundRecurringCash(String runKey) {
        CashFlowRunResult result = fundRecurringCash(false, runKey);
        return Math.toIntExact(result.totalProcessedCount());
    }

    int fundRecurringCashManually() {
        CashFlowRunResult result = fundRecurringCash(true, null);
        return result.newlyProcessedCount();
    }

    public int fundRecurringCashManually(String runKey) {
        CashFlowRunResult result = fundRecurringCash(true, runKey);
        return Math.toIntExact(result.totalProcessedCount());
    }

    private CashFlowRunResult fundRecurringCash(boolean manualRun, String requestedRunKey) {
        if (simulationMarketSessionService.isRegularSession()) {
            return CashFlowRunResult.empty();
        }
        if (marketSessionFenceService.hasOpenMarket()) {
            throw new IllegalStateException("Cannot fund recurring cash while any market is open");
        }
        marketSessionFenceService.assertMarketLedgerMutationAllowed("auto-participant recurring cash");
        Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies = loadProfilePolicies();
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        LocalDateTime now = clock.simulationDateTime();
        String operation = manualRun ? "MANUAL" : "SCHEDULED";
        String runKey = requestedRunKey == null
                ? compatibilityRunKey(operation)
                : requestedRunKey;
        AutoParticipantCashFlowRunLedger.RunProgress progress = runLedger.initializeAndRead(runKey, operation);
        if (progress.completed()) {
            return new CashFlowRunResult(0, progress.processedCount());
        }
        int funded = 0;
        int chunkSize = validateAccountChunkSize(accountChunkSize);
        long afterAccountId = progress.lastAccountId();
        while (true) {
            List<AutoParticipantRecurringCashTarget> targets =
                    autoParticipantCashFlowReader.findRecurringCashTargetChunk(afterAccountId, chunkSize);
            if (targets.isEmpty()) {
                long completedAfterAccountId = afterAccountId;
                transactionExecutor.execute(() -> {
                    AutoParticipantCashFlowRunLedger.RunProgress locked = runLedger.lock(runKey);
                    requireRunCursor(runKey, completedAfterAccountId, locked);
                    runLedger.complete(runKey, completedAfterAccountId);
                    return 0;
                });
                break;
            }
            List<RecurringCashCandidate> chunk = recurringCashCandidates(profilePolicies, clock, targets);
            Map<Long, List<AutoParticipantRecentCashFlow>> recentCashFlowsByAccountId =
                    loadRecentRecurringCashFlows(chunk);
            long expectedAfterAccountId = afterAccountId;
            long nextAfterAccountId = targets.getLast().accountId();
            funded += transactionExecutor.execute(() -> {
                AutoParticipantCashFlowRunLedger.RunProgress locked = runLedger.lock(runKey);
                requireRunCursor(runKey, expectedAfterAccountId, locked);
                int chunkFunded = fundRecurringCashChunk(
                        chunk,
                        recentCashFlowsByAccountId,
                        manualRun,
                        now
                );
                runLedger.advance(runKey, expectedAfterAccountId, nextAfterAccountId, chunkFunded);
                return chunkFunded;
            });
            afterAccountId = nextAfterAccountId;
            if (targets.size() < chunkSize) {
                continue;
            }
        }
        AutoParticipantCashFlowRunLedger.RunProgress completed = runLedger.initializeAndRead(runKey, operation);
        return new CashFlowRunResult(funded, completed.processedCount());
    }

    private void requireRunCursor(
            String runKey,
            long expectedLastAccountId,
            AutoParticipantCashFlowRunLedger.RunProgress progress
    ) {
        if (progress.completed() || progress.lastAccountId() != expectedLastAccountId) {
            throw new IllegalStateException(
                    "Recurring cash run cursor changed during execution: runKey=%s, expected=%d, actual=%d, completed=%s"
                            .formatted(
                                    runKey,
                                    expectedLastAccountId,
                                    progress.lastAccountId(),
                                    progress.completed()
                            )
            );
        }
    }

    private String compatibilityRunKey(String operation) {
        return "COMPATIBILITY:" + operation + ":" + UUID.randomUUID();
    }

    private int fundRecurringCashChunk(
            List<RecurringCashCandidate> candidates,
            Map<Long, List<AutoParticipantRecentCashFlow>> recentCashFlowsByAccountId,
            boolean manualRun,
            LocalDateTime now
    ) {
        String createdBy = manualRun ? MANUAL_CREATED_BY : AUTO_MARKET_CREATED_BY;
        List<AutoParticipantCashDeposit> deposits = candidates.stream()
                .filter(candidate -> !hasRecentRecurringCashFlow(candidate, recentCashFlowsByAccountId))
                .map(candidate -> new AutoParticipantCashDeposit(
                        candidate.accountId(),
                        candidate.policy().amount(),
                        candidate.policy().reason()
                ))
                .toList();
        return autoMarketWriter.depositCashFlowChunk(deposits, createdBy, now);
    }

    private List<RecurringCashCandidate> recurringCashCandidates(
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            SimulationClockSnapshot clock,
            List<AutoParticipantRecurringCashTarget> targets
    ) {
        Set<Long> candidateAccountIds = new HashSet<>();
        List<RecurringCashCandidate> candidates = new ArrayList<>();

        for (AutoParticipantRecurringCashTarget target : targets) {
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

    private Map<Long, List<AutoParticipantRecentCashFlow>> loadRecentRecurringCashFlows(List<RecurringCashCandidate> candidates) {
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
        return autoParticipantCashFlowReader.findRecentCashFlows(accountIds, reasons, RECURRING_CASH_CREATED_BY_VALUES, earliestWindowStart)
                .stream()
                .collect(Collectors.groupingBy(AutoParticipantRecentCashFlow::accountId));
    }

    private boolean hasRecentRecurringCashFlow(
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

    static int validateAccountChunkSize(int chunkSize) {
        if (chunkSize < 1 || chunkSize > MAX_ACCOUNT_CHUNK_SIZE) {
            throw new IllegalStateException(
                    "stock.batch.auto-participant-cash-flow.account-chunk-size must be between 1 and %d: %d"
                            .formatted(MAX_ACCOUNT_CHUNK_SIZE, chunkSize)
            );
        }
        return chunkSize;
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

    private record CashFlowRunResult(
            int newlyProcessedCount,
            long totalProcessedCount
    ) {

        private static CashFlowRunResult empty() {
            return new CashFlowRunResult(0, 0);
        }
    }
}
