package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.automarket.config.AutoMarketGenerationExecutorConfig;
import stock.batch.service.automarket.profile.ProfilePolicy;
import stock.batch.service.automarket.profile.ProfileSignalContext;
import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;
import stock.batch.service.batch.automarket.model.AutoParticipantSymbolStrategy;
import stock.batch.service.batch.automarket.reader.AutoMarketReader;
import stock.batch.service.execution.lock.OrderBookSymbolLock;
import stock.batch.service.simulation.SimulationClockService;
import stock.batch.service.simulation.SimulationMarketSessionService;
import web.common.core.simulation.SimulationClockSnapshot;

import static stock.batch.service.automarket.biz.AutoMarketPricePolicy.positiveOrDefault;

@Service
@Slf4j
public class AutoMarketService {

    private static final Duration PROJECT_SHORT_MOMENTUM_WINDOW = Duration.ofHours(1);

    private final AutoMarketReader autoMarketReader;
    private final AutoParticipantOrderService autoParticipantOrderService;
    private final AutoParticipantOrderScheduleService autoParticipantOrderScheduleService;
    private final AutoProfileBehaviorSupport autoProfileBehaviorSupport;
    private final SimulationClockService simulationClockService;
    private final SimulationMarketSessionService simulationMarketSessionService;
    private final TransactionTemplate transactionTemplate;
    private final OrderBookSymbolLock orderBookSymbolLock;
    private final Executor autoMarketGenerationTaskExecutor;

    @Value("${stock.batch.auto-market.generation-participant-chunk-size:25}")
    private int generationParticipantChunkSize;

    @Value("${stock.batch.auto-market.generation-due-limit-per-symbol:100}")
    private int generationDueLimitPerSymbol;

    @Value("${stock.batch.auto-market.slow-symbol-log-threshold-ms:1000}")
    private long slowSymbolLogThresholdMs;

    @Value("${stock.batch.auto-market.deadlock-retry-max-attempts:5}")
    private int deadlockRetryMaxAttempts = 5;

    @Value("${stock.batch.auto-market.deadlock-retry-backoff-ms:50}")
    private long deadlockRetryBackoffMs = 50;

    public AutoMarketService(
            AutoMarketReader autoMarketReader,
            AutoParticipantOrderService autoParticipantOrderService,
            AutoParticipantOrderScheduleService autoParticipantOrderScheduleService,
            AutoProfileBehaviorSupport autoProfileBehaviorSupport,
            SimulationClockService simulationClockService,
            SimulationMarketSessionService simulationMarketSessionService,
            TransactionTemplate transactionTemplate,
            OrderBookSymbolLock orderBookSymbolLock,
            @Qualifier(AutoMarketGenerationExecutorConfig.AUTO_MARKET_GENERATION_TASK_EXECUTOR) Executor autoMarketGenerationTaskExecutor
    ) {
        this.autoMarketReader = autoMarketReader;
        this.autoParticipantOrderService = autoParticipantOrderService;
        this.autoParticipantOrderScheduleService = autoParticipantOrderScheduleService;
        this.autoProfileBehaviorSupport = autoProfileBehaviorSupport;
        this.simulationClockService = simulationClockService;
        this.simulationMarketSessionService = simulationMarketSessionService;
        this.transactionTemplate = transactionTemplate;
        this.orderBookSymbolLock = orderBookSymbolLock;
        this.autoMarketGenerationTaskExecutor = autoMarketGenerationTaskExecutor;
    }

