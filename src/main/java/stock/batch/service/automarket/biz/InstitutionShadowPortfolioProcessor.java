package stock.batch.service.automarket.biz;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import web.common.core.utils.DeterministicSeed;

@Service
@RequiredArgsConstructor
@Slf4j
class InstitutionShadowPortfolioProcessor {

    private final InstitutionShadowPortfolioRepository repository;
    private final InstitutionPortfolioPlanner planner;

    @Transactional(
            isolation = Isolation.REPEATABLE_READ,
            propagation = Propagation.REQUIRES_NEW
    )
    public ProcessResult process(
            long portfolioId,
            LocalDateTime simulationDateTime,
            Map<String, InstitutionMarketInput> marketInputs
    ) {
        Optional<InstitutionPortfolioPolicy> lockedPolicy = repository.lockDuePortfolio(
                portfolioId,
                simulationDateTime
        );
        if (lockedPolicy.isEmpty()) {
            return ProcessResult.SKIPPED;
        }
        InstitutionPortfolioPolicy policy = lockedPolicy.get();
        repository.activateEffectivePolicyVersion(
                policy,
                simulationDateTime.toLocalDate(),
                simulationDateTime
        );
        LocalDateTime decisionSlot = decisionSlot(
                simulationDateTime,
                policy.decisionIntervalMinutes()
        );
        LocalDateTime nextDecisionAt = decisionSlot.plusMinutes(policy.decisionIntervalMinutes());
        if (repository.decisionRunExists(policy.portfolioId(), decisionSlot)) {
            repository.updateNextDecisionAt(policy.portfolioId(), nextDecisionAt, simulationDateTime);
            return ProcessResult.SKIPPED;
        }
        long seed = DeterministicSeed.fromUtf8(
                policy.portfolioCode() + "|" + decisionSlot + "|" + policy.policyVersion()
        );
        long decisionRunId = repository.insertDecisionRun(
                policy,
                decisionSlot,
                simulationDateTime.toLocalDate(),
                seed,
                simulationDateTime
        );

        List<InstitutionSymbolMandate> mandates;
        Map<String, InstitutionDailyBudgetSnapshot> dailyBudgets;
        InstitutionDecisionPlan plan;
        try {
            mandates = repository.findEnabledMandates(policy.portfolioId());
            if (policy.pilot() && mandates.size() != 1) {
                throw new IllegalStateException(
                        "Institution PILOT requires exactly one enabled symbol mandate"
                );
            }
            List<String> symbols = mandates.stream()
                    .map(InstitutionSymbolMandate::symbol)
                    .toList();
            InstitutionAccountSnapshot account = repository.lockAndLoadAccountSnapshot(
                    policy,
                    symbols,
                    simulationDateTime.toLocalDate()
            );
            dailyBudgets = repository.lockDailyBudgets(
                    policy.portfolioId(),
                    simulationDateTime.toLocalDate()
            );
            plan = planner.plan(
                    policy,
                    mandates,
                    marketInputs,
                    account.positions(),
                    dailyBudgets,
                    repository.findPreviousActions(policy.portfolioId()),
                    account.availableCash(),
                    account.openBuyReservedCash(),
                    account.totalHoldingValue()
            );
        } catch (RuntimeException planningFailure) {
            repository.markDecisionRunFailed(
                    decisionRunId,
                    planningFailure.getMessage(),
                    simulationDateTime
            );
            repository.updateNextDecisionAt(
                    policy.portfolioId(),
                    nextDecisionAt,
                    simulationDateTime
            );
            log.warn(
                    "Institution shadow planning rejected: portfolio={}, slot={}, reason={}",
                    policy.portfolioCode(),
                    decisionSlot,
                    planningFailure.getMessage()
            );
            return ProcessResult.FAILED;
        }

        repository.insertDecisionItems(decisionRunId, plan.items(), simulationDateTime);
        repository.claimDailyBudgets(
                simulationDateTime.toLocalDate(),
                policy,
                plan.items(),
                dailyBudgets,
                simulationDateTime
        );
        repository.insertOrderIntents(
                decisionRunId,
                policy,
                plan.items(),
                simulationDateTime
        );
        repository.markDecisionRunCompleted(decisionRunId, simulationDateTime);
        repository.updateNextDecisionAt(
                policy.portfolioId(),
                nextDecisionAt,
                simulationDateTime
        );
        return ProcessResult.COMPLETED;
    }

    static LocalDateTime decisionSlot(LocalDateTime simulationDateTime, int intervalMinutes) {
        LocalDateTime minute = simulationDateTime.truncatedTo(ChronoUnit.MINUTES);
        int minuteOfDay = minute.getHour() * 60 + minute.getMinute();
        return minute.minusMinutes(Math.floorMod(minuteOfDay, intervalMinutes));
    }

    enum ProcessResult {
        COMPLETED,
        FAILED,
        SKIPPED
    }
}
