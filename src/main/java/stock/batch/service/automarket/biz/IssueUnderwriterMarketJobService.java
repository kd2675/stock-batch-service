package stock.batch.service.automarket.biz;

import jakarta.annotation.PostConstruct;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.reader.AutoMarketReader;
import stock.batch.service.execution.lock.OrderBookSymbolLock;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;
import stock.batch.service.simulation.SimulationClockService;
import stock.batch.service.simulation.SimulationMarketSessionService;
import web.common.core.simulation.SimulationClockSnapshot;
import web.common.core.simulation.SimulationMarketSession;

@Service
@RequiredArgsConstructor
@Slf4j
public class IssueUnderwriterMarketJobService {

    private static final int MAX_DEADLOCK_RETRY_ATTEMPTS = 10;
    private static final long MAX_DEADLOCK_RETRY_BACKOFF_MILLIS = 1_000L;
    private static final int MAX_CONTRACT_LIMIT_PER_RUN = 500;

    private final AutoMarketReader autoMarketReader;
    private final IssueUnderwriterSupplyRepository repository;
    private final IssueUnderwriterSupplyProcessor processor;
    private final SimulationClockService simulationClockService;
    private final SimulationMarketSessionService simulationMarketSessionService;
    private final TransactionTemplate transactionTemplate;
    private final OrderBookSymbolLock orderBookSymbolLock;
    private final MarketSessionFenceService marketSessionFenceService;
    private final MeterRegistry meterRegistry;

    @Value("${stock.batch.issue-underwriter-market.deadlock-retry-max-attempts:5}")
    private int deadlockRetryMaxAttempts = 5;

    @Value("${stock.batch.issue-underwriter-market.deadlock-retry-backoff-ms:50}")
    private long deadlockRetryBackoffMs = 50L;

    @Value("${stock.batch.issue-underwriter-market.contract-limit-per-run:100}")
    private int contractLimitPerRun = 100;

    @PostConstruct
    void validateConfiguration() {
        if (deadlockRetryMaxAttempts < 1
                || deadlockRetryMaxAttempts > MAX_DEADLOCK_RETRY_ATTEMPTS) {
            throw new IllegalStateException(
                    "stock.batch.issue-underwriter-market.deadlock-retry-max-attempts "
                            + "must be between 1 and "
                            + MAX_DEADLOCK_RETRY_ATTEMPTS
            );
        }
        if (deadlockRetryBackoffMs < 0L
                || deadlockRetryBackoffMs > MAX_DEADLOCK_RETRY_BACKOFF_MILLIS) {
            throw new IllegalStateException(
                    "stock.batch.issue-underwriter-market.deadlock-retry-backoff-ms "
                            + "must be between 0 and "
                            + MAX_DEADLOCK_RETRY_BACKOFF_MILLIS
            );
        }
        if (contractLimitPerRun < 1
                || contractLimitPerRun > MAX_CONTRACT_LIMIT_PER_RUN) {
            throw new IllegalStateException(
                    "stock.batch.issue-underwriter-market.contract-limit-per-run "
                            + "must be between 1 and " + MAX_CONTRACT_LIMIT_PER_RUN
            );
        }
    }