    public int runAutoMarketStep() {
        long startedNanos = System.nanoTime();
        if (!isAutoMarketSessionActive()) {
            return 0;
        }
        List<AutoMarketConfig> configs = autoMarketReader.findEnabledConfigs();
        if (configs.isEmpty()) {
            return 0;
        }

        AutoMarketRunCount totalCount = new AutoMarketRunCount();
        Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies = loadProfilePolicies();
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        Map<String, AutoMarketConfig> configBySymbol = configs.stream()
                .collect(Collectors.toMap(
                        AutoMarketConfig::symbol,
                        config -> config,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        if (autoMarketReader.hasMissingParticipantSchedules(configs)) {
            Map<String, List<AutoParticipantStrategy>> enabledStrategiesBySymbol = autoMarketReader.findEnabledParticipantStrategiesBySymbol(configs);
            runInTransactionWithDeadlockRetry("auto-market-schedule", () -> autoParticipantOrderScheduleService.ensureSchedules(
                    uniqueParticipantStrategies(enabledStrategiesBySymbol),
                    profilePolicies,
                    clock.simulationDateTime()
            ));
        }
        List<AutoParticipantSymbolStrategy> candidates = autoMarketReader.findDueParticipantSymbolStrategies(
                configs,
                clock.simulationDateTime(),
                generationDueParticipantLimit(configs.size())
        );
        Map<String, List<AutoParticipantStrategy>> strategiesBySymbol = selectedStrategiesBySymbol(
                candidates,
                configBySymbol,
                profilePolicies,
                clock.simulationDateTime()
        );
        int activeParticipantCount = activeParticipantCount(strategiesBySymbol);
        Map<String, BigDecimal> momentumReferencePricesBySymbol = activeParticipantCount == 0
                ? Map.of()
                : autoMarketReader.findLatestPricesAtOrBefore(
                        configs.stream()
                                .map(AutoMarketConfig::symbol)
                                .toList(),
                        clock.simulationDateTime().minus(PROJECT_SHORT_MOMENTUM_WINDOW)
                );
        List<SymbolGenerationWork> works = symbolGenerationWorks(
                configs,
                strategiesBySymbol,
                momentumReferencePricesBySymbol
        );
        totalCount.add(runSymbolGenerationWorks(works, profilePolicies, clock.simulationDateTime()));
        log.info(
                "Auto market step completed: symbols={}, participants={}, symbolShards={}, scheduledStrategies={}, dueStrategies={}, generatedOrders={}, reservedBuyOrders={}, reservedSellOrders={}, failedReserveOrders={}, generationChunks={}, processedCount={}, elapsedMs={}",
                configs.size(),
                activeParticipantCount,
                totalCount.symbolShards,
                totalCount.scheduledStrategies,
                totalCount.dueStrategies,
                totalCount.generatedOrders,
                totalCount.reservedBuyOrders,
                totalCount.reservedSellOrders,
                totalCount.failedReserveOrders,
                totalCount.generationChunks,
                totalCount.processedCount(),
                elapsedMillis(startedNanos)
        );
        return totalCount.processedCount();
    }

    private int activeParticipantCount(Map<String, List<AutoParticipantStrategy>> strategiesBySymbol) {
        return (int) strategiesBySymbol.values()
                .stream()
                .flatMap(List::stream)
                .map(AutoParticipantStrategy::accountId)
                .distinct()
                .count();
    }

    private int generationDueParticipantLimit(int symbolCount) {
        return Math.max(1, generationDueLimitPerSymbol) * Math.max(1, symbolCount);
    }

    private List<AutoParticipantStrategy> uniqueParticipantStrategies(Map<String, List<AutoParticipantStrategy>> strategiesBySymbol) {
        Map<String, AutoParticipantStrategy> strategiesByUserKey = new LinkedHashMap<>();
        strategiesBySymbol.values()
                .stream()
                .flatMap(List::stream)
                .filter(strategy -> strategy.userKey() != null && !strategy.userKey().isBlank())
                .forEach(strategy -> strategiesByUserKey.putIfAbsent(strategy.userKey(), strategy));
        return List.copyOf(strategiesByUserKey.values());
    }

    private Map<String, List<AutoParticipantStrategy>> selectedStrategiesBySymbol(
            List<AutoParticipantSymbolStrategy> candidates,
            Map<String, AutoMarketConfig> configBySymbol,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now
    ) {
        Map<String, AutoParticipantSymbolStrategy> selectedByUserKey = new LinkedHashMap<>();
        for (AutoParticipantSymbolStrategy candidate : candidates) {
            AutoParticipantStrategy strategy = candidate.strategy();
            if (strategy.userKey() == null || strategy.userKey().isBlank()) {
                continue;
            }
            AutoMarketConfig config = configBySymbol.get(candidate.symbol());
            if (config == null) {
                continue;
            }
            AutoParticipantSymbolStrategy current = selectedByUserKey.get(strategy.userKey());
            if (current == null || symbolSelectionScore(candidate, config, profilePolicies, now) > symbolSelectionScore(current, configBySymbol.get(current.symbol()), profilePolicies, now)) {
                selectedByUserKey.put(strategy.userKey(), candidate);
            }
        }
        Map<String, List<AutoParticipantStrategy>> strategiesBySymbol = new LinkedHashMap<>();
        selectedByUserKey.values()
                .stream()
                .sorted((left, right) -> {
                    int symbolComparison = left.symbol().compareTo(right.symbol());
                    if (symbolComparison != 0) {
                        return symbolComparison;
                    }
                    return left.strategy().userKey().compareTo(right.strategy().userKey());
                })
                .forEach(selected -> strategiesBySymbol
                        .computeIfAbsent(selected.symbol(), ignored -> new ArrayList<>())
                        .add(selected.strategy()));
        return strategiesBySymbol.entrySet()
                .stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> List.copyOf(entry.getValue()),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private double symbolSelectionScore(
            AutoParticipantSymbolStrategy candidate,
            AutoMarketConfig config,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now
    ) {
        if (config == null) {
            return Double.NEGATIVE_INFINITY;
        }
        AutoParticipantStrategy strategy = candidate.strategy();
        ProfilePolicy policy = autoProfileBehaviorSupport.policy(profilePolicies, strategy.profileType());
        double overdueSeconds = Math.max(0, Duration.between(candidate.nextRunAt(), now).toSeconds());
        double overdueScore = Math.min(240.0, overdueSeconds / 30.0);
        double priorityScore = Math.clamp(candidate.priority(), 1, 100) / 100.0;
        double intensityScore = Math.clamp(strategy.intensity(), 1, 10) / 10.0;
        double momentum = priceChangeRate(config);
        double reportScore = config.reportScore() == null ? 0 : (Math.clamp(config.reportScore(), 1, 10) - 5.5) / 4.5;
        double profileSignal = policy.momentumWeight() * momentum
                - policy.contrarianWeight() * momentum
                + policy.newsWeight() * reportScore
                + policy.dipBuyWeight() * Math.max(0, -momentum)
                - policy.panicSellWeight() * Math.max(0, -momentum) * 0.25
                + policy.marketMakingWeight() * (1.0 - Math.min(1.0, Math.abs(momentum)));
        double deterministicSpread = Math.floorMod((strategy.userKey() + "\n" + candidate.symbol()).hashCode(), 1000) / 10000.0;
        return overdueScore * 2.0
                + priorityScore
                + intensityScore
                + profileSignal
                + deterministicSpread;
    }

    private double priceChangeRate(AutoMarketConfig config) {
        BigDecimal previousClose = positiveOrDefault(config.previousClose(), config.currentPrice());
        if (previousClose.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        return Math.clamp(
                config.currentPrice()
                        .subtract(previousClose)
                        .divide(previousClose, 6, RoundingMode.HALF_UP)
                        .doubleValue(),
                -1,
                1
        );
    }

    private List<SymbolGenerationWork> symbolGenerationWorks(
            List<AutoMarketConfig> configs,
            Map<String, List<AutoParticipantStrategy>> strategiesBySymbol,
            Map<String, BigDecimal> momentumReferencePricesBySymbol
    ) {
        List<SymbolGenerationWork> works = new ArrayList<>();
        for (AutoMarketConfig config : configs) {
            List<AutoParticipantStrategy> strategies = strategiesBySymbol.getOrDefault(config.symbol(), List.of());
            if (strategies.isEmpty()) {
                continue;
            }
            works.add(new SymbolGenerationWork(
                    config,
                    strategies,
                    momentumReferencePricesBySymbol.get(config.symbol())
            ));
        }
        return works;
    }

    private AutoMarketRunCount runSymbolGenerationWorks(
            List<SymbolGenerationWork> works,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now
    ) {
        if (works.isEmpty()) {
            return new AutoMarketRunCount();
        }
        List<CompletableFuture<AutoMarketRunCount>> futures = works.stream()
                .map(work -> CompletableFuture.supplyAsync(
                        () -> runSymbolGenerationWork(work, profilePolicies, now),
                        autoMarketGenerationTaskExecutor
                ))
                .toList();
        AutoMarketRunCount total = new AutoMarketRunCount();
        for (CompletableFuture<AutoMarketRunCount> future : futures) {
            total.add(joinSymbolGenerationResult(future));
        }
        return total;
    }

    private AutoMarketRunCount joinSymbolGenerationResult(CompletableFuture<AutoMarketRunCount> future) {
        try {
            return future.join();
        } catch (CompletionException ex) {
            log.warn("Auto market symbol generation shard failed: reason={}", ex.getMessage(), ex);
            return new AutoMarketRunCount();
        }
    }

    private AutoMarketRunCount runSymbolGenerationWork(
            SymbolGenerationWork work,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now
    ) {
        return orderBookSymbolLock.tryLock(work.config().symbol())
                .map(lock -> {
                    try (lock) {
                        return runLockedSymbolGenerationWork(work, profilePolicies, now);
                    }
                })
                .orElseGet(AutoMarketRunCount::new);
    }

    private AutoMarketRunCount runLockedSymbolGenerationWork(
            SymbolGenerationWork work,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now
    ) {
        long startedNanos = System.nanoTime();
        AutoMarketRunCount count = runSymbolGeneration(work, profilePolicies, now);
        count.symbolShards++;
        logSlowSymbolShard(work.config().symbol(), count, startedNanos);
        return count;
    }

    private AutoMarketRunCount runSymbolGeneration(
            SymbolGenerationWork work,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now
    ) {
        AutoMarketRunCount count = new AutoMarketRunCount();
        count.scheduledStrategies += work.strategies().size();
        List<AutoParticipantStrategy> dueStrategies = runInTransactionWithDeadlockRetry(work.config().symbol(), () -> autoParticipantOrderScheduleService.claimDueStrategies(
                work.strategies(),
                profilePolicies,
                now,
                false
        ));
        count.dueStrategies += dueStrategies.size();
        if (dueStrategies.isEmpty()) {
            return count;
        }

        double momentumPressure = priceMomentum(work.config(), work.momentumReferencePrice());
        int chunkSize = Math.max(1, generationParticipantChunkSize);
        for (int start = 0; start < dueStrategies.size(); start += chunkSize) {
            int end = Math.min(dueStrategies.size(), start + chunkSize);
            List<AutoParticipantStrategy> chunk = dueStrategies.subList(start, end);
            AutoParticipantOrderGenerationResult generationResult = runInTransactionWithDeadlockRetry(work.config().symbol(), () -> {
                AutoParticipantOrderGenerationResult result = autoParticipantOrderService.placeAutoOrders(
                        chunk,
                        work.config(),
                        profilePolicies,
                        momentumPressure
                );
                autoParticipantOrderScheduleService.completeStrategies(
                        chunk,
                        profilePolicies,
                        now
                );
                return result;
            });
            count.addGeneration(generationResult);
            count.generationChunks++;
        }
        return count;
    }

    private boolean isAutoMarketSessionActive() {
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        return clock.running() && simulationMarketSessionService.isRegularSession();
    }

    int effectiveIntensity(AutoParticipantStrategy strategy, AutoMarketConfig config) {
        return autoProfileBehaviorSupport.behavior(strategy.profileType()).effectiveIntensity(strategy, config, profilePolicy(strategy.profileType()));
    }

    double buyBiasForProfile(
            AutoParticipantProfileType profileType,
            int effectiveIntensity,
            double momentumPressure,
            double herdPressure,
            double unrealizedReturn,
            long availableQuantity
    ) {
        ProfilePolicy policy = profilePolicy(profileType);
        ProfileSignalContext context = new ProfileSignalContext(null, null, policy, effectiveIntensity, momentumPressure, herdPressure, unrealizedReturn, availableQuantity, BigDecimal.ZERO, false, 0, 0);
        return autoProfileBehaviorSupport.behavior(profileType).buyBias(context);
    }

    int orderCountForProfile(AutoParticipantProfileType profileType, int effectiveIntensity, double unrealizedReturn) {
        ProfilePolicy policy = profilePolicy(profileType);
        ProfileSignalContext context = new ProfileSignalContext(null, null, policy, effectiveIntensity, 0, 0, unrealizedReturn, 0, BigDecimal.ZERO, false, 0, 0);
        return autoProfileBehaviorSupport.behavior(profileType).orderCount(context);
    }

    int quantityUpperBoundForProfile(AutoParticipantProfileType profileType, int maxOrderQuantity) {
        return autoProfileBehaviorSupport.behavior(profileType).quantityUpperBound(Math.max(1, maxOrderQuantity), profilePolicy(profileType));
    }

    int orderTtlSecondsForProfile(AutoParticipantProfileType profileType, int baseTtlSeconds) {
        return autoProfileBehaviorSupport.behavior(profileType).orderTtlSeconds(Math.max(1, baseTtlSeconds), profilePolicy(profileType));
    }

    int runtimeOrderTtlSecondsForProfile(AutoParticipantProfileType profileType, int baseTtlSeconds) {
        return orderTtlSecondsForProfile(profileType, baseTtlSeconds);
    }

    double priceMomentum(AutoMarketConfig config) {
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        BigDecimal referencePrice = autoMarketReader.findLatestPriceAtOrBefore(
                        config.symbol(),
                        clock.simulationDateTime().minus(PROJECT_SHORT_MOMENTUM_WINDOW)
                )
                .orElseGet(() -> positiveOrDefault(config.previousClose(), config.currentPrice()));
        return priceMomentum(config, referencePrice);
    }

    private double priceMomentum(AutoMarketConfig config, BigDecimal referencePriceFromTick) {
        BigDecimal referencePrice = referencePriceFromTick == null
                ? positiveOrDefault(config.previousClose(), config.currentPrice())
                : referencePriceFromTick;
        if (referencePrice.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        double rate = config.currentPrice()
                .subtract(referencePrice)
                .divide(referencePrice, 6, RoundingMode.HALF_UP)
                .doubleValue();
        return Math.clamp(rate / 0.05, -1, 1);
    }

    private ProfilePolicy profilePolicy(AutoParticipantProfileType profileType) {
        return autoProfileBehaviorSupport.defaultPolicy(profileType);
    }

    private Map<AutoParticipantProfileType, ProfilePolicy> loadProfilePolicies() {
        return autoProfileBehaviorSupport.policiesWithOverrides(autoMarketReader.findParticipantProfileConfigs());
    }

    private <T> T runInTransaction(Supplier<T> action) {
        return transactionTemplate.execute(status -> action.get());
    }

    private <T> T runInTransactionWithDeadlockRetry(String symbol, Supplier<T> action) {
        int attempts = Math.max(1, deadlockRetryMaxAttempts);
        CannotAcquireLockException lastException = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return runInTransaction(action);
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
                "Auto market generation deadlock retry: symbol={}, attempt={}, backoffMs={}, reason={}",
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
            throw new IllegalStateException("Interrupted during auto market generation deadlock retry", interrupted);
        }
    }

    private void logSlowSymbolShard(String symbol, AutoMarketRunCount count, long startedNanos) {
        long elapsedMillis = elapsedMillis(startedNanos);
        if (elapsedMillis < Math.max(0, slowSymbolLogThresholdMs)) {
            return;
        }
        log.info(
                "Auto market symbol generation shard completed: symbol={}, scheduledStrategies={}, dueStrategies={}, generatedOrders={}, reservedBuyOrders={}, reservedSellOrders={}, failedReserveOrders={}, generationChunks={}, processedCount={}, elapsedMs={}",
                symbol,
                count.scheduledStrategies,
                count.dueStrategies,
                count.generatedOrders,
                count.reservedBuyOrders,
                count.reservedSellOrders,
                count.failedReserveOrders,
                count.generationChunks,
                count.processedCount(),
                elapsedMillis
        );
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static final class AutoMarketRunCount {
        private int symbolShards;
        private int scheduledStrategies;
        private int dueStrategies;
        private int generatedOrders;
        private int generationChunks;
        private int reservedBuyOrders;
        private int reservedSellOrders;
        private int failedReserveOrders;

        private int processedCount() {
            return generatedOrders;
        }

        private void add(AutoMarketRunCount other) {
            symbolShards += other.symbolShards;
            scheduledStrategies += other.scheduledStrategies;
            dueStrategies += other.dueStrategies;
            generatedOrders += other.generatedOrders;
            generationChunks += other.generationChunks;
            reservedBuyOrders += other.reservedBuyOrders;
            reservedSellOrders += other.reservedSellOrders;
            failedReserveOrders += other.failedReserveOrders;
        }

        private void addGeneration(AutoParticipantOrderGenerationResult generationResult) {
            if (generationResult == null) {
                return;
            }
            generatedOrders += generationResult.generatedOrderCount();
            reservedBuyOrders += generationResult.reservedBuyCount();
            reservedSellOrders += generationResult.reservedSellCount();
            failedReserveOrders += generationResult.failedReserveCount();
        }
    }

    private record SymbolGenerationWork(
            AutoMarketConfig config,
            List<AutoParticipantStrategy> strategies,
            BigDecimal momentumReferencePrice
    ) {
    }

}
