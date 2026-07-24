package stock.batch.service.automarket.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import stock.batch.service.automarket.profile.MarketSignalSnapshot;
import stock.batch.service.automarket.profile.MarketMakerBehavior;
import stock.batch.service.automarket.profile.ParticipantPortfolioSnapshot;
import stock.batch.service.automarket.profile.ProfileDecision;
import stock.batch.service.automarket.profile.ProfileDecisionAction;
import stock.batch.service.automarket.profile.ProfileDecisionReason;
import stock.batch.service.automarket.profile.ProfileExecutionPolicy;
import stock.batch.service.automarket.profile.ProfileExitMode;
import stock.batch.service.automarket.profile.ProfileInventoryMode;
import stock.batch.service.automarket.profile.ProfilePricingMode;
import stock.batch.service.automarket.profile.ProfileSignalContext;
import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;

class AutoMarketExecutionStylePlannerTest {

    private final AutoMarketExecutionStylePlanner planner = new AutoMarketExecutionStylePlanner();

    @Test
    void intentFor_holdDecision_rejectsExecutionBoundaryViolation() {
        ProfileDecision decision = ProfileDecision.hold(
                ProfileDecisionReason.SESSION_CLOSE,
                1.0
        );

        assertThatThrownBy(() -> planner.intentFor(
                AutoParticipantProfileType.DAY_TRADER,
                contextWithStockValue("0.00"),
                decision,
                0,
                1
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HOLD decision");
    }

    @Test
    void intentFor_balancedMarketMaker_alternatesPairedSidesAndSkewsTowardInventoryReduction() {
        ProfileSignalContext context = contextWithStockValue("600.00");
        ProfileDecision decision = new ProfileDecision(
                ProfileDecisionAction.BUY,
                ProfileDecisionReason.INVENTORY_BALANCED,
                4,
                1.0
        );

        AutoMarketExecutionIntent buy = planner.intentFor(
                AutoParticipantProfileType.MARKET_MAKER,
                context,
                decision,
                0,
                4
        );
        AutoMarketExecutionIntent sell = planner.intentFor(
                AutoParticipantProfileType.MARKET_MAKER,
                context,
                decision,
                1,
                4
        );

        assertThat(buy.action()).isEqualTo(ProfileDecisionAction.BUY);
        assertThat(sell.action()).isEqualTo(ProfileDecisionAction.SELL);
        assertThat(buy.quantityMultiplier()).isLessThan(sell.quantityMultiplier());
        assertThat(buy.inventorySkewTicks()).isNegative();
        assertThat(sell.inventorySkewTicks()).isEqualTo(buy.inventorySkewTicks());
    }

    @Test
    void intentFor_profitLockerExit_usesPartialExitQuantity() {
        ProfileDecision decision = new ProfileDecision(
                ProfileDecisionAction.SELL,
                ProfileDecisionReason.EXIT_THRESHOLD,
                1,
                0.5
        );

        AutoMarketExecutionIntent intent = planner.intentFor(
                AutoParticipantProfileType.PROFIT_LOCKER,
                contextWithStockValue("500.00"),
                decision,
                0,
                1
        );

        assertThat(intent.action()).isEqualTo(ProfileDecisionAction.SELL);
        assertThat(intent.requestedQuantity()).isEqualTo(2L);
    }

    @Test
    void intentFor_stopLossAndScalperExit_requestEntireAvailablePosition() {
        ProfileDecision decision = new ProfileDecision(
                ProfileDecisionAction.SELL,
                ProfileDecisionReason.EXIT_THRESHOLD,
                1,
                1.0
        );
        ProfileSignalContext context = contextWithStockValue("500.00");

        AutoMarketExecutionIntent stopLoss = planner.intentFor(
                AutoParticipantProfileType.STOP_LOSS_TRADER,
                context,
                decision,
                0,
                1
        );
        AutoMarketExecutionIntent scalper = planner.intentFor(
                AutoParticipantProfileType.SCALPER,
                context,
                decision,
                0,
                1
        );

        assertThat(stopLoss.requestedQuantity()).isEqualTo(5L);
        assertThat(scalper.requestedQuantity()).isEqualTo(5L);
    }

    @Test
    void intentFor_sessionClose_splitsCurrentPositionAcrossRemainingOrders() {
        ProfileDecision decision = new ProfileDecision(
                ProfileDecisionAction.SELL,
                ProfileDecisionReason.SESSION_CLOSE,
                4,
                1.0
        );

        AutoMarketExecutionIntent intent = planner.intentFor(
                AutoParticipantProfileType.DAY_TRADER,
                contextWithStockValue("1000.00"),
                decision,
                0,
                4
        );

        assertThat(intent.requestedQuantity()).isEqualTo(3L);
    }

    @Test
    void intentFor_marketMakerWithSignalDrivenInventory_keepsDirectionalIntent() {
        ProfileSignalContext base = contextWithStockValue("500.00");
        ProfileSignalContext context = new ProfileSignalContext(
                base.strategy(),
                base.config(),
                base.policy().withExecutionPolicy(new ProfileExecutionPolicy(
                        1.0,
                        1.0,
                        ProfilePricingMode.DIRECTIONAL,
                        ProfileExitMode.SIGNAL_DRIVEN,
                        ProfileInventoryMode.SIGNAL_DRIVEN
                )),
                base.activityLevel(),
                base.momentumPressure(),
                base.herdPressure(),
                base.unrealizedReturn(),
                base.availableQuantity(),
                base.cashBalance(),
                base.recentDividendCashAmount(),
                base.atLowerPriceLimit(),
                base.orderIndex(),
                base.noise(),
                base.portfolio(),
                base.marketSignals(),
                base.fundingBudgets(),
                base.behavioralMemory()
        );
        ProfileDecision decision = new ProfileDecision(
                ProfileDecisionAction.SELL,
                ProfileDecisionReason.SIGNAL,
                1,
                0.7
        );

        AutoMarketExecutionIntent intent = planner.intentFor(
                AutoParticipantProfileType.MARKET_MAKER,
                context,
                decision,
                0,
                1
        );

        assertThat(intent).isEqualTo(AutoMarketExecutionIntent.directional(ProfileDecisionAction.SELL));
    }

    @Test
    void intentFor_marketMakerWithDirectionalPricing_doesNotCreatePairedMarketMakingIntent() {
        ProfileSignalContext base = contextWithStockValue("500.00");
        ProfileSignalContext context = withExecutionPolicy(base, new ProfileExecutionPolicy(
                1.0,
                1.0,
                ProfilePricingMode.DIRECTIONAL,
                ProfileExitMode.SIGNAL_DRIVEN,
                ProfileInventoryMode.TARGET_ALLOCATION
        ));
        ProfileDecision decision = new ProfileDecision(
                ProfileDecisionAction.BUY,
                ProfileDecisionReason.INVENTORY_BALANCED,
                2,
                0.0
        );

        AutoMarketExecutionIntent intent = planner.intentFor(
                AutoParticipantProfileType.MARKET_MAKER,
                context,
                decision,
                1,
                2
        );

        assertThat(intent).isEqualTo(AutoMarketExecutionIntent.directional(ProfileDecisionAction.BUY));
    }

    private ProfileSignalContext withExecutionPolicy(
            ProfileSignalContext base,
            ProfileExecutionPolicy executionPolicy
    ) {
        return new ProfileSignalContext(
                base.strategy(),
                base.config(),
                base.policy().withExecutionPolicy(executionPolicy),
                base.activityLevel(),
                base.momentumPressure(),
                base.herdPressure(),
                base.unrealizedReturn(),
                base.availableQuantity(),
                base.cashBalance(),
                base.recentDividendCashAmount(),
                base.atLowerPriceLimit(),
                base.orderIndex(),
                base.noise(),
                base.portfolio(),
                base.marketSignals(),
                base.fundingBudgets(),
                base.behavioralMemory()
        );
    }

    private ProfileSignalContext contextWithStockValue(String stockValue) {
        MarketMakerBehavior behavior = new MarketMakerBehavior();
        AutoMarketConfig config = new AutoMarketConfig(
                "STOCK001",
                100,
                1200,
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
                AutoParticipantProfileType.MARKET_MAKER
        );
        long holdingQuantity = new BigDecimal(stockValue)
                .divide(config.currentPrice())
                .longValueExact();
        return new ProfileSignalContext(
                strategy,
                config,
                behavior.defaultPolicy(),
                5,
                0,
                0,
                0,
                holdingQuantity,
                new BigDecimal("400.00"),
                BigDecimal.ZERO,
                false,
                0,
                0,
                new ParticipantPortfolioSnapshot(
                        holdingQuantity,
                        0,
                        BigDecimal.ZERO,
                        new BigDecimal(stockValue),
                        new BigDecimal("1000.00"),
                        1
                ),
                MarketSignalSnapshot.EMPTY
        );
    }
}