    public int runIssueUnderwriterMarket() {
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        if (!clock.running()
                || simulationMarketSessionService.sessionAt(clock.simulationDateTime())
                != SimulationMarketSession.REGULAR) {
            return 0;
        }
        LocalDate simulationTradeDate = clock.simulationDate();
        Map<String, AutoMarketConfig> enabledConfigs = enabledConfigsBySymbol();
        List<IssueUnderwriterSupplyRepository.ContractReference> candidates =
                repository.findCandidates(contractLimitPerRun);
        int processed = 0;
        int generated = 0;
        int cancelled = 0;
        for (IssueUnderwriterSupplyRepository.ContractReference reference : candidates) {
            AutoMarketConfig config = enabledConfigs.get(reference.symbol());
            boolean marketTradingEnabled = config != null;
            AutoMarketConfig safetyConfig = config == null
                    ? repository.findSafetyMarketConfig(reference.symbol()).orElse(null)
                    : config;
            if (safetyConfig == null) {
                meterRegistry.counter(
                        "stock.issue.underwriter.market.config.missing"
                ).increment();
                log.error(
                        "Issue-underwriter contract skipped because market safety data is missing: "
                                + "contractId={}, symbol={}",
                        reference.id(),
                        reference.symbol()
                );
                continue;
            }
            IssueUnderwriterSupplyProcessor.ProcessResult result = runContract(
                    reference,
                    safetyConfig,
                    marketTradingEnabled,
                    simulationTradeDate
            );
            if (result.processed()) {
                processed++;
                generated += result.generatedOrderCount();
                cancelled += result.cancelledOrderCount();
            }
        }
        log.info(
                "Issue-underwriter market completed: candidates={}, processed={}, "
                        + "generatedOrders={}, cancelledOrders={}, enabledSymbols={}",
                candidates.size(),
                processed,
                generated,
                cancelled,
                enabledConfigs.size()
        );
        return processed;
    }

    private Map<String, AutoMarketConfig> enabledConfigsBySymbol() {
        Map<String, AutoMarketConfig> configs = new LinkedHashMap<>();
        for (AutoMarketConfig config : autoMarketReader.findEnabledConfigs()) {
            AutoMarketConfig previous = configs.putIfAbsent(config.symbol(), config);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate enabled market config for issue-underwriter symbol: "
                                + config.symbol()
                );
            }
        }
        return Map.copyOf(configs);
    }

    private IssueUnderwriterSupplyProcessor.ProcessResult runContract(
            IssueUnderwriterSupplyRepository.ContractReference reference,
            AutoMarketConfig config,
            boolean marketTradingEnabled,
            LocalDate simulationTradeDate
    ) {
        return orderBookSymbolLock.tryLock(reference.symbol())
                .map(lock -> {
                    try (lock) {
                        return runInTransactionWithDeadlockRetry(
                                reference.symbol(),
                                () -> marketSessionFenceService
                                        .lockOpenOrderBookFences(
                                                List.of(reference.symbol())
                                        )
                                        .map(approval -> processor.process(
                                                reference.id(),
                                                config,
                                                marketTradingEnabled,
                                                simulationTradeDate,
                                                approval
                                        ))
                                        .orElse(
                                                IssueUnderwriterSupplyProcessor
                                                        .ProcessResult.SKIPPED
                                        )
                        );
                    }
                })
                .orElseGet(() -> {
                    meterRegistry.counter(
                            "stock.issue.underwriter.market.symbol.lock.skips"
                    ).increment();
                    return IssueUnderwriterSupplyProcessor.ProcessResult.SKIPPED;
                });
    }

    private IssueUnderwriterSupplyProcessor.ProcessResult
    runInTransactionWithDeadlockRetry(
            String symbol,
            Supplier<IssueUnderwriterSupplyProcessor.ProcessResult> action
    ) {
        CannotAcquireLockException lastException = null;
        for (int attempt = 1; attempt <= deadlockRetryMaxAttempts; attempt++) {
            try {
                IssueUnderwriterSupplyProcessor.ProcessResult result =
                        transactionTemplate.execute(status -> action.get());
                return result == null
                        ? IssueUnderwriterSupplyProcessor.ProcessResult.SKIPPED
                        : result;
            } catch (CannotAcquireLockException ex) {
                lastException = ex;
                if (attempt >= deadlockRetryMaxAttempts) {
                    break;
                }
                long backoffMillis = deadlockRetryBackoffMs * attempt;
                log.warn(
                        "Issue-underwriter deadlock retry: symbol={}, attempt={}, "
                                + "backoffMs={}, reason={}",
                        symbol,
                        attempt,
                        backoffMillis,
                        ex.getMessage()
                );
                if (backoffMillis > 0L) {
                    try {
                        Thread.sleep(backoffMillis);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "Interrupted during issue-underwriter deadlock retry",
                                interrupted
                        );
                    }
                }
            }
        }
        throw lastException;
    }
}
