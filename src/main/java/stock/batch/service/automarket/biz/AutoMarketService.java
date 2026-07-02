package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.batch.service.automarket.profile.ProfilePolicy;
import stock.batch.service.automarket.profile.ProfileSignalContext;
import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoParticipant;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;
import stock.batch.service.batch.automarket.reader.AutoMarketReader;
import stock.batch.service.batch.automarket.writer.AutoMarketWriter;
import stock.batch.service.simulation.SimulationClockService;
import web.common.core.simulation.SimulationClockSnapshot;

import static stock.batch.service.automarket.biz.AutoMarketPricePolicy.positiveOrDefault;

@Service
@RequiredArgsConstructor
public class AutoMarketService {

    private static final String BUY = "BUY";
    private static final Duration PROJECT_SHORT_MOMENTUM_WINDOW = Duration.ofHours(1);

    private final AutoMarketReader autoMarketReader;
    private final AutoMarketWriter autoMarketWriter;
    private final AutoMarketOrderExpiryService autoMarketOrderExpiryService;
    private final AutoParticipantOrderService autoParticipantOrderService;
    private final ListingAutoAccountOrderService listingAutoAccountOrderService;
    private final AutoProfileBehaviorSupport autoProfileBehaviorSupport;
    private final SimulationClockService simulationClockService;

    @Transactional
    public int runAutoMarketStep() {
        List<AutoParticipant> participants = autoMarketReader.findEnabledParticipants();
        List<AutoMarketConfig> configs = autoMarketReader.findEnabledConfigs();
        if (configs.isEmpty()) {
            return 0;
        }

        int processed = 0;
        Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies = loadProfilePolicies();
        if (!participants.isEmpty()) {
            ensureAccounts(participants);
        }
        Map<String, List<AutoParticipantStrategy>> strategiesBySymbol = participants.isEmpty()
                ? Map.of()
                : autoMarketReader.findEnabledParticipantStrategiesBySymbol(configs);
        SimulationClockSnapshot clock = simulationClockService.currentSnapshot();
        Map<String, BigDecimal> momentumReferencePricesBySymbol = participants.isEmpty()
                ? Map.of()
                : autoMarketReader.findLatestPricesAtOrBefore(
                        configs.stream()
                                .map(AutoMarketConfig::symbol)
                                .toList(),
                        clock.simulationDateTime().minus(PROJECT_SHORT_MOMENTUM_WINDOW)
                );
        for (AutoMarketConfig config : configs) {
            List<AutoParticipantStrategy> strategies = strategiesBySymbol.getOrDefault(config.symbol(), List.of());
            if (strategies.isEmpty()) {
                processed += listingAutoAccountOrderService.run(config);
                continue;
            }
            processed += autoMarketOrderExpiryService.expireOldAutoOrders(config, profilePolicies);
            processed += listingAutoAccountOrderService.run(config);
            processed += autoParticipantOrderService.placeAutoOrders(
                    strategies,
                    config,
                    profilePolicies,
                    priceMomentum(config, momentumReferencePricesBySymbol.get(config.symbol()))
            );
        }
        return processed;
    }

    private void ensureAccounts(List<AutoParticipant> participants) {
        LocalDateTime now = simulationClockService.currentMarketDateTime();
        Set<String> existingAccountUserKeys = new HashSet<>(autoMarketReader.findExistingAccountUserKeys(
                participants.stream()
                        .map(AutoParticipant::userKey)
                        .toList()
        ));
        for (AutoParticipant participant : participants) {
            if (existingAccountUserKeys.contains(participant.userKey())) {
                continue;
            }
            autoMarketWriter.insertAccount(participant, now);
        }
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

    private ProfilePolicy profilePolicy(
            Map<AutoParticipantProfileType, ProfilePolicy> profilePolicies,
            AutoParticipantProfileType profileType
    ) {
        return autoProfileBehaviorSupport.policy(profilePolicies, profileType);
    }

    private Map<AutoParticipantProfileType, ProfilePolicy> loadProfilePolicies() {
        return autoProfileBehaviorSupport.policiesWithOverrides(autoMarketReader.findParticipantProfileConfigs());
    }

}
