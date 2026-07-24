package stock.batch.service.automarket.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantBehaviorModelVersion;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;

class ProfileBehaviorMemoryTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2027, 1, 18);

    @Test
    void averageDown_roundLimitReached_holds() {
        AverageDownBuyerBehavior behavior = new AverageDownBuyerBehavior();
        ProfileSignalContext context = context(
                behavior,
                5,
                new BigDecimal("400.00"),
                -0.10,
                MarketSignalSnapshot.EMPTY,
                new BehavioralMemory(BUSINESS_DATE.minusDays(5), 5, 3, BUSINESS_DATE.minusDays(1), BigDecimal.TEN)
        );

        ProfileDecision decision = behavior.decide(context);

        assertThat(decision.action()).isEqualTo(ProfileDecisionAction.HOLD);
        assertThat(decision.reason()).isEqualTo(ProfileDecisionReason.AVERAGE_DOWN_LIMIT);
    }

    @Test
    void averageDown_alreadyBoughtOnBusinessDate_holdsForCooldown() {
        AverageDownBuyerBehavior behavior = new AverageDownBuyerBehavior();
        ProfileSignalContext context = context(
                behavior,
                1,
                new BigDecimal("900.00"),
                -0.10,
                MarketSignalSnapshot.EMPTY,
                new BehavioralMemory(BUSINESS_DATE.minusDays(5), 5, 1, BUSINESS_DATE, BigDecimal.TEN)
        );

        ProfileDecision decision = behavior.decide(context);

        assertThat(decision.reason()).isEqualTo(ProfileDecisionReason.AVERAGE_DOWN_COOLDOWN);
    }

    @Test
    void swingTrader_beforeMinimumHoldingPeriod_doesNotTakeOrdinaryProfit() {
        SwingTraderBehavior behavior = new SwingTraderBehavior();
        ProfileSignalContext context = context(
                behavior,
                2,
                new BigDecimal("800.00"),
                0.20,
                marketSignals(0.03, 0.05, Long.MAX_VALUE),
                new BehavioralMemory(BUSINESS_DATE, 1, 0, null, BigDecimal.TEN)
        );

        ProfileDecision decision = behavior.decide(context);

        assertThat(decision.action()).isEqualTo(ProfileDecisionAction.HOLD);
        assertThat(decision.reason()).isEqualTo(ProfileDecisionReason.HOLDING_PERIOD);
    }

    @Test
    void swingTrader_afterTenDaysAndWeakTrend_exits() {
        SwingTraderBehavior behavior = new SwingTraderBehavior();
        ProfileSignalContext context = context(
                behavior,
                2,
                new BigDecimal("800.00"),
                0.02,
                marketSignals(-0.01, 0.01, Long.MAX_VALUE),
                new BehavioralMemory(BUSINESS_DATE.minusDays(12), 10, 0, null, BigDecimal.TEN)
        );

        ProfileDecision decision = behavior.decide(context);

        assertThat(decision.action()).isEqualTo(ProfileDecisionAction.SELL);
        assertThat(decision.reason()).isEqualTo(ProfileDecisionReason.MULTI_DAY_SIGNAL);
    }

    @Test
    void longTermHolder_beforeMinimumHoldingPeriod_ignoresNonEmergencyConcentration() {
        LongTermHolderBehavior behavior = new LongTermHolderBehavior();
        ProfileSignalContext context = context(
                behavior,
                5,
                new BigDecimal("500.00"),
                0.10,
                marketSignals(0.02, 0.04, Long.MAX_VALUE),
                new BehavioralMemory(BUSINESS_DATE.minusDays(5), 5, 0, null, BigDecimal.TEN)
        );

        ProfileDecision decision = behavior.decide(context);

        assertThat(decision.action()).isEqualTo(ProfileDecisionAction.HOLD);
        assertThat(decision.reason()).isEqualTo(ProfileDecisionReason.HOLDING_PERIOD);
    }

    @Test
    void scalper_carriedPosition_exitsWithoutWaitingForNewMomentum() {
        ScalperBehavior behavior = new ScalperBehavior();
        ProfileSignalContext context = context(
                behavior,
                12,
                BigDecimal.ZERO,
                0.0,
                marketSignals(0.0, 0.0, 20_000L),
                new BehavioralMemory(BUSINESS_DATE.minusDays(1), 1, 0, null, BigDecimal.TEN)
        );

        ProfileDecision decision = behavior.decide(context);

        assertThat(decision.action()).isEqualTo(ProfileDecisionAction.SELL);
        assertThat(decision.reason()).isEqualTo(ProfileDecisionReason.HOLDING_PERIOD);
        assertThat(decision.desiredOrderCount()).isEqualTo(3);
    }

    @Test
    void dayTrader_closeWindow_sizesEnoughChunksForAvailablePosition() {
        DayTraderBehavior behavior = new DayTraderBehavior();
        ProfileSignalContext context = context(
                behavior,
                12,
                BigDecimal.ZERO,
                0.0,
                marketSignals(0.0, 0.0, 2_400L),
                BehavioralMemory.EMPTY
        );

        ProfileDecision decision = behavior.decide(context);

        assertThat(decision.reason()).isEqualTo(ProfileDecisionReason.SESSION_CLOSE);
        assertThat(decision.desiredOrderCount()).isEqualTo(3);
    }

    private ProfileSignalContext context(
            AutoProfileBehavior behavior,
            long holdingQuantity,
            BigDecimal cash,
            double unrealizedReturn,
            MarketSignalSnapshot marketSignals,
            BehavioralMemory memory
    ) {
        AutoMarketConfig config = new AutoMarketConfig(
                "STOCK001",
                5,
                100,
                1_000_000L,
                BigDecimal.ONE,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                null
        );
        AutoParticipantStrategy strategy = new AutoParticipantStrategy(
                "auto-001",
                1L,
                5,
                behavior.type(),
                null,
                null,
                null,
                AutoParticipantBehaviorModelVersion.V2,
                17L,
                LocalDateTime.of(BUSINESS_DATE, java.time.LocalTime.NOON)
        );
        BigDecimal holdingValue = config.currentPrice().multiply(BigDecimal.valueOf(holdingQuantity));
        BigDecimal liquidAsset = cash.add(holdingValue);
        return new ProfileSignalContext(
                strategy,
                config,
                behavior.defaultPolicy(),
                5,
                marketSignals.momentum1Hour(),
                0,
                unrealizedReturn,
                holdingQuantity,
                cash,
                BigDecimal.ZERO,
                false,
                0,
                0,
                new ParticipantPortfolioSnapshot(
                        holdingQuantity,
                        0,
                        BigDecimal.ZERO,
                        holdingValue,
                        liquidAsset,
                        holdingQuantity > 0 ? 1 : 0
                ),
                marketSignals,
                FundingBudgetSnapshot.EMPTY,
                memory
        );
    }

    private MarketSignalSnapshot marketSignals(double return3Day, double return5Day, long secondsToClose) {
        return new MarketSignalSnapshot(
                0.0,
                0.0,
                0.0,
                return3Day,
                return5Day,
                0.0,
                0L,
                0L,
                secondsToClose,
                1.0,
                100L,
                100L,
                0.0,
                Long.MAX_VALUE
        );
    }
}
