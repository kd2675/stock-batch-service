package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import stock.batch.service.automarket.lock.AutoMarketProfileLock;
import stock.batch.service.automarket.profile.ProfilePolicy;
import stock.batch.service.automarket.profile.ProfileSignalContext;
import stock.batch.service.automarket.queue.AutoMarketReadyProfileQueue;
import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;
import stock.batch.service.batch.automarket.model.AutoParticipantSymbolStrategy;
import stock.batch.service.batch.automarket.reader.AutoMarketReader;
import stock.batch.service.simulation.SimulationClockService;
import stock.batch.service.simulation.SimulationMarketSessionService;
import web.common.core.simulation.SimulationClockSnapshot;

import static stock.batch.service.automarket.biz.AutoMarketPricePolicy.positiveOrDefault;

@Service
@Slf4j
public class AutoMarketService {

    private static final Duration PROJECT_SHORT_MOMENTUM_WINDOW = Duration.ofHours(1);

    private final AutoMarketReader autoMarketReader;
    private final AutoMarketDailyRegimeService autoMarketDailyRegimeService;
    private final AutoParticipantOrderService autoParticipantOrderService;
    private final AutoParticipantOrderScheduleService autoParticipantOrderScheduleService;
    private final AutoProfileBehaviorSupport autoProfileBehaviorSupport;
    private final SimulationClockService simulationClockService;
    private final SimulationMarketSessionService simulationMarketSessionService;
    private final TransactionTemplate transactionTemplate;
    private final AutoMarketProfileLock autoMarketProfileLock;
    private final AutoMarketReadyProfileQueue readyProfileQueue;
    private final Executor autoMarketGenerationTaskExecutor;

    @Value("${stock.batch.auto-market.generation-participant-chunk-size:25}")
    private int generationParticipantChunkSize;

    @Value("${stock.batch.auto-market.generation-due-limit-per-symbol:100}")
    private int generationDueLimitPerSymbol;

    @Value("${stock.batch.auto-market.generation-profile-worker-count:9}")
    private int generationProfileWorkerCount;

    @Value("${stock.batch.auto-market.slow-symbol-log-threshold-ms:1000}")
    private long slowSymbolLogThresholdMs;

    @Value("${stock.batch.auto-market.deadlock-retry-max-attempts:5}")
    private int deadlockRetryMaxAttempts = 5;

    @Value("${stock.batch.auto-market.deadlock-retry-backoff-ms:50}")
    private long deadlockRetryBackoffMs = 50;

    @Value("${stock.batch.auto-market.symbol-selection.diversity-penalty:3.0}")
    private double symbolSelectionDiversityPenalty;

    @Value("${stock.batch.auto-market.symbol-selection.participant-affinity-weight:0.75}")
    private double symbolSelectionParticipantAffinityWeight;

    @Value("${stock.batch.auto-market.symbol-selection.max-share-per-profile:0.55}")
    private double symbolSelectionMaxSharePerProfile;

