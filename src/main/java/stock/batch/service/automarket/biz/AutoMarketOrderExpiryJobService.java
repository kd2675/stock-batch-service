package stock.batch.service.automarket.biz;

import jakarta.annotation.PostConstruct;

import java.time.Instant;
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

import stock.batch.service.automarket.profile.ProfilePolicy;
import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
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
public class AutoMarketOrderExpiryJobService {

    private static final int MAX_DEADLOCK_RETRY_ATTEMPTS = 10;
    private static final long MAX_DEADLOCK_RETRY_BACKOFF_MILLIS = 1_000L;
    private static final int MAX_SYMBOL_LIMIT_PER_RUN = 500;

    private final AutoMarketReader autoMarketReader;
    private final AutoMarketOrderExpiryService autoMarketOrderExpiryService;
    private final AutoProfileBehaviorSupport autoProfileBehaviorSupport;
    private final SimulationClockService simulationClockService;
    private final SimulationMarketSessionService simulationMarketSessionService;
    private final TransactionTemplate transactionTemplate;
    private final OrderBookSymbolLock orderBookSymbolLock;
    private final MarketSessionFenceService marketSessionFenceService;
    private final MeterRegistry meterRegistry;

    @Value("${stock.batch.auto-market-order-expiry.deadlock-retry-max-attempts:5}")
    private int deadlockRetryMaxAttempts = 5;

    @Value("${stock.batch.auto-market-order-expiry.deadlock-retry-backoff-ms:50}")
    private long deadlockRetryBackoffMs = 50;

    @Value("${stock.batch.auto-market-order-expiry.symbol-limit-per-run:100}")
    private int symbolLimitPerRun = 100;

    @PostConstruct
    void validateRetryConfiguration() {
        if (deadlockRetryMaxAttempts < 1 || deadlockRetryMaxAttempts > MAX_DEADLOCK_RETRY_ATTEMPTS) {
            throw new IllegalStateException(
                    "stock.batch.auto-market-order-expiry.deadlock-retry-max-attempts must be between 1 and %d: %d"
                            .formatted(MAX_DEADLOCK_RETRY_ATTEMPTS, deadlockRetryMaxAttempts)
            );
        }
        if (deadlockRetryBackoffMs < 0 || deadlockRetryBackoffMs > MAX_DEADLOCK_RETRY_BACKOFF_MILLIS) {
            throw new IllegalStateException(
                    "stock.batch.auto-market-order-expiry.deadlock-retry-backoff-ms must be between 0 and %d: %d"
                            .formatted(MAX_DEADLOCK_RETRY_BACKOFF_MILLIS, deadlockRetryBackoffMs)
            );
        }
        if (symbolLimitPerRun < 1 || symbolLimitPerRun > MAX_SYMBOL_LIMIT_PER_RUN) {
            throw new IllegalStateException(
                    "stock.batch.auto-market-order-expiry.symbol-limit-per-run must be between 1 and %d: %d"
                            .formatted(MAX_SYMBOL_LIMIT_PER_RUN, symbolLimitPerRun)
            );
        }
    }

    public int expireAutoMarketOrders() {
        long startedNanos = System.nanoTime();
        SimulationClockSnapshot clock = regularSessionClock();
        if (clock == null) {
            return 0;
        }
        List<AutoMarketConfig> allConfigs = autoMarketReader.findEnabledMaintenanceConfigs();
        if (allConfigs.isEmpty()) {
            return 0;
        }
        List<AutoMarketConfig> configs = AutoMarketSymbolWorkSlice.select(
                allConfigs,
                AutoMarketConfig::symbol,
                symbolLimitPerRun,
                Instant.now()
        );
        Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies =
                autoProfileBehaviorSupport.policiesWithOverrides(autoMarketReader.findParticipantProfileConfigs());
        List<Long> activeV2MarketMakerAccountIds =
                autoMarketOrderExpiryService.loadActiveV2MarketMakerAccountIds();

        int expiredOrders = 0;
        for (AutoMarketConfig config : configs) {
            // Candidate discovery may scan every open order for the symbol. Keep it outside the
            // execution-critical Redis symbol lock; exact rows are locked and revalidated only
            // after the lock and session fence have both been acquired.
            AutoMarketOrderExpiryService.ExpiryCandidatePlan plan =
                    autoMarketOrderExpiryService.planExpiryCandidates(
                            config,
                            profilePolicies,
                            clock.simulationDateTime(),
                            activeV2MarketMakerAccountIds
                    );
            if (plan.hasWork()) {
                expiredOrders += expireSymbolOrders(config, plan);
            }
        }
        log.info(
                "Auto market order expiry completed: symbols={}, availableSymbols={}, expiredOrders={}, elapsedMs={}",
                configs.size(),
                allConfigs.size(),
                expiredOrders,
                elapsedMillis(startedNanos)
        );
        return expiredOrders;
    }

    private int expireSymbolOrders(
            AutoMarketConfig config,
            AutoMarketOrderExpiryService.ExpiryCandidatePlan plan
    ) {
        return orderBookSymbolLock.tryLock(config.symbol())
                .map(lock -> {
                    try (lock) {
                        try {
                            return runIntInTransactionWithDeadlockRetry(
                                    config.symbol(),
                                    () -> marketSessionFenceService.lockOpenOrderBookFences(List.of(config.symbol()))
                                            .map(approval -> autoMarketOrderExpiryService.expirePlannedOrders(
                                                    config,
                                                    plan,
                                                    approval.businessEffectiveAt()
                                            ))
                                            .orElse(0)
                            );
                        } catch (CannotAcquireLockException ex) {
                            log.warn(
                                    "Auto market order expiry skipped after lock retry exhaustion: symbol={}, reason={}",
                                    config.symbol(),
                                    ex.getMessage()
                            );
                            return 0;
                        }
                    }
                })
                .orElseGet(() -> {
                    meterRegistry.counter("stock.auto.market.order.expiry.symbol.lock.skips").increment();
                    log.debug("Auto market order expiry skipped because order-book symbol is busy: symbol={}", config.symbol());
                    return 0;
                });
    }

    private SimulationClockSnapshot regularSessionClock() {
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        boolean regularSessionActive = clock.running()
                && simulationMarketSessionService.sessionAt(clock.simulationDateTime())
                == SimulationMarketSession.REGULAR;
        return regularSessionActive ? clock : null;
    }

    private int runIntInTransaction(Supplier<Integer> action) {
        Integer result = transactionTemplate.execute(status -> action.get());
        return result == null ? 0 : result;
    }

    private int runIntInTransactionWithDeadlockRetry(String symbol, Supplier<Integer> action) {
        int attempts = Math.max(1, deadlockRetryMaxAttempts);
        CannotAcquireLockException lastException = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return runIntInTransaction(action);
            } catch (CannotAcquireLockException ex) {
                lastException = ex;
                if (attempt >= attempts) {
                    break;
                }
                sleepBeforeRetry(symbol, attempt, ex);
            }
        }
        throw lastException;
    }

    private void sleepBeforeRetry(String symbol, int attempt, CannotAcquireLockException ex) {
        long backoffMillis = Math.max(0, deadlockRetryBackoffMs) * attempt;
        log.warn(
                "Auto market order expiry deadlock retry: symbol={}, attempt={}, backoffMs={}, reason={}",
                symbol,
                attempt,
                backoffMillis,
                ex.getMessage()
        );
        if (backoffMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(backoffMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during auto market order expiry deadlock retry", interrupted);
        }
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
