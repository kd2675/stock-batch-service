package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.automarket.config.AutoMarketGenerationExecutorConfig;
import stock.batch.service.automarket.lock.AutoMarketProfileLock;
import stock.batch.service.automarket.profile.ProfilePolicy;
import stock.batch.service.automarket.profile.ProfileSignalContext;
import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;
import stock.batch.service.batch.automarket.reader.AutoMarketReader;
import stock.batch.service.simulation.SimulationClockService;
import stock.batch.service.simulation.SimulationMarketSessionService;
import web.common.core.simulation.SimulationClockSnapshot;

import static stock.batch.service.automarket.biz.AutoMarketPricePolicy.positiveOrDefault;

@Service
@Slf4j
public class AutoMarketService {

    private static final String BUY = "BUY";
    private static final Duration PROJECT_SHORT_MOMENTUM_WINDOW = Duration.ofHours(1);

    private final AutoMarketReader autoMarketReader;
    private final AutoParticipantOrderService autoParticipantOrderService;
    private final AutoParticipantOrderScheduleService autoParticipantOrderScheduleService;
    private final AutoProfileBehaviorSupport autoProfileBehaviorSupport;
    private final SimulationClockService simulationClockService;
    private final SimulationMarketSessionService simulationMarketSessionService;
    private final TransactionTemplate transactionTemplate;
    private final AutoMarketProfileLock autoMarketProfileLock;
    private final Executor autoMarketGenerationTaskExecutor;

    @Value("${stock.batch.auto-market.generation-participant-chunk-size:25}")
    private int generationParticipantChunkSize;

    @Value("${stock.batch.auto-market.slow-symbol-log-threshold-ms:1000}")
    private long slowSymbolLogThresholdMs;

