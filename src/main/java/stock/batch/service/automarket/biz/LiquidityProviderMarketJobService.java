package stock.batch.service.automarket.biz;

import jakarta.annotation.PostConstruct;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
public class LiquidityProviderMarketJobService {

    private static final int MAX_DEADLOCK_RETRY_ATTEMPTS = 10;
    private static final long MAX_DEADLOCK_RETRY_BACKOFF_MILLIS = 1_000L;
    private static final int MAX_MANDATE_LIMIT_PER_RUN = 500;

    private final AutoMarketReader autoMarketReader;
    private final AutoMarketDailyRegimeService dailyRegimeService;
    private final LiquidityProviderRepository repository;
    private final LiquidityProviderQuoteProcessor quoteProcessor;
    private final SimulationClockService simulationClockService;
    private final SimulationMarketSessionService simulationMarketSessionService;
    private final TransactionTemplate transactionTemplate;
    private final OrderBookSymbolLock orderBookSymbolLock;
    private final MarketSessionFenceService marketSessionFenceService;
    private final MeterRegistry meterRegistry;

    @Value("${stock.batch.liquidity-provider-market.deadlock-retry-max-attempts:5}")
    private int deadlockRetryMaxAttempts = 5;

    @Value("${stock.batch.liquidity-provider-market.deadlock-retry-backoff-ms:50}")
    private long deadlockRetryBackoffMs = 50L;

    @Value("${stock.batch.liquidity-provider-market.mandate-limit-per-run:100}")
    private int mandateLimitPerRun = 100;

    @PostConstruct
    void validateConfiguration() {
        if (deadlockRetryMaxAttempts < 1 || deadlockRetryMaxAttempts > MAX_DEADLOCK_RETRY_ATTEMPTS) {
            throw new IllegalStateException(
                    "stock.batch.liquidity-provider-market.deadlock-retry-max-attempts "
                            + "must be between 1 and %d: %d"
                            .formatted(MAX_DEADLOCK_RETRY_ATTEMPTS, deadlockRetryMaxAttempts)
            );
        }
        if (deadlockRetryBackoffMs < 0L
                || deadlockRetryBackoffMs > MAX_DEADLOCK_RETRY_BACKOFF_MILLIS) {
            throw new IllegalStateException(
                    "stock.batch.liquidity-provider-market.deadlock-retry-backoff-ms "
                            + "must be between 0 and %d: %d"
                            .formatted(MAX_DEADLOCK_RETRY_BACKOFF_MILLIS, deadlockRetryBackoffMs)
            );
        }
        if (mandateLimitPerRun < 1 || mandateLimitPerRun > MAX_MANDATE_LIMIT_PER_RUN) {
            throw new IllegalStateException(
                    "stock.batch.liquidity-provider-market.mandate-limit-per-run "
                            + "must be between 1 and %d: %d"
                            .formatted(MAX_MANDATE_LIMIT_PER_RUN, mandateLimitPerRun)
            );
        }
    }

    public int runLiquidityProviderMarket() {
        long startedNanos = System.nanoTime();
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        if (!isRegularSessionActive(clock)) {
            return 0;
        }
        LocalDateTime now = clock.simulationDateTime();
        LocalDate simulationTradeDate = now.toLocalDate();
        Map<String, AutoMarketConfig> activeConfigs = activeConfigsBySymbol(
                simulationTradeDate,
                now
        );
        List<LiquidityProviderRepository.MandateReference> dueMandates =
                repository.findDueMandates(simulationTradeDate, now, mandateLimitPerRun);
        if (dueMandates.isEmpty()) {
            return 0;
        }

        RunTotals totals = RunTotals.EMPTY;
        for (LiquidityProviderRepository.MandateReference reference : dueMandates) {
            AutoMarketConfig activeConfig = activeConfigs.get(reference.symbol());
            boolean marketTradingEnabled = activeConfig != null;
            AutoMarketConfig resolvedConfig = activeConfig;
            if (resolvedConfig == null) {
                resolvedConfig = repository.findSafetyMarketConfig(reference.symbol()).orElse(null);
            }
            if (resolvedConfig == null) {
                meterRegistry.counter("stock.liquidity.provider.market.config.missing").increment();
                log.error(
                        "Liquidity-provider mandate skipped because market safety data is missing: "
                                + "mandateId={}, symbol={}",
                        reference.id(),
                        reference.symbol()
                );
                continue;
            }
            LiquidityProviderQuoteProcessor.ProcessResult result = runMandate(
                    reference,
                    resolvedConfig,
                    marketTradingEnabled,
                    simulationTradeDate
            );
            totals = totals.plus(result);
        }
        log.info(
                "Liquidity-provider market completed: dueMandates={}, processedMandates={}, "
                        + "cancelledOrders={}, generatedOrders={}, availableActiveSymbols={}, elapsedMs={}",
                dueMandates.size(),
                totals.processedMandates(),
                totals.cancelledOrders(),
                totals.generatedOrders(),
                activeConfigs.size(),
                elapsedMillis(startedNanos)
        );
        return totals.processedMandates();
    }