    public AutoMarketService(
            AutoMarketReader autoMarketReader,
            AutoMarketDailyRegimeService autoMarketDailyRegimeService,
            AutoParticipantOrderService autoParticipantOrderService,
            AutoParticipantOrderScheduleService autoParticipantOrderScheduleService,
            AutoProfileBehaviorSupport autoProfileBehaviorSupport,
            SimulationClockService simulationClockService,
            SimulationMarketSessionService simulationMarketSessionService,
            TransactionTemplate transactionTemplate,
            AutoMarketProfileLock autoMarketProfileLock,
            AutoMarketReadyProfileQueue readyProfileQueue,
            @Qualifier(AutoMarketGenerationExecutorConfig.AUTO_MARKET_GENERATION_TASK_EXECUTOR) Executor autoMarketGenerationTaskExecutor
    ) {
        this.autoMarketReader = autoMarketReader;
        this.autoMarketDailyRegimeService = autoMarketDailyRegimeService;
        this.autoParticipantOrderService = autoParticipantOrderService;
        this.autoParticipantOrderScheduleService = autoParticipantOrderScheduleService;
        this.autoProfileBehaviorSupport = autoProfileBehaviorSupport;
        this.simulationClockService = simulationClockService;
        this.simulationMarketSessionService = simulationMarketSessionService;
        this.transactionTemplate = transactionTemplate;
        this.autoMarketProfileLock = autoMarketProfileLock;
        this.readyProfileQueue = readyProfileQueue;
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
        configs = autoMarketDailyRegimeService.applyDailyRegimes(
                configs,
                clock.simulationDateTime().toLocalDate(),
                clock.simulationDateTime()
        );
        Map<String, AutoMarketConfig> configBySymbol = configs.stream()
                .collect(Collectors.toMap(
                        AutoMarketConfig::symbol,
                        config -> config,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        List<AutoParticipantProfileType> readyProfiles = claimReadyProfiles(clock.simulationDateTime());
        if (readyProfiles.isEmpty()) {
            return 0;
        }
        Map<AutoParticipantProfileType, List<AutoParticipantSymbolStrategy>> candidatesByProfile = findDueCandidatesByProfile(
                readyProfiles,
                configs,
                configBySymbol,
                clock.simulationDateTime()
        );
        requeueProfilesWithoutCandidates(readyProfiles, candidatesByProfile, clock.simulationDateTime());
        int activeParticipantCount = activeParticipantCount(candidatesByProfile);
        Map<String, BigDecimal> momentumReferencePricesBySymbol = activeParticipantCount == 0
                ? Map.of()
                : autoMarketReader.findLatestPricesAtOrBefore(
                        configs.stream()
                                .map(AutoMarketConfig::symbol)
                                .toList(),
                        clock.simulationDateTime().minus(PROJECT_SHORT_MOMENTUM_WINDOW)
                );
        List<ProfileGenerationWork> works = profileGenerationWorks(
                candidatesByProfile,
                configBySymbol,
                momentumReferencePricesBySymbol
        );
        totalCount.add(runProfileGenerationWorks(works, profilePolicies, clock.simulationDateTime()));
        log.info(
                "Auto market step completed: symbols={}, participants={}, profileShards={}, symbolShards={}, scheduledStrategies={}, dueStrategies={}, generatedOrders={}, reservedBuyOrders={}, reservedSellOrders={}, failedReserveOrders={}, generationChunks={}, processedCount={}, elapsedMs={}",
                configs.size(),
                activeParticipantCount,
                totalCount.profileShards,
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

    private int activeParticipantCount(Map<AutoParticipantProfileType, List<AutoParticipantSymbolStrategy>> candidatesByProfile) {
        return (int) candidatesByProfile.values()
                .stream()
                .flatMap(List::stream)
                .map(AutoParticipantSymbolStrategy::strategy)
                .map(AutoParticipantStrategy::userKey)
                .distinct()
                .count();
    }

    private int generationDueParticipantLimit(int symbolCount) {
        return Math.max(1, generationDueLimitPerSymbol) * Math.max(1, symbolCount);
    }

    private List<AutoParticipantProfileType> claimReadyProfiles(LocalDateTime now) {
        int workerCount = Math.max(1, generationProfileWorkerCount);
        List<AutoParticipantProfileType> profiles = new ArrayList<>();
        while (profiles.size() < workerCount) {
            int previousSize = profiles.size();
            AutoParticipantProfileType claimedProfile = readyProfileQueue.claimDueProfile(now)
                    .orElse(null);
            if (claimedProfile != null) {
                if (!addDistinctProfile(profiles, claimedProfile)) {
                    break;
                }
                continue;
            }
            if (profiles.size() == previousSize) {
                break;
            }
        }
        return List.copyOf(profiles);
    }

    private void requeueProfilesWithoutCandidates(
            List<AutoParticipantProfileType> claimedProfiles,
            Map<AutoParticipantProfileType, List<AutoParticipantSymbolStrategy>> candidatesByProfile,
            LocalDateTime now
    ) {
        List<AutoParticipantProfileType> emptyProfiles = claimedProfiles.stream()
                .filter(profileType -> !candidatesByProfile.containsKey(profileType))
                .toList();
        if (!emptyProfiles.isEmpty()) {
            requeueProfiles(emptyProfiles, now.plusSeconds(1));
        }
    }

    private boolean addDistinctProfile(List<AutoParticipantProfileType> profiles, AutoParticipantProfileType profileType) {
        if (profileType == null || profiles.contains(profileType)) {
            return false;
        }
        profiles.add(profileType);
        return true;
    }

    private Map<AutoParticipantProfileType, List<AutoParticipantSymbolStrategy>> findDueCandidatesByProfile(
            List<AutoParticipantProfileType> readyProfiles,
            List<AutoMarketConfig> configs,
            Map<String, AutoMarketConfig> configBySymbol,
            LocalDateTime now
    ) {
        Map<AutoParticipantProfileType, List<AutoParticipantSymbolStrategy>> candidatesByProfile = new LinkedHashMap<>();
        int participantLimit = generationDueParticipantLimit(configs.size());
        for (AutoParticipantProfileType profileType : readyProfiles) {
            List<AutoParticipantSymbolStrategy> candidates = autoMarketReader.findDueParticipantSymbolStrategies(
                    configs,
                    profileType,
                    now,
                    participantLimit
            );
            List<AutoParticipantSymbolStrategy> scopedCandidates = candidatesByProfile(candidates, configBySymbol)
                    .getOrDefault(profileType, List.of());
            if (!scopedCandidates.isEmpty()) {
                candidatesByProfile.put(profileType, scopedCandidates);
            }
        }
        return candidatesByProfile;
    }

    private Map<AutoParticipantProfileType, List<AutoParticipantSymbolStrategy>> candidatesByProfile(
            List<AutoParticipantSymbolStrategy> candidates,
            Map<String, AutoMarketConfig> configBySymbol
    ) {
        Map<AutoParticipantProfileType, List<AutoParticipantSymbolStrategy>> candidatesByProfile = new LinkedHashMap<>();
        for (AutoParticipantSymbolStrategy candidate : candidates) {
            AutoParticipantStrategy strategy = candidate.strategy();
            if (strategy.userKey() == null || strategy.userKey().isBlank()) {
                continue;
            }
            if (!configBySymbol.containsKey(candidate.symbol())) {
                continue;
            }
            candidatesByProfile
                    .computeIfAbsent(strategy.profileType(), ignored -> new ArrayList<>())
                    .add(candidate);
        }
        return candidatesByProfile.entrySet()
                .stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> List.copyOf(entry.getValue()),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private Map<String, List<AutoParticipantStrategy>> selectedStrategiesBySymbol(
            List<AutoParticipantSymbolStrategy> candidates,
            Map<String, AutoMarketConfig> configBySymbol,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now
    ) {
        Map<AutoParticipantProfileType, List<AutoParticipantSymbolStrategy>> candidatesByProfile = candidatesByProfile(candidates, configBySymbol);
        Map<String, List<AutoParticipantStrategy>> strategiesBySymbol = new LinkedHashMap<>();
        for (Map.Entry<AutoParticipantProfileType, List<AutoParticipantSymbolStrategy>> entry : candidatesByProfile.entrySet()) {
            List<AutoParticipantSymbolStrategy> selectedCandidates = selectProfileSymbols(
                    entry.getKey(),
                    entry.getValue(),
                    configBySymbol,
                    profilePolicies,
                    now
            );
            for (AutoParticipantSymbolStrategy selected : selectedCandidates) {
                strategiesBySymbol
                        .computeIfAbsent(selected.symbol(), ignored -> new ArrayList<>())
                        .add(selected.strategy());
            }
        }
        return strategiesBySymbol.entrySet()
                .stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> List.copyOf(entry.getValue()),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private List<AutoParticipantSymbolStrategy> selectProfileSymbols(
            AutoParticipantProfileType profileType,
            List<AutoParticipantSymbolStrategy> candidates,
            Map<String, AutoMarketConfig> configBySymbol,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now
    ) {
        Map<String, List<AutoParticipantSymbolStrategy>> candidatesByUserKey = new LinkedHashMap<>();
        for (AutoParticipantSymbolStrategy candidate : candidates) {
            AutoParticipantStrategy strategy = candidate.strategy();
            if (strategy.userKey() == null || strategy.userKey().isBlank()) {
                continue;
            }
            AutoMarketConfig config = configBySymbol.get(candidate.symbol());
            if (config == null) {
                continue;
            }
            candidatesByUserKey
                    .computeIfAbsent(strategy.userKey(), ignored -> new ArrayList<>())
                    .add(candidate);
        }
        if (candidatesByUserKey.isEmpty()) {
            return List.of();
        }

        Map<String, Double> targetCountsBySymbol = targetCountsBySymbol(
                profileType,
                candidates,
                configBySymbol,
                profilePolicies,
                now,
                candidatesByUserKey.size()
        );
        Map<String, Integer> selectedCountsBySymbol = new LinkedHashMap<>();
        List<AutoParticipantSymbolStrategy> selected = new ArrayList<>();
        for (Map.Entry<String, List<AutoParticipantSymbolStrategy>> entry : candidatesByUserKey.entrySet()) {
            AutoParticipantSymbolStrategy best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (AutoParticipantSymbolStrategy candidate : entry.getValue()) {
                AutoMarketConfig config = configBySymbol.get(candidate.symbol());
                double targetCount = Math.max(1.0, targetCountsBySymbol.getOrDefault(candidate.symbol(), 1.0));
                int selectedCount = selectedCountsBySymbol.getOrDefault(candidate.symbol(), 0);
                double diversityPenalty = selectedCount / targetCount * Math.max(0.0, symbolSelectionDiversityPenalty);
                double adjustedScore = symbolSelectionScore(candidate, config, profilePolicies, now)
                        + participantSymbolAffinity(entry.getKey(), candidate.symbol())
                        - diversityPenalty;
                if (best == null || adjustedScore > bestScore) {
                    best = candidate;
                    bestScore = adjustedScore;
                }
            }
            if (best == null) {
                continue;
            }
            selected.add(best);
            selectedCountsBySymbol.merge(best.symbol(), 1, Integer::sum);
        }
        return List.copyOf(selected);
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
        double profileTendency = profileSymbolTendency(strategy.profileType(), momentum, reportScore);
        return overdueScore * 2.0
                + priorityScore
                + intensityScore * 0.65
                + profileSignal
                + profileTendency;
    }

    private Map<String, Double> targetCountsBySymbol(
            AutoParticipantProfileType profileType,
            List<AutoParticipantSymbolStrategy> candidates,
            Map<String, AutoMarketConfig> configBySymbol,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now,
            int participantCount
    ) {
        Map<String, List<Double>> scoresBySymbol = new LinkedHashMap<>();
        for (AutoParticipantSymbolStrategy candidate : candidates) {
            AutoMarketConfig config = configBySymbol.get(candidate.symbol());
            if (config == null) {
                continue;
            }
            double score = symbolSelectionScore(candidate, config, profilePolicies, now);
            scoresBySymbol.computeIfAbsent(candidate.symbol(), ignored -> new ArrayList<>()).add(score);
        }
        if (scoresBySymbol.isEmpty()) {
            return Map.of();
        }
        double maxAverageScore = scoresBySymbol.values()
                .stream()
                .mapToDouble(this::average)
                .max()
                .orElse(0);
        Map<String, Double> rawWeightsBySymbol = new LinkedHashMap<>();
        for (Map.Entry<String, List<Double>> entry : scoresBySymbol.entrySet()) {
            double normalizedScore = Math.clamp(average(entry.getValue()) - maxAverageScore, -4.0, 0.0);
            rawWeightsBySymbol.put(entry.getKey(), Math.exp(normalizedScore));
        }
        double rawTotalWeight = rawWeightsBySymbol.values()
                .stream()
                .mapToDouble(Double::doubleValue)
                .sum();
        if (rawTotalWeight <= 0) {
            return Map.of();
        }
        double maxShare = Math.clamp(symbolSelectionMaxSharePerProfile, 0.25, 1.0);
        Map<String, Double> cappedSharesBySymbol = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : rawWeightsBySymbol.entrySet()) {
            cappedSharesBySymbol.put(entry.getKey(), Math.min(maxShare, entry.getValue() / rawTotalWeight));
        }
        double cappedTotalShare = cappedSharesBySymbol.values()
                .stream()
                .mapToDouble(Double::doubleValue)
                .sum();
        if (cappedTotalShare <= 0) {
            return Map.of();
        }
        Map<String, Double> targetCountsBySymbol = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : cappedSharesBySymbol.entrySet()) {
            double normalizedShare = entry.getValue() / cappedTotalShare;
            targetCountsBySymbol.put(entry.getKey(), Math.max(1.0, participantCount * normalizedShare));
        }
        return targetCountsBySymbol;
    }

    private double average(List<Double> values) {
        if (values.isEmpty()) {
            return 0;
        }
        return values.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0);
    }

    private double participantSymbolAffinity(String userKey, String symbol) {
        double normalized = Math.floorMod((userKey + "\n" + symbol).hashCode(), 10_000) / 10_000.0;
        return (normalized - 0.5) * Math.max(0.0, symbolSelectionParticipantAffinityWeight);
    }

    private double profileSymbolTendency(
            AutoParticipantProfileType profileType,
            double momentum,
            double reportScore
    ) {
        double positiveMomentum = Math.max(0, momentum);
        double negativeMomentum = Math.max(0, -momentum);
        double quietMarket = 1.0 - Math.min(1.0, Math.abs(momentum));
        return switch (profileType) {
            case NEWS_REACTIVE -> reportScore * 0.35;
            case MOMENTUM_FOLLOWER, FOMO_BUYER, HERD_FOLLOWER, OVERCONFIDENT ->
                    positiveMomentum * 0.55 - negativeMomentum * 0.12;
            case CONTRARIAN, DIP_BUYER, AVERAGE_DOWN_BUYER, VALUE_ANCHOR, LIMIT_DOWN_TRAPPED ->
                    negativeMomentum * 0.55 - positiveMomentum * 0.10;
            case MARKET_MAKER, LIQUIDITY_AVOIDANT, CASH_DEFENSIVE, OBSERVER ->
                    quietMarket * 0.35;
            case SCALPER, DAY_TRADER, SWING_TRADER ->
                    Math.abs(momentum) * 0.25 + positiveMomentum * 0.10;
            case PANIC_SELLER, STOP_LOSS_TRADER ->
                    negativeMomentum * 0.35;
            case PROFIT_LOCKER ->
                    positiveMomentum * 0.30;
            case LONG_TERM_HOLDER, PAYDAY_ACCUMULATOR, DIVIDEND_REINVESTOR, LOSS_AVERSE ->
                    quietMarket * 0.20 + negativeMomentum * 0.12;
            case SMALL_DIVERSIFIER ->
                    quietMarket * 0.15;
            case WHALE ->
                    Math.abs(momentum) * 0.15;
            case NOISE_TRADER ->
                    0.0;
        };
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

    private List<ProfileGenerationWork> profileGenerationWorks(
            Map<AutoParticipantProfileType, List<AutoParticipantSymbolStrategy>> candidatesByProfile,
            Map<String, AutoMarketConfig> configBySymbol,
            Map<String, BigDecimal> momentumReferencePricesBySymbol
    ) {
        List<ProfileGenerationWork> works = new ArrayList<>();
        for (Map.Entry<AutoParticipantProfileType, List<AutoParticipantSymbolStrategy>> entry : candidatesByProfile.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            works.add(new ProfileGenerationWork(
                    entry.getKey(),
                    entry.getValue(),
                    configBySymbol,
                    momentumReferencePricesBySymbol
            ));
        }
        return works;
    }

    private AutoMarketRunCount runProfileGenerationWorks(
            List<ProfileGenerationWork> works,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now
    ) {
        if (works.isEmpty()) {
            return new AutoMarketRunCount();
        }
        List<CompletableFuture<AutoMarketRunCount>> futures = works.stream()
                .map(work -> CompletableFuture.supplyAsync(
                        () -> runProfileGenerationWork(work, profilePolicies, now),
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

    private AutoMarketRunCount runProfileGenerationWork(
            ProfileGenerationWork work,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now
    ) {
        return autoMarketProfileLock.tryLock(work.profileType())
                .map(lock -> {
                    try (lock) {
                        return runLockedProfileGenerationWork(work, profilePolicies, now);
                    } finally {
                        requeueProfiles(List.of(work.profileType()), now.plusSeconds(1));
                    }
                })
                .orElseGet(() -> {
                    readyProfileQueue.enqueue(work.profileType(), now.plusSeconds(1));
                    return new AutoMarketRunCount();
                });
    }

    private void requeueProfiles(List<AutoParticipantProfileType> profileTypes, LocalDateTime fallbackReadyAt) {
        try {
            readyProfileQueue.enqueueAll(autoParticipantOrderScheduleService.findNextProfileSchedules(profileTypes, fallbackReadyAt));
        } catch (RuntimeException ex) {
            log.warn("Auto market ready profile requeue failed: profileTypes={}, reason={}", profileTypes, ex.getMessage());
        }
    }

    private AutoMarketRunCount runLockedProfileGenerationWork(
            ProfileGenerationWork work,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now
    ) {
        long startedNanos = System.nanoTime();
        AutoMarketRunCount count = runProfileGeneration(work, profilePolicies, now);
        count.profileShards++;
        logSlowProfileShard(work.profileType(), count, startedNanos);
        return count;
    }

    private AutoMarketRunCount runProfileGeneration(
            ProfileGenerationWork work,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now
    ) {
        AutoMarketRunCount count = new AutoMarketRunCount();
        List<AutoParticipantStrategy> profileStrategies = uniqueParticipantStrategies(work.candidates());
        count.scheduledStrategies += profileStrategies.size();
        List<AutoParticipantStrategy> dueStrategies = runInTransactionWithDeadlockRetry(work.profileType().name(), () -> autoParticipantOrderScheduleService.claimDueStrategies(
                profileStrategies,
                profilePolicies,
                now,
                false
        ));
        count.dueStrategies += dueStrategies.size();
        if (dueStrategies.isEmpty()) {
            return count;
        }

        Set<String> dueUserKeys = dueStrategies.stream()
                .map(AutoParticipantStrategy::userKey)
                .collect(Collectors.toSet());
        List<AutoParticipantSymbolStrategy> dueCandidates = work.candidates()
                .stream()
                .filter(candidate -> dueUserKeys.contains(candidate.strategy().userKey()))
                .toList();
        Map<String, List<AutoParticipantStrategy>> strategiesBySymbol = selectedStrategiesBySymbol(
                dueCandidates,
                work.configBySymbol(),
                profilePolicies,
                now
        );
        for (Map.Entry<String, List<AutoParticipantStrategy>> entry : strategiesBySymbol.entrySet()) {
            AutoMarketConfig config = work.configBySymbol().get(entry.getKey());
            if (config == null) {
                continue;
            }
            count.add(runProfileSymbolGeneration(
                    work.profileType(),
                    config,
                    entry.getValue(),
                    work.momentumReferencePricesBySymbol().get(config.symbol()),
                    profilePolicies,
                    now
            ));
        }
        return count;
    }

    private List<AutoParticipantStrategy> uniqueParticipantStrategies(List<AutoParticipantSymbolStrategy> candidates) {
        Map<String, AutoParticipantStrategy> strategiesByUserKey = new LinkedHashMap<>();
        for (AutoParticipantSymbolStrategy candidate : candidates) {
            AutoParticipantStrategy strategy = candidate.strategy();
            if (strategy.userKey() == null || strategy.userKey().isBlank()) {
                continue;
            }
            strategiesByUserKey.putIfAbsent(strategy.userKey(), strategy);
        }
        return List.copyOf(strategiesByUserKey.values());
    }

    private AutoMarketRunCount runProfileSymbolGeneration(
            AutoParticipantProfileType profileType,
            AutoMarketConfig config,
            List<AutoParticipantStrategy> dueStrategies,
            BigDecimal momentumReferencePrice,
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            LocalDateTime now
    ) {
        AutoMarketRunCount count = new AutoMarketRunCount();
        if (dueStrategies.isEmpty()) {
            return count;
        }
        double momentumPressure = priceMomentum(config, momentumReferencePrice);
        int chunkSize = Math.max(1, generationParticipantChunkSize);
        for (int start = 0; start < dueStrategies.size(); start += chunkSize) {
            int end = Math.min(dueStrategies.size(), start + chunkSize);
            List<AutoParticipantStrategy> chunk = dueStrategies.subList(start, end);
            AutoParticipantOrderGenerationResult generationResult = runInTransactionWithDeadlockRetry(profileType.name() + ":" + config.symbol(), () -> {
                AutoParticipantOrderGenerationResult result = autoParticipantOrderService.placeAutoOrders(
                        chunk,
                        config,
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
        count.symbolShards++;
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

    private void sleepBeforeRetry(String label, int attempt, CannotAcquireLockException ex) {
        long backoffMillis = Math.max(0, deadlockRetryBackoffMs) * attempt;
        log.warn(
                "Auto market generation deadlock retry: shard={}, attempt={}, backoffMs={}, reason={}",
                label,
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

    private void logSlowProfileShard(AutoParticipantProfileType profileType, AutoMarketRunCount count, long startedNanos) {
        long elapsedMillis = elapsedMillis(startedNanos);
        if (elapsedMillis < Math.max(0, slowSymbolLogThresholdMs)) {
            return;
        }
        log.info(
                "Auto market profile generation shard completed: profileType={}, symbolShards={}, scheduledStrategies={}, dueStrategies={}, generatedOrders={}, reservedBuyOrders={}, reservedSellOrders={}, failedReserveOrders={}, generationChunks={}, processedCount={}, elapsedMs={}",
                profileType,
                count.symbolShards,
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
            profileShards += other.profileShards;
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

    private record ProfileGenerationWork(
            AutoParticipantProfileType profileType,
            List<AutoParticipantSymbolStrategy> candidates,
            Map<String, AutoMarketConfig> configBySymbol,
            Map<String, BigDecimal> momentumReferencePricesBySymbol
    ) {
    }

}