    public AutoMarketService(
            AutoMarketReader autoMarketReader,
            AutoParticipantOrderService autoParticipantOrderService,
            AutoParticipantOrderScheduleService autoParticipantOrderScheduleService,
            AutoProfileBehaviorSupport autoProfileBehaviorSupport,
            SimulationClockService simulationClockService,
            SimulationMarketSessionService simulationMarketSessionService,
            TransactionTemplate transactionTemplate,
            AutoMarketProfileLock autoMarketProfileLock,
            @Qualifier(AutoMarketGenerationExecutorConfig.AUTO_MARKET_GENERATION_TASK_EXECUTOR) Executor autoMarketGenerationTaskExecutor
    ) {
        this.autoMarketReader = autoMarketReader;
        this.autoParticipantOrderService = autoParticipantOrderService;
        this.autoParticipantOrderScheduleService = autoParticipantOrderScheduleService;
        this.autoProfileBehaviorSupport = autoProfileBehaviorSupport;
        this.simulationClockService = simulationClockService;
        this.simulationMarketSessionService = simulationMarketSessionService;
        this.transactionTemplate = transactionTemplate;
        this.autoMarketProfileLock = autoMarketProfileLock;
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
        Map<String, List<AutoParticipantStrategy>> strategiesBySymbol = autoMarketReader.findEnabledParticipantStrategiesBySymbol(configs);
        int activeParticipantCount = activeParticipantCount(strategiesBySymbol);
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        Map<String, BigDecimal> momentumReferencePricesBySymbol = activeParticipantCount == 0
                ? Map.of()
                : autoMarketReader.findLatestPricesAtOrBefore(
                        configs.stream()
                                .map(AutoMarketConfig::symbol)
                                .toList(),
                        clock.simulationDateTime().minus(PROJECT_SHORT_MOMENTUM_WINDOW)
                );
        if (isAutoMarketSessionActive()) {
            List<ProfileGenerationShard> shards = profileGenerationShards(
                    configs,
                    strategiesBySymbol,
                    momentumReferencePricesBySymbol
            );
            totalCount.add(runProfileGenerationShards(shards, profilePolicies, clock.simulationDateTime()));
        }
        log.info(
                "Auto market step completed: symbols={}, participants={}, profileShards={}, scheduledStrategies={}, dueStrategies={}, generatedOrders={}, reservedBuyOrders={}, reservedSellOrders={}, failedReserveOrders={}, generationChunks={}, processedCount={}, elapsedMs={}",
                configs.size(),
                activeParticipantCount,
                totalCount.profileShards,
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

    private List<ProfileGenerationShard> profileGenerationShards(
            List<AutoMarketConfig> configs,
            Map<String, List<AutoParticipantStrategy>> strategiesBySymbol,
            Map<String, BigDecimal> momentumReferencePricesBySymbol
    ) {
        Map<AutoParticipantProfileType, List<SymbolGenerationWork>> worksByProfile = new EnumMap<>(AutoParticipantProfileType.class);
        for (AutoMarketConfig config : configs) {
            List<AutoParticipantStrategy> strategies = strategiesBySymbol.getOrDefault(config.symbol(), List.of());
            if (strategies.isEmpty()) {
                continue;
            }
            Map<AutoParticipantProfileType, List<AutoParticipantStrategy>> byProfile = strategies.stream()
                    .collect(Collectors.groupingBy(AutoParticipantStrategy::profileType, () -> new EnumMap<>(AutoParticipantProfileType.class), Collectors.toList()));
            for (Map.Entry<AutoParticipantProfileType, List<AutoParticipantStrategy>> entry : byProfile.entrySet()) {
                worksByProfile.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>())
                        .add(new SymbolGenerationWork(
                                config,
                                entry.getValue(),
                                momentumReferencePricesBySymbol.get(config.symbol())
                        ));
            }
        }
        return worksByProfile.entrySet().stream()
                .map(entry -> new ProfileGenerationShard(entry.getKey(), entry.getValue()))
                .toList();
    }

    private AutoMarketRunCount runProfileGenerationShards(
            List<ProfileGenerationShard> shards,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now
    ) {
        if (shards.isEmpty()) {
            return new AutoMarketRunCount();
        }
        List<CompletableFuture<AutoMarketRunCount>> futures = shards.stream()
                .map(shard -> CompletableFuture.supplyAsync(
                        () -> runProfileGenerationShard(shard, profilePolicies, now),
                        autoMarketGenerationTaskExecutor
                ))
                .toList();
        AutoMarketRunCount total = new AutoMarketRunCount();
        for (CompletableFuture<AutoMarketRunCount> future : futures) {
            total.add(joinProfileGenerationResult(future));
        }
        return total;
    }

    private AutoMarketRunCount joinProfileGenerationResult(CompletableFuture<AutoMarketRunCount> future) {
        try {
            return future.join();
        } catch (CompletionException ex) {
            log.warn("Auto market profile generation shard failed: reason={}", ex.getMessage(), ex);
            return new AutoMarketRunCount();
        }
    }

    private AutoMarketRunCount runProfileGenerationShard(
            ProfileGenerationShard shard,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now
    ) {
        return autoMarketProfileLock.tryLock(shard.profileType())
                .map(lock -> {
                    try (lock) {
                        return runLockedProfileGenerationShard(shard, profilePolicies, now);
                    }
                })
                .orElseGet(AutoMarketRunCount::new);
    }

    private AutoMarketRunCount runLockedProfileGenerationShard(
            ProfileGenerationShard shard,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now
    ) {
        long startedNanos = System.nanoTime();
        AutoMarketRunCount count = new AutoMarketRunCount();
        count.profileShards++;
        for (SymbolGenerationWork work : shard.works()) {
            if (!isAutoMarketSessionActive()) {
                break;
            }
            AutoMarketRunCount symbolCount = runProfileSymbolGeneration(work, profilePolicies, now);
            count.add(symbolCount);
        }
        logSlowProfileShard(shard.profileType(), count, startedNanos);
        return count;
    }

    private AutoMarketRunCount runProfileSymbolGeneration(
            SymbolGenerationWork work,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now
    ) {
        AutoMarketRunCount count = new AutoMarketRunCount();
        count.scheduledStrategies += work.strategies().size();
        List<AutoParticipantStrategy> dueStrategies = runInTransaction(() -> autoParticipantOrderScheduleService.claimDueStrategies(
                work.config().symbol(),
                work.strategies(),
                profilePolicies,
                now
        ));
        count.dueStrategies += dueStrategies.size();
        if (dueStrategies.isEmpty() || !isAutoMarketSessionActive()) {
            return count;
        }

        double momentumPressure = priceMomentum(work.config(), work.momentumReferencePrice());
        int chunkSize = Math.max(1, generationParticipantChunkSize);
        List<AutoParticipantStrategy> completedStrategies = new ArrayList<>();
        for (int start = 0; start < dueStrategies.size(); start += chunkSize) {
            if (!isAutoMarketSessionActive()) {
                break;
            }
            int end = Math.min(dueStrategies.size(), start + chunkSize);
            List<AutoParticipantStrategy> chunk = dueStrategies.subList(start, end);
            AutoParticipantOrderGenerationResult generationResult = runInTransaction(() -> autoParticipantOrderService.placeAutoOrders(
                    chunk,
                    work.config(),
                    profilePolicies,
                    momentumPressure
            ));
            count.addGeneration(generationResult);
            count.generationChunks++;
            completedStrategies.addAll(chunk);
        }
        if (!completedStrategies.isEmpty()) {
            runIntInTransaction(() -> autoParticipantOrderScheduleService.completeStrategies(
                    work.config().symbol(),
                    completedStrategies,
                    profilePolicies,
                    now
            ));
        }
        return count;
    }

    private boolean isAutoMarketSessionActive() {
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        return !clock.running() || simulationMarketSessionService.isRegularSession();
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

    private int runIntInTransaction(Supplier<Integer> action) {
        Integer result = transactionTemplate.execute(status -> action.get());
        return result == null ? 0 : result;
    }

    private <T> T runInTransaction(Supplier<T> action) {
        return transactionTemplate.execute(status -> action.get());
    }

    private void logSlowProfileShard(AutoParticipantProfileType profileType, AutoMarketRunCount count, long startedNanos) {
        long elapsedMillis = elapsedMillis(startedNanos);
        if (elapsedMillis < Math.max(0, slowSymbolLogThresholdMs)) {
            return;
        }
        log.info(
                "Auto market profile generation shard completed: profileType={}, scheduledStrategies={}, dueStrategies={}, generatedOrders={}, reservedBuyOrders={}, reservedSellOrders={}, failedReserveOrders={}, generationChunks={}, processedCount={}, elapsedMs={}",
                profileType,
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
        private int profileShards;
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
            profileShards += other.profileShards;
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

    private record ProfileGenerationShard(
            AutoParticipantProfileType profileType,
            List<SymbolGenerationWork> works
    ) {
    }

    private record SymbolGenerationWork(
            AutoMarketConfig config,
            List<AutoParticipantStrategy> strategies,
            BigDecimal momentumReferencePrice
    ) {
    }

}
