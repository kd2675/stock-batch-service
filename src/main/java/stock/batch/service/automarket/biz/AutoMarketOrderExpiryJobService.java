package stock.batch.service.automarket.biz;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

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
import stock.batch.service.simulation.SimulationClockService;
import stock.batch.service.simulation.SimulationMarketSessionService;
import web.common.core.simulation.SimulationClockSnapshot;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutoMarketOrderExpiryJobService {

    private final AutoMarketReader autoMarketReader;
    private final AutoMarketOrderExpiryService autoMarketOrderExpiryService;
    private final AutoProfileBehaviorSupport autoProfileBehaviorSupport;
    private final SimulationClockService simulationClockService;
    private final SimulationMarketSessionService simulationMarketSessionService;
    private final TransactionTemplate transactionTemplate;
    private final OrderBookSymbolLock orderBookSymbolLock;

    @Value("${stock.batch.auto-market-order-expiry.deadlock-retry-max-attempts:5}")
    private int deadlockRetryMaxAttempts = 5;

    @Value("${stock.batch.auto-market-order-expiry.deadlock-retry-backoff-ms:50}")
    private long deadlockRetryBackoffMs = 50;

    public int expireAutoMarketOrders() {
        long startedNanos = System.nanoTime();
        if (!isRegularSessionActive()) {
            return 0;
        }
        List<AutoMarketConfig> configs = autoMarketReader.findEnabledConfigs();
        if (configs.isEmpty()) {
            return 0;
        }
        Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies =
                autoProfileBehaviorSupport.policiesWithOverrides(autoMarketReader.findParticipantProfileConfigs());

        int expiredOrders = 0;
        for (AutoMarketConfig config : configs) {
            expiredOrders += expireSymbolOrders(config, profilePolicies);
        }
        log.info(
                "Auto market order expiry completed: symbols={}, expiredOrders={}, elapsedMs={}",
                configs.size(),
                expiredOrders,
                elapsedMillis(startedNanos)
        );
        return expiredOrders;
    }

    private int expireSymbolOrders(
            AutoMarketConfig config,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies
    ) {
        return orderBookSymbolLock.tryLock(config.symbol())
                .map(lock -> {
                    try (lock) {
                        try {
                            return runIntInTransactionWithDeadlockRetry(
                                    config.symbol(),
                                    () -> autoMarketOrderExpiryService.expireOldAutoOrders(config, profilePolicies)
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
                    log.warn("Auto market order expiry skipped because order-book symbol is busy: symbol={}", config.symbol());
                    return 0;
                });
    }

    private boolean isRegularSessionActive() {
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        return clock.running() && simulationMarketSessionService.isRegularSession();
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
