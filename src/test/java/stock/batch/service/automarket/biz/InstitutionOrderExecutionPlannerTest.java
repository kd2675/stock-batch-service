package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoMarketDistributionBias;

import static org.assertj.core.api.Assertions.assertThat;

class InstitutionOrderExecutionPlannerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2027, 1, 27, 10, 0);

    private final InstitutionOrderExecutionPlanner planner =
            new InstitutionOrderExecutionPlanner();

    @Test
    void plan_passiveBuy_capsSingleOrderAndNeverCrossesBestAsk() {
        InstitutionOrderExecutionPlan plan = planner.plan(
                intent(1L, 1_000L, "100000.00", 10_000L, "0.000000"),
                marketConfig(),
                new InstitutionExternalBook(
                        new BigDecimal("100.00"),
                        new BigDecimal("101.00"),
                        1_000L,
                        1_000L
                ),
                NOW
        );

        assertThat(plan.executable()).isTrue();
        assertThat(plan.aggressive()).isFalse();
        assertThat(plan.quantity()).isEqualTo(150L);
        assertThat(plan.price()).isLessThan(new BigDecimal("101.00"));
        assertThat(plan.expiresAt()).isEqualTo(NOW.plusSeconds(600));
    }

    @Test
    void plan_plannedNotionalCapsQuantityAtSubmittedPrice() {
        InstitutionOrderExecutionPlan plan = planner.plan(
                intent(1L, 50L, "693.00", 10_000L, "0.000000"),
                marketConfig(),
                InstitutionExternalBook.EMPTY,
                NOW
        );

        assertThat(plan.executable()).isTrue();
        assertThat(plan.price()).isEqualByComparingTo("99.00");
        assertThat(plan.quantity()).isEqualTo(7L);
    }

    @Test
    void plan_aggressiveBuyUsesAtMostTenPercentOfExternalFiveLevelDepth() {
        InstitutionExternalBook book = new InstitutionExternalBook(
                new BigDecimal("99.00"),
                new BigDecimal("101.00"),
                100L,
                40L
        );
        InstitutionOrderExecutionPlan aggressivePlan = null;
        for (long seed = 1L; seed <= 10_000L; seed++) {
            InstitutionOrderExecutionPlan candidate = planner.plan(
                    intent(seed, 1_000L, "100000.00", 10_000L, "1.000000"),
                    marketConfig(),
                    book,
                    NOW
            );
            if (candidate.aggressive()) {
                aggressivePlan = candidate;
                break;
            }
        }

        assertThat(aggressivePlan).isNotNull();
        assertThat(aggressivePlan.price()).isEqualByComparingTo("101.00");
        assertThat(aggressivePlan.quantity()).isEqualTo(4L);
    }

    @Test
    void plan_aggressiveQuoteBeyondDailyLimit_rejectsInsteadOfMislabelingPassiveOrder() {
        long aggressiveSeed = findAggressiveSeed();

        InstitutionOrderExecutionPlan plan = planner.plan(
                intent(aggressiveSeed, 1_000L, "100000.00", 10_000L, "1.000000"),
                marketConfig(),
                new InstitutionExternalBook(
                        new BigDecimal("99.00"),
                        new BigDecimal("200.00"),
                        100L,
                        40L
                ),
                NOW
        );

        assertThat(plan.executable()).isFalse();
        assertThat(plan.reason()).isEqualTo("AGGRESSIVE_PRICE_OUTSIDE_DAILY_LIMIT");
    }

    @Test
    void plan_selfTradeGroupMismatchRejectsBeforePricing() {
        InstitutionOrderIntent invalid = new InstitutionOrderIntent(
                1L,
                "DEMO001",
                1L,
                1L,
                1L,
                "BUY",
                10L,
                new BigDecimal("1000.00"),
                10_000L,
                BigDecimal.ZERO,
                1L,
                1L,
                "ACTIVE",
                "LIVE",
                "ACTIVE",
                "INSTITUTIONAL_INVESTOR",
                "INSTITUTION:ACCOUNT",
                "ACTIVE",
                "INSTITUTIONAL_INVESTOR",
                "INSTITUTION:PARTICIPANT",
                "INSTITUTIONAL_INVESTOR",
                "ACTIVE"
        );

        InstitutionOrderExecutionPlan plan = planner.plan(
                invalid,
                marketConfig(),
                InstitutionExternalBook.EMPTY,
                NOW
        );

        assertThat(plan.executable()).isFalse();
        assertThat(plan.reason()).isEqualTo("SELF_TRADE_GROUP_MISMATCH");
    }

    private InstitutionOrderIntent intent(
            long deterministicSeed,
            long requestedQuantity,
            String plannedAmount,
            long referenceDailyVolume,
            String aggressionPressure
    ) {
        return new InstitutionOrderIntent(
                1L,
                "DEMO001",
                1L,
                1L,
                1L,
                "BUY",
                requestedQuantity,
                new BigDecimal(plannedAmount),
                referenceDailyVolume,
                new BigDecimal(aggressionPressure),
                1L,
                deterministicSeed,
                "ACTIVE",
                "LIVE",
                "ACTIVE",
                "INSTITUTIONAL_INVESTOR",
                "INSTITUTION:ONE",
                "ACTIVE",
                "INSTITUTIONAL_INVESTOR",
                "INSTITUTION:ONE",
                "INSTITUTIONAL_INVESTOR",
                "ACTIVE"
        );
    }

    private AutoMarketConfig marketConfig() {
        return new AutoMarketConfig(
                "DEMO001",
                "KOSPI",
                1_000,
                300,
                500_000L,
                BigDecimal.ONE,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                new BigDecimal("30.00"),
                null,
                AutoMarketDistributionBias.NEUTRAL,
                AutoMarketDistributionBias.NEUTRAL
        );
    }

    private long findAggressiveSeed() {
        InstitutionExternalBook executableBook = new InstitutionExternalBook(
                new BigDecimal("99.00"),
                new BigDecimal("101.00"),
                100L,
                40L
        );
        for (long seed = 1L; seed <= 10_000L; seed++) {
            InstitutionOrderExecutionPlan candidate = planner.plan(
                    intent(seed, 1_000L, "100000.00", 10_000L, "1.000000"),
                    marketConfig(),
                    executableBook,
                    NOW
            );
            if (candidate.aggressive()) {
                return seed;
            }
        }
        throw new AssertionError("No deterministic aggressive seed found");
    }
}