    private Map<String, AutoMarketConfig> activeConfigsBySymbol(
            LocalDate simulationTradeDate,
            LocalDateTime now
    ) {
        List<AutoMarketConfig> configs = autoMarketReader.findEnabledConfigs();
        if (configs.isEmpty()) {
            return Map.of();
        }
        List<AutoMarketConfig> regimeConfigs = dailyRegimeService.applyDailyRegimes(
                configs,
                simulationTradeDate,
                now
        );
        Map<String, AutoMarketConfig> bySymbol = new LinkedHashMap<>();
        for (AutoMarketConfig config : regimeConfigs) {
            AutoMarketConfig previous = bySymbol.putIfAbsent(config.symbol(), config);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate active auto-market configuration for liquidity symbol: "
                                + config.symbol()
                );
            }
        }
        return Map.copyOf(bySymbol);
    }

    private LiquidityProviderQuoteProcessor.ProcessResult runMandate(
            LiquidityProviderRepository.MandateReference reference,
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
                                        .lockOpenOrderBookFences(List.of(reference.symbol()))
                                        .map(sessionApproval -> quoteProcessor.process(
                                                reference.id(),
                                                config,
                                                marketTradingEnabled,
                                                simulationTradeDate,
                                                sessionApproval
                                        ))
                                        .orElse(LiquidityProviderQuoteProcessor.ProcessResult.SKIPPED)
                        );
                    }
                })
                .orElseGet(() -> {
                    meterRegistry.counter("stock.liquidity.provider.market.symbol.lock.skips").increment();
                    log.debug(
                            "Liquidity-provider mandate skipped because order-book symbol is busy: "
                                    + "mandateId={}, symbol={}",
                            reference.id(),
                            reference.symbol()
                    );
                    return LiquidityProviderQuoteProcessor.ProcessResult.SKIPPED;
                });
    }

    private LiquidityProviderQuoteProcessor.ProcessResult runInTransactionWithDeadlockRetry(
            String symbol,
            Supplier<LiquidityProviderQuoteProcessor.ProcessResult> action
    ) {
        CannotAcquireLockException lastException = null;
        for (int attempt = 1; attempt <= deadlockRetryMaxAttempts; attempt++) {
            try {
                LiquidityProviderQuoteProcessor.ProcessResult result =
                        transactionTemplate.execute(status -> action.get());
                return result == null
                        ? LiquidityProviderQuoteProcessor.ProcessResult.SKIPPED
                        : result;
            } catch (CannotAcquireLockException ex) {
                lastException = ex;
                if (attempt >= deadlockRetryMaxAttempts) {
                    break;
                }
                sleepBeforeRetry(symbol, attempt, ex);
            }
        }
        throw lastException;
    }

    private void sleepBeforeRetry(
            String symbol,
            int attempt,
            CannotAcquireLockException ex
    ) {
        long backoffMillis = deadlockRetryBackoffMs * attempt;
        log.warn(
                "Liquidity-provider market deadlock retry: symbol={}, attempt={}, backoffMs={}, reason={}",
                symbol,
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
                    "Interrupted during liquidity-provider market deadlock retry",
                    interrupted
            );
        }
    }

    private boolean isRegularSessionActive(SimulationClockSnapshot clock) {
        return clock.running()
                && simulationMarketSessionService.sessionAt(clock.simulationDateTime())
                == SimulationMarketSession.REGULAR;
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private record RunTotals(
            int processedMandates,
            int cancelledOrders,
            int generatedOrders
    ) {
        private static final RunTotals EMPTY = new RunTotals(0, 0, 0);

        private RunTotals plus(LiquidityProviderQuoteProcessor.ProcessResult result) {
            if (!result.processed()) {
                return this;
            }
            return new RunTotals(
                    processedMandates + 1,
                    cancelledOrders + result.cancelledOrderCount(),
                    generatedOrders + result.generatedOrderCount()
            );
        }
    }
}
