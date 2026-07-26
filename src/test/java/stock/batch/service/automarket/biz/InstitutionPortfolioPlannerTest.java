package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import stock.batch.service.batch.automarket.model.AutoMarketPressure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstitutionPortfolioPlannerTest {

    private final InstitutionPortfolioPlanner planner = new InstitutionPortfolioPlanner();

    @Test
    void plan_projectedPositionAlreadyAtTarget_holdsWithoutNewQuantity() {
        InstitutionDecisionPlan plan = planner.plan(
                policy("0.600000", "0.010000", "0.010000"),
                List.of(mandate("DEMO001", "1.000000", "0.000000", "1.000000", 100_000L)),
                Map.of("DEMO001", market("DEMO001", "100.00", AutoMarketPressure.NEUTRAL)),
                Map.of("DEMO001", new InstitutionPositionSnapshot(5_000L, 0L, 1_000L, 0L)),
                Map.of(),
                Map.of(),
                new BigDecimal("400000.00"),
                new BigDecimal("100000.00"),
                new BigDecimal("500000.00")
        );

        InstitutionDecisionItem item = plan.items().getFirst();
        assertThat(item.actualAllocationRate()).isEqualByComparingTo("0.50000000");
        assertThat(item.projectedAllocationRate()).isEqualByComparingTo("0.60000000");
        assertThat(item.action()).isEqualTo(InstitutionDecisionAction.HOLD);
        assertThat(item.gatedQuantity()).isZero();
        assertThat(item.decisionReason()).isEqualTo("HYSTERESIS_BAND");
    }

    @Test
    void plan_buyDemand_appliesDecisionTurnoverAndReferenceVolumeCaps() {
        InstitutionDecisionPlan plan = planner.plan(
                policy("0.600000", "0.010000", "0.002000"),
                List.of(mandate("DEMO001", "1.000000", "0.000000", "1.000000", 1_000L)),
                Map.of("DEMO001", market("DEMO001", "100.00", AutoMarketPressure.NEUTRAL)),
                Map.of(),
                Map.of(),
                Map.of(),
                new BigDecimal("1000000.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );

        InstitutionDecisionItem item = plan.items().getFirst();
        assertThat(item.action()).isEqualTo(InstitutionDecisionAction.BUY);
        assertThat(item.dailyGrossQuantityLimit()).isEqualTo(20L);
        assertThat(item.gatedQuantity()).isEqualTo(20L);
        assertThat(item.gatedTradeAmount()).isEqualByComparingTo("2000.00");
        assertThat(item.gateReason()).contains("SYMBOL_PARTICIPATION_LIMIT");
    }

    @Test
    void plan_existingDailyBudget_boundsRepeatedShadowSuggestions() {
        InstitutionPortfolioPolicy policy = policy("0.600000", "0.010000", "0.010000");
        InstitutionSymbolMandate mandate = mandate(
                "DEMO001",
                "1.000000",
                "0.000000",
                "1.000000",
                1_000L
        );
        InstitutionDailyBudgetSnapshot exhausted = new InstitutionDailyBudgetSnapshot(
                1_000L,
                20L,
                new BigDecimal("10000.00"),
                20L,
                0L,
                new BigDecimal("2000.00"),
                BigDecimal.ZERO,
                1L,
                2L
        );

        InstitutionDecisionItem item = planner.plan(
                policy,
                List.of(mandate),
                Map.of("DEMO001", market("DEMO001", "100.00", AutoMarketPressure.NEUTRAL)),
                Map.of(),
                Map.of("DEMO001", exhausted),
                Map.of("DEMO001", InstitutionDecisionAction.BUY),
                new BigDecimal("1000000.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO
        ).items().getFirst();

        assertThat(item.action()).isEqualTo(InstitutionDecisionAction.BUY);
        assertThat(item.gatedQuantity()).isZero();
        assertThat(item.remainingDailyQuantityBudget()).isZero();
        assertThat(item.gateReason()).contains("SYMBOL_PARTICIPATION_LIMIT");
    }

    @Test
    void plan_priorShadowPlanReachesTarget_holdsWithoutRepeatingBuy() {
        InstitutionDailyBudgetSnapshot priorPlan = new InstitutionDailyBudgetSnapshot(
                1_000_000L,
                20_000L,
                new BigDecimal("1000000.00"),
                6_000L,
                0L,
                new BigDecimal("600000.00"),
                BigDecimal.ZERO,
                1L,
                1L
        );

        InstitutionDecisionItem item = planner.plan(
                policy("0.600000", "1.000000", "1.000000"),
                List.of(mandate("DEMO001", "1.000000", "0.000000", "1.000000", 1_000_000L)),
                Map.of("DEMO001", market("DEMO001", "100.00", AutoMarketPressure.NEUTRAL)),
                Map.of(),
                Map.of("DEMO001", priorPlan),
                Map.of("DEMO001", InstitutionDecisionAction.BUY),
                new BigDecimal("1000000.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO
        ).items().getFirst();

        assertThat(item.projectedQuantity()).isEqualTo(6_000L);
        assertThat(item.projectedAllocationRate()).isEqualByComparingTo("0.60000000");
        assertThat(item.action()).isEqualTo(InstitutionDecisionAction.HOLD);
        assertThat(item.gatedQuantity()).isZero();
    }

    @Test
    void plan_pilotSell_usesLiveReservationsWithoutSubtractingHistoricalPlansTwice() {
        InstitutionPortfolioPolicy pilotPolicy = new InstitutionPortfolioPolicy(
                1L,
                1L,
                1L,
                "PILOT",
                "Pilot",
                "BALANCED_LONG_TERM",
                "PILOT",
                new BigDecimal("0.200000"),
                new BigDecimal("0.000000"),
                new BigDecimal("1.000000"),
                new BigDecimal("0.700000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("0.005000"),
                new BigDecimal("0.002000"),
                BigDecimal.ONE,
                BigDecimal.ONE,
                60,
                1L
        );
        InstitutionDailyBudgetSnapshot priorSubmittedPlan = new InstitutionDailyBudgetSnapshot(
                100_000L,
                2_000L,
                new BigDecimal("100000.00"),
                0L,
                50L,
                BigDecimal.ZERO,
                new BigDecimal("5000.00"),
                1L,
                1L
        );

        InstitutionDecisionItem item = planner.plan(
                pilotPolicy,
                List.of(mandate("DEMO001", "1.000000", "0.000000", "1.000000", 100_000L)),
                Map.of("DEMO001", market("DEMO001", "100.00", AutoMarketPressure.NEUTRAL)),
                Map.of("DEMO001", new InstitutionPositionSnapshot(100L, 0L, 0L, 0L)),
                Map.of("DEMO001", priorSubmittedPlan),
                Map.of("DEMO001", InstitutionDecisionAction.SELL),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("10000.00")
        ).items().getFirst();

        assertThat(item.action()).isEqualTo(InstitutionDecisionAction.SELL);
        assertThat(item.gatedQuantity()).isEqualTo(80L);
        assertThat(item.gateReason()).doesNotContain("SHARE_LIMIT");
    }

    @Test
    void plan_previousDirection_usesExitThresholdHysteresis() {
        InstitutionPortfolioPolicy policy = new InstitutionPortfolioPolicy(
                1L,
                1L,
                1L,
                "PENSION",
                "Pension",
                "BALANCED_LONG_TERM",
                "SHADOW",
                new BigDecimal("0.600000"),
                new BigDecimal("0.500000"),
                new BigDecimal("0.700000"),
                new BigDecimal("0.700000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("0.010000"),
                new BigDecimal("0.002000"),
                new BigDecimal("0.010000"),
                new BigDecimal("0.010000"),
                60,
                1L
        );

        InstitutionDecisionItem continuingBuy = planner.plan(
                policy,
                List.of(mandate("DEMO001", "1.000000", "0.000000", "1.000000", 1_000_000L)),
                Map.of("DEMO001", market("DEMO001", "100.00", AutoMarketPressure.NEUTRAL)),
                Map.of("DEMO001", new InstitutionPositionSnapshot(5_950L, 0L, 0L, 0L)),
                Map.of(),
                Map.of("DEMO001", InstitutionDecisionAction.BUY),
                new BigDecimal("405000.00"),
                BigDecimal.ZERO,
                new BigDecimal("595000.00")
        ).items().getFirst();
        InstitutionDecisionItem freshDecision = planner.plan(
                policy,
                List.of(mandate("DEMO001", "1.000000", "0.000000", "1.000000", 1_000_000L)),
                Map.of("DEMO001", market("DEMO001", "100.00", AutoMarketPressure.NEUTRAL)),
                Map.of("DEMO001", new InstitutionPositionSnapshot(5_950L, 0L, 0L, 0L)),
                Map.of(),
                Map.of(),
                new BigDecimal("405000.00"),
                BigDecimal.ZERO,
                new BigDecimal("595000.00")
        ).items().getFirst();

        assertThat(continuingBuy.action()).isEqualTo(InstitutionDecisionAction.BUY);
        assertThat(freshDecision.action()).isEqualTo(InstitutionDecisionAction.HOLD);
    }

    @Test
    void plan_twoSymbols_normalizesTargetsToBoundedStockAllocation() {
        AutoMarketPressure positive = new AutoMarketPressure(100, 100, 0, 0, 0);
        InstitutionDecisionPlan plan = planner.plan(
                policy("0.600000", "0.010000", "0.010000"),
                List.of(
                        mandate("DEMO001", "0.500000", "0.100000", "0.400000", 100_000L),
                        mandate("DEMO002", "0.500000", "0.100000", "0.400000", 100_000L)
                ),
                Map.of(
                        "DEMO001", market("DEMO001", "100.00", positive),
                        "DEMO002", market("DEMO002", "100.00", AutoMarketPressure.NEUTRAL)
                ),
                Map.of(),
                Map.of(),
                Map.of(),
                new BigDecimal("1000000.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );

        BigDecimal targetSum = plan.items().stream()
                .map(InstitutionDecisionItem::targetAllocationRate)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(targetSum).isEqualByComparingTo(plan.targetStockAllocationRate());
        assertThat(plan.items())
                .allSatisfy(item -> assertThat(item.targetAllocationRate())
                        .isBetween(new BigDecimal("0.10000000"), new BigDecimal("0.40000000")));
    }

    @Test
    void plan_policyVersionChangesAfterBudgetCreation_rejectsDecision() {
        InstitutionDailyBudgetSnapshot oldBudget = new InstitutionDailyBudgetSnapshot(
                1_000L,
                20L,
                new BigDecimal("10000.00"),
                0L,
                0L,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                99L,
                0L
        );

        assertThatThrownBy(() -> planner.plan(
                policy("0.600000", "0.010000", "0.010000"),
                List.of(mandate("DEMO001", "1.000000", "0.000000", "1.000000", 1_000L)),
                Map.of("DEMO001", market("DEMO001", "100.00", AutoMarketPressure.NEUTRAL)),
                Map.of(),
                Map.of("DEMO001", oldBudget),
                Map.of(),
                new BigDecimal("1000000.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("policy cannot change");
    }

    private InstitutionPortfolioPolicy policy(
            String baseStockAllocation,
            String dailyTurnover,
            String decisionTurnover
    ) {
        return new InstitutionPortfolioPolicy(
                1L,
                1L,
                1L,
                "PENSION",
                "Pension",
                "BALANCED_LONG_TERM",
                "SHADOW",
                new BigDecimal(baseStockAllocation),
                new BigDecimal("0.300000"),
                new BigDecimal("0.800000"),
                new BigDecimal("0.700000"),
                new BigDecimal("0.020000"),
                new BigDecimal("0.020000"),
                new BigDecimal("0.005000"),
                new BigDecimal("0.002000"),
                new BigDecimal(dailyTurnover),
                new BigDecimal(decisionTurnover),
                60,
                1L
        );
    }

    private InstitutionSymbolMandate mandate(
            String symbol,
            String baseWeight,
            String minimum,
            String maximum,
            long referenceVolume
    ) {
        return new InstitutionSymbolMandate(
                symbol,
                new BigDecimal(baseWeight),
                new BigDecimal(minimum),
                new BigDecimal(maximum),
                new BigDecimal("0.100000"),
                new BigDecimal("0.100000"),
                new BigDecimal("0.100000"),
                new BigDecimal("0.100000"),
                referenceVolume,
                new BigDecimal("0.020000")
        );
    }

    private InstitutionMarketInput market(
            String symbol,
            String currentPrice,
            AutoMarketPressure pressure
    ) {
        return new InstitutionMarketInput(
                symbol,
                new BigDecimal(currentPrice),
                pressure,
                AutoMarketPressure.NEUTRAL,
                0.0,
                0.0,
                0.0
        );
    }
}
