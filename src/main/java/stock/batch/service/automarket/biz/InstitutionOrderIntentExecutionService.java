package stock.batch.service.automarket.biz;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.execution.lock.OrderBookSymbolLock;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;

@Service
@Slf4j
class InstitutionOrderIntentExecutionService {

    private static final int MAX_DEADLOCK_RETRY_ATTEMPTS = 10;
    private static final long MAX_DEADLOCK_RETRY_BACKOFF_MILLIS = 1_000L;

    private final InstitutionOrderIntentRepository repository;
    private final InstitutionOrderIntentProcessor processor;
    private final AutoMarketOrderExecutor orderExecutor;
    private final OrderBookSymbolLock orderBookSymbolLock;
    private final MarketSessionFenceService marketSessionFenceService;
    private final TransactionTemplate transactionTemplate;
    private final int intentLimitPerRun;
    private final int deadlockRetryMaxAttempts;
    private final long deadlockRetryBackoffMs;

    InstitutionOrderIntentExecutionService(
            InstitutionOrderIntentRepository repository,
            InstitutionOrderIntentProcessor processor,
            AutoMarketOrderExecutor orderExecutor,
            OrderBookSymbolLock orderBookSymbolLock,
            MarketSessionFenceService marketSessionFenceService,
            TransactionTemplate transactionTemplate,
            @Value("${stock.batch.institution-market.intent-limit-per-run:20}") int intentLimitPerRun,
            @Value("${stock.batch.institution-market.deadlock-retry-max-attempts:5}")
            int deadlockRetryMaxAttempts,
            @Value("${stock.batch.institution-market.deadlock-retry-backoff-ms:50}")
            long deadlockRetryBackoffMs
    ) {
        this.repository = repository;
        this.processor = processor;
        this.orderExecutor = orderExecutor;
        this.orderBookSymbolLock = orderBookSymbolLock;
        this.marketSessionFenceService = marketSessionFenceService;
        this.transactionTemplate = transactionTemplate;
        this.intentLimitPerRun = Math.clamp(intentLimitPerRun, 1, 100);
        if (deadlockRetryMaxAttempts < 1
                || deadlockRetryMaxAttempts > MAX_DEADLOCK_RETRY_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "Institution intent deadlock retry attempts must be between 1 and "
                            + MAX_DEADLOCK_RETRY_ATTEMPTS
            );
        }
        if (deadlockRetryBackoffMs < 0L
                || deadlockRetryBackoffMs > MAX_DEADLOCK_RETRY_BACKOFF_MILLIS) {
            throw new IllegalArgumentException(
                    "Institution intent deadlock retry backoff must be between 0 and "
                            + MAX_DEADLOCK_RETRY_BACKOFF_MILLIS + " milliseconds"
            );
        }
        this.deadlockRetryMaxAttempts = deadlockRetryMaxAttempts;
        this.deadlockRetryBackoffMs = deadlockRetryBackoffMs;
    }

    int runPendingIntents(
            Map<String, AutoMarketConfig> configsBySymbol,
            LocalDate simulationTradeDate,
            LocalDateTime observedAt
    ) {
        Integer reconciledClosedIntents = null;
        try {
            reconciledClosedIntents = transactionTemplate.execute(status ->
                    repository.reconcileClosedSubmittedIntents(
                            simulationTradeDate,
                            observedAt
                    )
            );
        } catch (CannotAcquireLockException ex) {
            log.warn(
                    "Institution closed-order reconciliation deferred after lock contention: "
                            + "tradeDate={}, reason={}",
                    simulationTradeDate,
                    ex.getMessage()
            );
        }
        if (reconciledClosedIntents != null && reconciledClosedIntents > 0) {
            log.info(
                    "Reconciled closed institution orders and released unused participation "
                            + "capacity: count={}, tradeDate={}",
                    reconciledClosedIntents,
                    simulationTradeDate
            );
        }
        int rejectedStaleIntents = repository.rejectStalePendingIntents(
                simulationTradeDate,
                observedAt
        );
        if (rejectedStaleIntents > 0) {
            log.warn(
                    "Rejected stale institution LIVE intents from prior trade dates: count={}, "
                            + "activeTradeDate={}",
                    rejectedStaleIntents,
                    simulationTradeDate
            );
        }
        List<InstitutionOrderIntentRepository.IntentReference> references =
                repository.findPendingIntents(simulationTradeDate, intentLimitPerRun);
        int submitted = 0;
        for (InstitutionOrderIntentRepository.IntentReference reference : references) {
            AutoMarketConfig config = configsBySymbol.get(reference.symbol());
            if (config == null) {
                recordFailure(reference, "ACTIVE_MARKET_CONFIG_MISSING", observedAt);
                continue;
            }
            InstitutionOrderIntentProcessor.ProcessResult result = runOne(
                    reference,
                    config,
                    simulationTradeDate,
                    observedAt
            );
            if (result.submitted()) {
                submitted++;
            }
        }
        return submitted;
    }

    private InstitutionOrderIntentProcessor.ProcessResult runOne(
            InstitutionOrderIntentRepository.IntentReference reference,
            AutoMarketConfig config,
            LocalDate simulationTradeDate,
            LocalDateTime observedAt
    ) {
        return orderBookSymbolLock.tryLock(reference.symbol())
                .map(lock -> {
                    try (lock) {
                        try {
                            return runTransactionWithDeadlockRetry(
                                    reference,
                                    config,
                                    simulationTradeDate
                            );
                        } catch (CannotAcquireLockException ex) {
                            log.warn(
                                    "Institution LIVE intent deferred after transient lock retries: "
                                            + "decisionRunId={}, symbol={}, attempts={}, reason={}",
                                    reference.decisionRunId(),
                                    reference.symbol(),
                                    deadlockRetryMaxAttempts,
                                    ex.getMessage()
                            );
                            return InstitutionOrderIntentProcessor.ProcessResult.SKIPPED;
                        } catch (RuntimeException ex) {
                            recordFailure(reference, ex.getMessage(), observedAt);
                            log.warn(
                                    "Institution LIVE order intent failed: decisionRunId={}, "
                                            + "symbol={}, reason={}",
                                    reference.decisionRunId(),
                                    reference.symbol(),
                                    ex.getMessage(),
                                    ex
                            );
                            return InstitutionOrderIntentProcessor.ProcessResult.SKIPPED;
                        }
                    }
                })
                .orElse(InstitutionOrderIntentProcessor.ProcessResult.SKIPPED);
    }

    private InstitutionOrderIntentProcessor.ProcessResult runTransactionWithDeadlockRetry(
            InstitutionOrderIntentRepository.IntentReference reference,
            AutoMarketConfig config,
            LocalDate simulationTradeDate
    ) {
        CannotAcquireLockException lastException = null;
        for (int attempt = 1; attempt <= deadlockRetryMaxAttempts; attempt++) {
            try {
                InstitutionOrderIntentProcessor.ProcessResult result =
                        transactionTemplate.execute(status ->
                                marketSessionFenceService
                                        .lockOpenOrderBookFences(List.of(reference.symbol()))
                                        .map(sessionApproval -> processor.process(
                                                reference,
                                                config,
                                                simulationTradeDate,
                                                sessionApproval
                                        ))
                                        .orElse(InstitutionOrderIntentProcessor.ProcessResult.SKIPPED)
                        );
                return result == null
                        ? InstitutionOrderIntentProcessor.ProcessResult.SKIPPED
                        : result;
            } catch (CannotAcquireLockException ex) {
                lastException = ex;
                if (attempt >= deadlockRetryMaxAttempts) {
                    break;
                }
                sleepBeforeDeadlockRetry(reference, attempt, ex);
            }
        }
        throw lastException;
    }

    private void sleepBeforeDeadlockRetry(
            InstitutionOrderIntentRepository.IntentReference reference,
            int attempt,
            CannotAcquireLockException ex
    ) {
        long backoffMillis = deadlockRetryBackoffMs * attempt;
        log.warn(
                "Institution LIVE intent deadlock retry: decisionRunId={}, symbol={}, "
                        + "attempt={}, backoffMs={}, reason={}",
                reference.decisionRunId(),
                reference.symbol(),
                attempt,
                backoffMillis,
                ex.getMessage()
        );
        if (backoffMillis <= 0L) {
            return;
        }
        try {
            Thread.sleep(backoffMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted during institution intent deadlock retry",
                    interrupted
            );
        }
    }

    private void recordFailure(
            InstitutionOrderIntentRepository.IntentReference reference,
            String reason,
            LocalDateTime failedAt
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            InstitutionOrderIntentRepository.FailureResult failure = repository.recordFailure(
                    reference,
                    reason,
                    failedAt
            );
            if (failure.terminal()) {
                repository.rejectPendingPortfolioIntents(
                        failure.portfolioId(),
                        reference,
                        failedAt
                );
                repository.suspendLivePortfolio(failure.portfolioId(), failedAt);
                int cancelledOrderCount = orderExecutor.expireOrders(
                        repository.findOpenPortfolioAccountOrders(failure.portfolioId()),
                        failedAt
                );
                log.error(
                        "Institution LIVE portfolio suspended after terminal intent failure: "
                                + "decisionRunId={}, symbol={}, attempts={}, cancelledOrders={}",
                        reference.decisionRunId(),
                        reference.symbol(),
                        failure.attemptCount(),
                        cancelledOrderCount
                );
            }
        });
    }
}
