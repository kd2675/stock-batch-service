package stock.batch.service.automarket.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static stock.batch.service.automarket.support.AutoMarketRandomSupport.withSeed;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantBehaviorModelVersion;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;

class AutoProfileV2ContractTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2027, 1, 18);
    private static final BigDecimal PRICE = new BigDecimal("100.00");

    private final AutoProfileBehaviorRegistry registry = AutoProfileBehaviorRegistry.createDefault();

    @Test
    void registry_defaultPolicies_coverEveryProfileExactlyOnce() {
        assertThat(registry.defaultPolicies().keySet())
                .containsExactlyInAnyOrder(AutoParticipantProfileType.values());
    }

    @Test
    void decide_sameSeedAndSnapshot_replaysEveryProfileDecision() {
        Map<AutoParticipantProfileType, ProfileDecision> first = decisionsForAllProfiles(123456789L);
        Map<AutoParticipantProfileType, ProfileDecision> second = decisionsForAllProfiles(123456789L);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void decide_everyProfile_returnsBoundedDecision() {
        assertThat(decisionsForAllProfiles(17L).values()).allSatisfy(decision -> {
            assertThat(decision.action()).isNotNull();
            assertThat(decision.reason()).isNotNull();
            assertThat(decision.desiredOrderCount()).isBetween(0, 8);
            assertThat(decision.signalStrength()).isBetween(0.0, 1.0);
        });
    }

    @Test
    void marketMaker_balancedInventory_requestsPairedQuotes() {
        AutoProfileBehavior behavior = registry.behavior(AutoParticipantProfileType.MARKET_MAKER);

        ProfileDecision decision = behavior.decide(context(
                behavior,
                5_000L,
                new BigDecimal("500000.00"),
                0.0,
                neutralSignals(),
                FundingBudgetSnapshot.EMPTY,
                BehavioralMemory.EMPTY,
                1,
                0L
        ));

        assertThat(decision)
                .extracting(ProfileDecision::reason, ProfileDecision::desiredOrderCount)
                .satisfies(values -> {
                    assertThat(values.get(0)).isEqualTo(ProfileDecisionReason.INVENTORY_BALANCED);
                    assertThat((Integer) values.get(1)).isEven().isGreaterThanOrEqualTo(2);
                });
    }

    @Test
    void marketMaker_multipleEligibleSymbols_splitsPortfolioInventoryTargetAcrossUniverse() {
        AutoProfileBehavior behavior = registry.behavior(AutoParticipantProfileType.MARKET_MAKER);

        ProfileDecision decision = behavior.decide(context(
                behavior,
                2_500L,
                new BigDecimal("750000.00"),
                0.0,
                neutralSignals(),
                FundingBudgetSnapshot.EMPTY,
                BehavioralMemory.EMPTY,
                2,
                0L,
                2
        ));

        assertThat(decision.reason()).isEqualTo(ProfileDecisionReason.INVENTORY_BALANCED);
    }

    @Test
    void dayTrader_finalHour_liquidatesPosition() {
        AutoProfileBehavior behavior = registry.behavior(AutoParticipantProfileType.DAY_TRADER);

        ProfileDecision decision = behavior.decide(context(
                behavior,
                12_000L,
                BigDecimal.ZERO,
                0.0,
                signals(0.0, 0.0, 0.0, 0.0, 2_400L, 1.0, 10_000L, 10_000L),
                FundingBudgetSnapshot.EMPTY,
                BehavioralMemory.EMPTY,
                1,
                0L
        ));

        assertThat(decision.action()).isEqualTo(ProfileDecisionAction.SELL);
    }

    @Test
    void scalper_knownExpiredIntradayPosition_liquidatesPosition() {
        AutoProfileBehavior behavior = registry.behavior(AutoParticipantProfileType.SCALPER);
        BehavioralMemory expiredPosition = new BehavioralMemory(
                BUSINESS_DATE,
                0,
                0,
                null,
                PRICE,
                0,
                0,
                301L,
                true
        );

        ProfileDecision decision = behavior.decide(context(
                behavior,
                1_000L,
                new BigDecimal("900000.00"),
                0.0,
                neutralSignals(),
                FundingBudgetSnapshot.EMPTY,
                expiredPosition,
                1,
                0L
        ));

        assertThat(decision)
                .extracting(ProfileDecision::action, ProfileDecision::reason)
                .containsExactly(ProfileDecisionAction.SELL, ProfileDecisionReason.HOLDING_PERIOD);
    }

    @Test
    void scalper_unknownIntradayPositionAge_doesNotInventTimeoutExit() {
        AutoProfileBehavior behavior = registry.behavior(AutoParticipantProfileType.SCALPER);
        BehavioralMemory unknownPositionAge = new BehavioralMemory(
                BUSINESS_DATE,
                0,
                0,
                null,
                PRICE,
                0,
                0,
                10_000L,
                false
        );

        ProfileDecision decision = behavior.decide(context(
                behavior,
                1_000L,
                new BigDecimal("900000.00"),
                0.0,
                neutralSignals(),
                FundingBudgetSnapshot.EMPTY,
                unknownPositionAge,
                1,
                0L
        ));

        assertThat(decision.action()).isEqualTo(ProfileDecisionAction.HOLD);
    }

    @Test
    void liquidityAvoidant_wideSpread_holds() {
        AutoProfileBehavior behavior = registry.behavior(AutoParticipantProfileType.LIQUIDITY_AVOIDANT);

        ProfileDecision decision = behavior.decide(context(
                behavior,
                1_000L,
                new BigDecimal("900000.00"),
                0.0,
                signals(0.5, 0.5, 0.02, 0.03, 20_000L, 6.0, 50_000L, 50_000L),
                FundingBudgetSnapshot.EMPTY,
                BehavioralMemory.EMPTY,
                1,
                0L
        ));

        assertThat(decision.reason()).isEqualTo(ProfileDecisionReason.LIQUIDITY_FILTER);
    }

    @Test
    void fomoBuyer_knownThinRecentActivity_doesNotChaseUnconfirmedBookPressure() {
        AutoProfileBehavior behavior = registry.behavior(AutoParticipantProfileType.FOMO_BUYER);

        ProfileDecision decision = behavior.decide(context(
                behavior,
                1_000L,
                new BigDecimal("900000.00"),
                0.0,
                signalsWithActivity(0.40, 0.45, 20_000L, 16_000L, 10L, 2, true),
                FundingBudgetSnapshot.EMPTY,
                BehavioralMemory.EMPTY,
                1,
                0L
        ));

        assertThat(decision.action()).isEqualTo(ProfileDecisionAction.HOLD);
    }

    @Test
    void fomoBuyer_broadHighRecentActivity_confirmsMomentum() {
        AutoProfileBehavior behavior = registry.behavior(AutoParticipantProfileType.FOMO_BUYER);

        ProfileDecision decision = behavior.decide(context(
                behavior,
                1_000L,
                new BigDecimal("900000.00"),
                0.0,
                signalsWithActivity(0.40, 0.45, 20_000L, 16_000L, 1_000L, 5, true),
                FundingBudgetSnapshot.EMPTY,
                BehavioralMemory.EMPTY,
                1,
                0L
        ));

        assertThat(decision.action()).isEqualTo(ProfileDecisionAction.BUY);
    }

    @Test
    void crowdProfiles_unknownRecentActivity_holdInsteadOfTreatingUnknownAsConfirmation() {
        MarketSignalSnapshot unknownBullishActivity = signalsWithActivity(
                0.45,
                0.50,
                20_000L,
                10_000L,
                0L,
                0,
                false
        );
        MarketSignalSnapshot unknownBearishActivity = signalsWithActivity(
                -0.45,
                -0.50,
                10_000L,
                20_000L,
                0L,
                0,
                false
        );

        ProfileDecision fomo = registry.behavior(AutoParticipantProfileType.FOMO_BUYER).decide(context(
                registry.behavior(AutoParticipantProfileType.FOMO_BUYER),
                1_000L,
                new BigDecimal("900000.00"),
                0.0,
                unknownBullishActivity,
                FundingBudgetSnapshot.EMPTY,
                BehavioralMemory.EMPTY,
                1,
                0L
        ));
        ProfileDecision herd = registry.behavior(AutoParticipantProfileType.HERD_FOLLOWER).decide(context(
                registry.behavior(AutoParticipantProfileType.HERD_FOLLOWER),
                1_000L,
                new BigDecimal("900000.00"),
                0.0,
                unknownBullishActivity,
                FundingBudgetSnapshot.EMPTY,
                BehavioralMemory.EMPTY,
                1,
                0L
        ));
        ProfileDecision panic = registry.behavior(AutoParticipantProfileType.PANIC_SELLER).decide(context(
                registry.behavior(AutoParticipantProfileType.PANIC_SELLER),
                1_000L,
                new BigDecimal("900000.00"),
                0.0,
                unknownBearishActivity,
                FundingBudgetSnapshot.EMPTY,
                BehavioralMemory.EMPTY,
                1,
                0L
        ));

        assertThat(Map.of(
                "fomo", fomo.action(),
                "herd", herd.action(),
                "panic", panic.action()
        )).containsOnly(
                Map.entry("fomo", ProfileDecisionAction.HOLD),
                Map.entry("herd", ProfileDecisionAction.HOLD),
                Map.entry("panic", ProfileDecisionAction.HOLD)
        );
    }

    @Test
    void overconfident_recentProfitableTradingDaysAndMomentum_increasesRiskWithoutOpenProfit() {
        AutoProfileBehavior behavior = registry.behavior(AutoParticipantProfileType.OVERCONFIDENT);
        MarketSignalSnapshot signals = new MarketSignalSnapshot(
                0.25,
                0.30,
                0.08,
                0.10,
                0.12,
                0.15,
                100_000L,
                100_000L,
                20_000L,
                1.0,
                12_000L,
                10_000L,
                0.0,
                Long.MAX_VALUE
        );

        ProfileDecision decision = behavior.decide(context(
                behavior,
                1_000L,
                new BigDecimal("900000.00"),
                0.0,
                signals,
                FundingBudgetSnapshot.EMPTY,
                new BehavioralMemory(BUSINESS_DATE.minusDays(5), 5, 0, null, PRICE, 4, 5),
                1,
                0L
        ));

        assertThat(decision.action()).isEqualTo(ProfileDecisionAction.BUY);
    }

    @Test
    void paydayAndDividendProfiles_withoutDedicatedBudget_hold() {
        ProfileDecision payday = registry.behavior(AutoParticipantProfileType.PAYDAY_ACCUMULATOR)
                .decide(contextFor(AutoParticipantProfileType.PAYDAY_ACCUMULATOR, FundingBudgetSnapshot.EMPTY));
        ProfileDecision dividend = registry.behavior(AutoParticipantProfileType.DIVIDEND_REINVESTOR)
                .decide(contextFor(AutoParticipantProfileType.DIVIDEND_REINVESTOR, FundingBudgetSnapshot.EMPTY));

        assertThat(Map.of("payday", payday.reason(), "dividend", dividend.reason()))
                .containsEntry("payday", ProfileDecisionReason.FUNDING_BUDGET_EMPTY)
                .containsEntry("dividend", ProfileDecisionReason.DIVIDEND_BUDGET_EMPTY);
    }

    @Test
    void smallDiversifier_highConcentration_reducesPosition() {
        AutoProfileBehavior behavior = registry.behavior(AutoParticipantProfileType.SMALL_DIVERSIFIER);

        ProfileDecision decision = behavior.decide(context(
                behavior,
                3_000L,
                new BigDecimal("100000.00"),
                0.0,
                neutralSignals(),
                FundingBudgetSnapshot.EMPTY,
                BehavioralMemory.EMPTY,
                1,
                0L
        ));

        assertThat(decision.action()).isEqualTo(ProfileDecisionAction.SELL);
    }

    @Test
    void stopLossAndProfitLocker_respectDifferentExitThresholds() {
        AutoProfileBehavior stopLoss = registry.behavior(AutoParticipantProfileType.STOP_LOSS_TRADER);
        AutoProfileBehavior profitLocker = registry.behavior(AutoParticipantProfileType.PROFIT_LOCKER);

        ProfileDecision lossExit = stopLoss.decide(context(
                stopLoss,
                1_000L,
                new BigDecimal("900000.00"),
                -0.06,
                neutralSignals(),
                FundingBudgetSnapshot.EMPTY,
                BehavioralMemory.EMPTY,
                1,
                0L
        ));
        ProfileDecision profitExit = profitLocker.decide(context(
                profitLocker,
                1_000L,
                new BigDecimal("900000.00"),
                0.06,
                neutralSignals(),
                FundingBudgetSnapshot.EMPTY,
                BehavioralMemory.EMPTY,
                1,
                0L
        ));

        assertThat(Map.of("loss", lossExit.action(), "profit", profitExit.action()))
                .containsOnly(
                        Map.entry("loss", ProfileDecisionAction.SELL),
                        Map.entry("profit", ProfileDecisionAction.SELL)
                );
    }

    @Test
    void multiDayProfiles_missingRequiredHistory_holdInsteadOfTreatingZeroAsFlatMarket() {
        MarketSignalSnapshot missingHistory = new MarketSignalSnapshot(
                0.40,
                0.50,
                0.05,
                -0.10,
                -0.12,
                -0.20,
                100_000L,
                100_000L,
                20_000L,
                1.0,
                20_000L,
                10_000L,
                0.0,
                Long.MAX_VALUE,
                0L,
                0,
                false,
                0
        );
        Map<AutoParticipantProfileType, ProfileDecisionReason> reasons = new EnumMap<>(AutoParticipantProfileType.class);
        for (AutoParticipantProfileType type : new AutoParticipantProfileType[]{
                AutoParticipantProfileType.MOMENTUM_FOLLOWER,
                AutoParticipantProfileType.CONTRARIAN,
                AutoParticipantProfileType.VALUE_ANCHOR,
                AutoParticipantProfileType.SWING_TRADER,
                AutoParticipantProfileType.LONG_TERM_HOLDER
        }) {
            AutoProfileBehavior behavior = registry.behavior(type);
            reasons.put(type, behavior.decide(context(
                    behavior,
                    0L,
                    new BigDecimal("900000.00"),
                    0.0,
                    missingHistory,
                    FundingBudgetSnapshot.EMPTY,
                    BehavioralMemory.EMPTY,
                    0,
                    0L
            )).reason());
        }

        assertThat(reasons.values()).containsOnly(ProfileDecisionReason.HISTORICAL_SIGNAL_UNAVAILABLE);
    }

    @Test
    void swingTrader_requiresTenDayTrendConfirmationBeforeEntry() {
        AutoProfileBehavior behavior = registry.behavior(AutoParticipantProfileType.SWING_TRADER);
        MarketSignalSnapshot confirmedTrend = new MarketSignalSnapshot(
                0.10, 0.15, 0.01, 0.08, 0.12, 0.10, 0.08,
                100_000L, 100_000L, 20_000L, 1.0, 20_000L, 10_000L,
                0.0, Long.MAX_VALUE, 0L, 0, false, 21
        );
        MarketSignalSnapshot unconfirmedTrend = new MarketSignalSnapshot(
                0.10, 0.15, 0.01, 0.08, 0.12, -0.02, 0.08,
                100_000L, 100_000L, 20_000L, 1.0, 20_000L, 10_000L,
                0.0, Long.MAX_VALUE, 0L, 0, false, 21
        );

        ProfileDecision confirmed = behavior.decide(context(
                behavior, 0L, new BigDecimal("900000.00"), 0.0, confirmedTrend,
                FundingBudgetSnapshot.EMPTY, BehavioralMemory.EMPTY, 0, 0L
        ));
        ProfileDecision unconfirmed = behavior.decide(context(
                behavior, 0L, new BigDecimal("900000.00"), 0.0, unconfirmedTrend,
                FundingBudgetSnapshot.EMPTY, BehavioralMemory.EMPTY, 0, 0L
        ));

        assertThat(Map.of("confirmed", confirmed.action(), "unconfirmed", unconfirmed.action()))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "confirmed", ProfileDecisionAction.BUY,
                        "unconfirmed", ProfileDecisionAction.HOLD
                ));
    }

    @Test
    void positionManagementProfiles_withoutHolding_keepAnEntryDecisionPath() {
        Map<AutoParticipantProfileType, ProfileDecisionAction> actions = new EnumMap<>(AutoParticipantProfileType.class);
        for (AutoParticipantProfileType type : new AutoParticipantProfileType[]{
                AutoParticipantProfileType.LOSS_AVERSE,
                AutoParticipantProfileType.LIMIT_DOWN_TRAPPED,
                AutoParticipantProfileType.AVERAGE_DOWN_BUYER,
                AutoParticipantProfileType.STOP_LOSS_TRADER,
                AutoParticipantProfileType.PANIC_SELLER,
                AutoParticipantProfileType.PROFIT_LOCKER
        }) {
            AutoProfileBehavior behavior = registry.behavior(type);
            actions.put(type, withSeed(99L, () -> behavior.decide(context(
                    behavior,
                    0L,
                    new BigDecimal("900000.00"),
                    0.0,
                    neutralSignals(),
                    FundingBudgetSnapshot.EMPTY,
                    BehavioralMemory.EMPTY,
                    0,
                    0L
            ))).action());
        }

        assertThat(actions).doesNotContainValue(ProfileDecisionAction.HOLD);
    }

    private Map<AutoParticipantProfileType, ProfileDecision> decisionsForAllProfiles(long seed) {
        Map<AutoParticipantProfileType, ProfileDecision> decisions = new EnumMap<>(AutoParticipantProfileType.class);
        for (AutoParticipantProfileType type : AutoParticipantProfileType.values()) {
            AutoProfileBehavior behavior = registry.behavior(type);
            ProfileSignalContext context = contextFor(type, new FundingBudgetSnapshot(
                    new BigDecimal("100000.00"),
                    new BigDecimal("100000.00")
            ));
            decisions.put(type, withSeed(seed, () -> behavior.decide(context)));
        }
        return decisions;
    }

    private ProfileSignalContext contextFor(
            AutoParticipantProfileType type,
            FundingBudgetSnapshot fundingBudgets
    ) {
        AutoProfileBehavior behavior = registry.behavior(type);
        return context(
                behavior,
                1_000L,
                new BigDecimal("900000.00"),
                0.02,
                signals(0.20, 0.25, 0.02, 0.04, 18_000L, 1.0, 15_000L, 12_000L),
                fundingBudgets,
                new BehavioralMemory(BUSINESS_DATE.minusDays(3), 3, 0, null, PRICE),
                2,
                0L
        );
    }

    private ProfileSignalContext context(
            AutoProfileBehavior behavior,
            long holdingQuantity,
            BigDecimal cash,
            double unrealizedReturn,
            MarketSignalSnapshot signals,
            FundingBudgetSnapshot fundingBudgets,
            BehavioralMemory memory,
            int positionCount,
            long symbolOpenBuyQuantity
    ) {
        return context(
                behavior,
                holdingQuantity,
                cash,
                unrealizedReturn,
                signals,
                fundingBudgets,
                memory,
                positionCount,
                symbolOpenBuyQuantity,
                1
        );
    }

    private ProfileSignalContext context(
            AutoProfileBehavior behavior,
            long holdingQuantity,
            BigDecimal cash,
            double unrealizedReturn,
            MarketSignalSnapshot signals,
            FundingBudgetSnapshot fundingBudgets,
            BehavioralMemory memory,
            int positionCount,
            long symbolOpenBuyQuantity,
            int eligibleSymbolCount
    ) {
        AutoMarketConfig config = new AutoMarketConfig(
                "STOCK001",
                5_000,
                60,
                1_000_000L,
                BigDecimal.ONE,
                PRICE,
                PRICE,
                null
        );
        AutoParticipantStrategy strategy = new AutoParticipantStrategy(
                "auto-" + behavior.type().name().toLowerCase(java.util.Locale.ROOT),
                behavior.type().ordinal() + 1L,
                5,
                behavior.type(),
                null,
                null,
                null,
                AutoParticipantBehaviorModelVersion.V2,
                17L + behavior.type().ordinal(),
                LocalDateTime.of(BUSINESS_DATE, java.time.LocalTime.NOON)
        );
        BigDecimal holdingValue = PRICE.multiply(BigDecimal.valueOf(holdingQuantity));
        BigDecimal liquidAsset = cash.add(holdingValue);
        return new ProfileSignalContext(
                strategy,
                config,
                behavior.defaultPolicy(),
                5,
                signals.momentum1Hour(),
                signals.depthImbalance(),
                unrealizedReturn,
                holdingQuantity,
                cash,
                BigDecimal.ZERO,
                false,
                0,
                0.0,
                new ParticipantPortfolioSnapshot(
                        holdingQuantity,
                        0L,
                        BigDecimal.ZERO,
                        holdingValue,
                        liquidAsset,
                        positionCount,
                        symbolOpenBuyQuantity,
                        eligibleSymbolCount
                ),
                signals,
                fundingBudgets,
                memory
        );
    }

    private MarketSignalSnapshot neutralSignals() {
        return signals(0.0, 0.0, 0.0, 0.0, 20_000L, 1.0, 10_000L, 10_000L);
    }

    private MarketSignalSnapshot signals(
            double momentum5Minute,
            double momentum1Hour,
            double return3Day,
            double return5Day,
            long secondsToClose,
            double spreadTicks,
            long bidDepth,
            long askDepth
    ) {
        return new MarketSignalSnapshot(
                momentum5Minute,
                momentum1Hour,
                0.01,
                return3Day,
                return5Day,
                0.05,
                100_000L,
                100_000L,
                secondsToClose,
                spreadTicks,
                bidDepth,
                askDepth,
                0.5,
                600L
        );
    }

    private MarketSignalSnapshot signalsWithActivity(
            double momentum5Minute,
            double momentum1Hour,
            long bidDepth,
            long askDepth,
            long recentQuantity,
            int recentParticipants,
            boolean available
    ) {
        return new MarketSignalSnapshot(
                momentum5Minute,
                momentum1Hour,
                0.01,
                0.02,
                0.04,
                0.05,
                100_000L,
                100_000L,
                20_000L,
                1.0,
                bidDepth,
                askDepth,
                0.5,
                600L,
                recentQuantity,
                recentParticipants,
                available
        );
    }
}
