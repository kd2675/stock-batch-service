package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoMarketDistributionBias;
import stock.batch.service.batch.automarket.model.AutoMarketPressure;
import stock.batch.service.batch.automarket.model.AutoOrder;

import static org.assertj.core.api.Assertions.assertThat;

class IssueUnderwriterSupplyPlannerTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2027, 1, 27);
    private static final LocalDateTime NOW = TRADE_DATE.atTime(10, 0);

    private final IssueUnderwriterSupplyPlanner planner =
            new IssueUnderwriterSupplyPlanner();

    @Test
    void plan_activeContract_placesOnePassiveSellBoundedByExternalDepth() {
        IssueUnderwriterSupplyPlanner.SupplyPlan plan = planner.plan(input());

        assertThat(plan.executableOrders()).singleElement().satisfies(order -> {
            assertThat(order.side()).isEqualTo("SELL");
            assertThat(order.price()).isEqualByComparingTo("10100.00");
            assertThat(order.quantity()).isEqualTo(100L);
            assertThat(order.expiresAt()).isEqualTo(NOW.plusSeconds(600));
            assertThat(order.originType().name()).isEqualTo("ISSUE_UNDERWRITER");
            assertThat(order.strategyOrigin().underwritingContractId()).isEqualTo(1L);
        });
        assertThat(plan.dailyQuantityLimit()).isEqualTo(1_000L);
        assertThat(plan.submittedQuantity()).isEqualTo(100L);
        assertThat(plan.submittedAmount()).isEqualByComparingTo("1010000.00");
        assertThat(plan.stateStatus()).isEqualTo("ACTIVE");
        assertThat(plan.gateReason()).isEqualTo("WITHIN_LIMITS");
    }

    @Test
    void plan_extremeRegimeValues_doNotChangeUnderwriterDirectionPriceOrQuantity() {
        IssueUnderwriterSupplyPlanner.SupplyPlan positive = planner.plan(input(
                market(
                        new AutoMarketPressure(100, 100, 100, 100, 100),
                        new AutoMarketPressure(100, 100, 100, 100, 100)
                ),
                openOrders(),
                dailyState(),
                new IssueUnderwriterSupplyRepository.SupplyUsage(
                        0L,
                        BigDecimal.ZERO.setScale(2)
                )
        ));
        IssueUnderwriterSupplyPlanner.SupplyPlan negative = planner.plan(input(
                market(
                        new AutoMarketPressure(-100, -100, -100, -100, -100),
                        new AutoMarketPressure(-100, -100, -100, -100, -100)
                ),
                openOrders(),
                dailyState(),
                new IssueUnderwriterSupplyRepository.SupplyUsage(
                        0L,
                        BigDecimal.ZERO.setScale(2)
                )
        ));

        assertThat(positive.executableOrders()).singleElement();
        assertThat(negative.executableOrders()).singleElement();
        assertThat(positive.executableOrders().getFirst().side()).isEqualTo("SELL");
        assertThat(negative.executableOrders().getFirst().side()).isEqualTo("SELL");
        assertThat(positive.executableOrders().getFirst().price())
                .isEqualByComparingTo(negative.executableOrders().getFirst().price());
        assertThat(positive.executableOrders().getFirst().quantity())
                .isEqualTo(negative.executableOrders().getFirst().quantity());
    }

    @Test
    void plan_dailySubmissionLimitReached_doesNotRefundOrReplace() {
        IssueUnderwriterSupplyRepository.DailyState exhausted =
                new IssueUnderwriterSupplyRepository.DailyState(
                        true,
                        TRADE_DATE,
                        1L,
                        10_000L,
                        1_000L,
                        new BigDecimal("10000000.00"),
                        1_000L,
                        new BigDecimal("10000000.00"),
                        5L,
                        5L,
                        new BigDecimal("10100.00"),
                        "GATED",
                        "DAILY_SUBMISSION_LIMIT_REACHED",
                        2L,
                        10L
                );

        IssueUnderwriterSupplyPlanner.SupplyPlan plan = planner.plan(input(
                market(AutoMarketPressure.NEUTRAL, AutoMarketPressure.NEUTRAL),
                openOrders(),
                exhausted,
                new IssueUnderwriterSupplyRepository.SupplyUsage(
                        1_000L,
                        new BigDecimal("10000000.00")
                )
        ));

        assertThat(plan.executableOrders()).isEmpty();
        assertThat(plan.gateReason()).isEqualTo("DAILY_SUBMISSION_LIMIT_REACHED");
        assertThat(plan.dailyQuantityLimit()).isEqualTo(1_000L);
    }

    @Test
    void plan_dailyOrderLimitReached_blocksTinyOrderReplenishment() {
        IssueUnderwriterSupplyRepository.DailyState orderCountExhausted =
                new IssueUnderwriterSupplyRepository.DailyState(
                        true,
                        TRADE_DATE,
                        1L,
                        10_000L,
                        1_000L,
                        new BigDecimal("10000000.00"),
                        20L,
                        new BigDecimal("202000.00"),
                        20L,
                        20L,
                        new BigDecimal("10100.00"),
                        "GATED",
                        "DAILY_ORDER_LIMIT_REACHED",
                        2L,
                        40L
                );

        IssueUnderwriterSupplyPlanner.SupplyPlan plan = planner.plan(input(
                market(AutoMarketPressure.NEUTRAL, AutoMarketPressure.NEUTRAL),
                openOrders(),
                orderCountExhausted,
                new IssueUnderwriterSupplyRepository.SupplyUsage(
                        20L,
                        new BigDecimal("202000.00")
                )
        ));

        assertThat(plan.executableOrders()).isEmpty();
        assertThat(plan.gateReason()).isEqualTo("DAILY_ORDER_LIMIT_REACHED");
    }

    @Test
    void plan_lifetimeLimitWithOpenOrder_retainsFinalOrderUntilTerminal() {
        AutoOrder finalOpenOrder = openOrder(900L, "SELL", 100L);
        IssueUnderwriterSupplyPlanner.SupplyPlan plan = planner.plan(input(
                market(AutoMarketPressure.NEUTRAL, AutoMarketPressure.NEUTRAL),
                new IssueUnderwriterSupplyRepository.OpenOrderLoad(
                        List.of(finalOpenOrder),
                        false,
                        List.of(finalOpenOrder),
                        0
                ),
                dailyState(),
                new IssueUnderwriterSupplyRepository.SupplyUsage(
                        5_000L,
                        new BigDecimal("50000000.00")
                )
        ));

        assertThat(plan.executableOrders()).isEmpty();
        assertThat(plan.cancellationOrders()).isEmpty();
        assertThat(plan.completeContract()).isFalse();
        assertThat(plan.gateReason())
                .isEqualTo("LIFETIME_LIMIT_PENDING_OPEN_ORDER");
    }

    @Test
    void plan_lifetimeLimitWithoutOpenOrder_completesContract() {
        IssueUnderwriterSupplyPlanner.SupplyPlan plan = planner.plan(input(
                market(AutoMarketPressure.NEUTRAL, AutoMarketPressure.NEUTRAL),
                openOrders(),
                dailyState(),
                new IssueUnderwriterSupplyRepository.SupplyUsage(
                        5_000L,
                        new BigDecimal("50000000.00")
                )
        ));

        assertThat(plan.executableOrders()).isEmpty();
        assertThat(plan.completeContract()).isTrue();
        assertThat(plan.stateStatus()).isEqualTo("COMPLETED");
        assertThat(plan.gateReason()).isEqualTo("LIFETIME_LIMIT_REACHED");
    }

    @Test
    void plan_bidAtDailyCeiling_neverCreatesMarketableSell() {
        IssueUnderwriterSupplyRepository.ExternalBook ceilingBook =
                new IssueUnderwriterSupplyRepository.ExternalBook(
                        new BigDecimal("13000.00"),
                        null,
                        10_000L
                );
        IssueUnderwriterSupplyPlanner.SupplyInput input = input();
        IssueUnderwriterSupplyPlanner.SupplyPlan plan = planner.plan(
                new IssueUnderwriterSupplyPlanner.SupplyInput(
                        input.contract(),
                        input.marketConfig(),
                        input.marketTradingEnabled(),
                        input.now(),
                        input.simulationTradeDate(),
                        input.account(),
                        input.openOrders(),
                        ceilingBook,
                        input.referenceDailyVolume(),
                        input.dailyState(),
                        input.supplyUsage()
                )
        );

        assertThat(plan.executableOrders()).isEmpty();
        assertThat(plan.gateReason()).isEqualTo("PASSIVE_PRICE_UNAVAILABLE");
    }

    @Test
    void plan_issuePriceAboveDailyCeiling_neverSellsBelowIssuePrice() {
        IssueUnderwriterSupplyPlanner.SupplyInput baseline = input();
        IssueUnderwriterSupplyRepository.ContractSnapshot highIssuePriceContract =
                new IssueUnderwriterSupplyRepository.ContractSnapshot(
                        baseline.contract().id(),
                        baseline.contract().contractCode(),
                        baseline.contract().symbol(),
                        baseline.contract().participantId(),
                        baseline.contract().accountId(),
                        baseline.contract().totalIssueQuantity(),
                        baseline.contract().tradableAllocationQuantity(),
                        baseline.contract().lockedAllocationQuantity(),
                        baseline.contract().externalAllocationQuantity(),
                        baseline.contract().underwrittenQuantity(),
                        new BigDecimal("14000.00"),
                        baseline.contract().stabilizationStartDate(),
                        baseline.contract().stabilizationEndDate(),
                        baseline.contract().stabilizationQuantityLimit(),
                        baseline.contract().stabilizationAmountLimit(),
                        baseline.contract().status(),
                        baseline.contract().policyVersion()
                );

        IssueUnderwriterSupplyPlanner.SupplyPlan plan = planner.plan(
                new IssueUnderwriterSupplyPlanner.SupplyInput(
                        highIssuePriceContract,
                        baseline.marketConfig(),
                        baseline.marketTradingEnabled(),
                        baseline.now(),
                        baseline.simulationTradeDate(),
                        baseline.account(),
                        baseline.openOrders(),
                        baseline.externalBook(),
                        baseline.referenceDailyVolume(),
                        baseline.dailyState(),
                        baseline.supplyUsage()
                )
        );

        assertThat(plan.executableOrders()).isEmpty();
        assertThat(plan.gateReason()).isEqualTo("PASSIVE_PRICE_UNAVAILABLE");
    }

    private IssueUnderwriterSupplyPlanner.SupplyInput input() {
        return input(
                market(AutoMarketPressure.NEUTRAL, AutoMarketPressure.NEUTRAL),
                openOrders(),
                dailyState(),
                new IssueUnderwriterSupplyRepository.SupplyUsage(
                        0L,
                        BigDecimal.ZERO.setScale(2)
                )
        );
    }

    private IssueUnderwriterSupplyPlanner.SupplyInput input(
            AutoMarketConfig config,
            IssueUnderwriterSupplyRepository.OpenOrderLoad openOrders,
            IssueUnderwriterSupplyRepository.DailyState dailyState,
            IssueUnderwriterSupplyRepository.SupplyUsage usage
    ) {
        return new IssueUnderwriterSupplyPlanner.SupplyInput(
                contract(),
                config,
                true,
                NOW,
                TRADE_DATE,
                account(),
                openOrders,
                new IssueUnderwriterSupplyRepository.ExternalBook(
                        new BigDecimal("9900.00"),
                        new BigDecimal("10100.00"),
                        1_000L
                ),
                10_000L,
                dailyState,
                usage
        );
    }

    private IssueUnderwriterSupplyRepository.ContractSnapshot contract() {
        return new IssueUnderwriterSupplyRepository.ContractSnapshot(
                1L,
                "INITIAL-ISSUE:DEMO001",
                "DEMO001",
                10L,
                200L,
                100_000L,
                50_000L,
                50_000L,
                0L,
                50_000L,
                new BigDecimal("10000.00"),
                TRADE_DATE.minusDays(1),
                TRADE_DATE.plusDays(18),
                5_000L,
                new BigDecimal("50000000.00"),
                "STABILIZING",
                2L
        );
    }

    private IssueUnderwriterSupplyRepository.AccountSnapshot account() {
        return new IssueUnderwriterSupplyRepository.AccountSnapshot(
                200L,
                "ACTIVE",
                "ISSUE_UNDERWRITER",
                "ISSUE_UNDERWRITER:DEFAULT",
                50_000L,
                0L,
                10L,
                "ISSUE_UNDERWRITER",
                "ACTIVE",
                "ISSUE_UNDERWRITER:DEFAULT",
                "ISSUE_UNDERWRITER",
                "ACTIVE",
                TRADE_DATE.minusDays(1),
                null,
                TRADE_DATE,
                0,
                0,
                new IssueUnderwriterSupplyRepository.SupplyReconciliation(
                        100_000L,
                        50_000L,
                        100_000L,
                        0L,
                        100_000L,
                        50_000L,
                        50_000L
                )
        );
    }

    private IssueUnderwriterSupplyRepository.OpenOrderLoad openOrders() {
        return new IssueUnderwriterSupplyRepository.OpenOrderLoad(
                List.of(),
                false,
                List.of(),
                0
        );
    }

    private IssueUnderwriterSupplyRepository.DailyState dailyState() {
        return IssueUnderwriterSupplyRepository.DailyState.empty(
                TRADE_DATE,
                1L
        );
    }

    private AutoOrder openOrder(long id, String side, long quantity) {
        return new AutoOrder(
                id,
                200L,
                "DEMO001",
                side,
                quantity,
                0L,
                BigDecimal.ZERO.setScale(2),
                new BigDecimal("10100.00"),
                null,
                null,
                NOW.plusSeconds(600),
                NOW.minusSeconds(30)
        );
    }

    private AutoMarketConfig market(
            AutoMarketPressure primaryPressure,
            AutoMarketPressure secondaryPressure
    ) {
        return new AutoMarketConfig(
                "DEMO001",
                "KOSPI",
                1_000,
                300,
                1_000_000L,
                BigDecimal.TEN,
                new BigDecimal("10000.00"),
                new BigDecimal("10000.00"),
                new BigDecimal("30.00"),
                null,
                AutoMarketDistributionBias.NEUTRAL,
                AutoMarketDistributionBias.NEUTRAL,
                primaryPressure,
                secondaryPressure,
                null
        );
    }
}
