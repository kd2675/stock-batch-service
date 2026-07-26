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

class LiquidityProviderQuotePlannerTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2027, 1, 27);
    private static final LocalDateTime NOW = TRADE_DATE.atTime(10, 0);

    private final LiquidityProviderQuotePlanner planner = new LiquidityProviderQuotePlanner();

    @Test
    void plan_neutralLiveMandate_placesOnePassiveBoundedOrderPerSide() {
        LiquidityProviderQuotePlan plan = planner.plan(input());

        assertThat(plan.stateStatus()).isEqualTo("QUOTING");
        assertThat(plan.gateReason()).isEqualTo("WITHIN_LIMITS");
        assertThat(plan.executableOrders()).hasSize(2);
        assertThat(plan.executableOrders())
                .allSatisfy(order -> {
                    assertThat(order.quantity()).isPositive().isLessThanOrEqualTo(100L);
                    assertThat(order.originType().name()).isEqualTo("LIQUIDITY_PROVIDER");
                    assertThat(order.expiresAt()).isEqualTo(NOW.plusSeconds(300));
                });
        assertThat(plan.bidPrice()).isLessThan(plan.askPrice());
        assertThat(plan.bidPrice()).isLessThan(new BigDecimal("10100.00"));
        assertThat(plan.askPrice()).isGreaterThan(new BigDecimal("9900.00"));
        assertThat(plan.projectedInventoryQuantity()).isEqualTo(1_000L);
    }

    @Test
    void plan_externalFiveLevelDepth_capsEachNewOrderAtTenPercent() {
        LiquidityProviderQuoteInput input = input(
                mandate("LIVE"),
                account(1_000L, BigDecimal.valueOf(100L)),
                LiquidityProviderExecutionSnapshot.EMPTY,
                dailyState(),
                new LiquidityProviderExternalBook(
                        new BigDecimal("9900.00"),
                        new BigDecimal("10100.00"),
                        50L,
                        70L
                ),
                List.of(),
                AutoMarketPressure.NEUTRAL,
                AutoMarketPressure.NEUTRAL,
                true
        );

        LiquidityProviderQuotePlan plan = planner.plan(input);

        assertThat(quantity(plan, "BUY")).isEqualTo(5L);
        assertThat(quantity(plan, "SELL")).isEqualTo(7L);
        assertThat(plan.externalBuyDepthQuantity()).isEqualTo(50L);
        assertThat(plan.externalSellDepthQuantity()).isEqualTo(70L);
    }

    @Test
    void plan_primaryAndSecondaryRegimes_changeDepthButKeepPriceSkewBounded() {
        LiquidityProviderMandate mandate = mandate("LIVE", "0.050000", "0.050000", 1_000L);
        LiquidityProviderQuotePlan positive = planner.plan(input(
                mandate,
                account(1_000L, BigDecimal.valueOf(100L)),
                LiquidityProviderExecutionSnapshot.EMPTY,
                dailyState(),
                deepExternalBook(),
                List.of(),
                new AutoMarketPressure(100, 0, 100, 100, 100),
                new AutoMarketPressure(-100, 0, -100, -100, -100),
                true
        ));
        LiquidityProviderQuotePlan negative = planner.plan(input(
                mandate,
                account(1_000L, BigDecimal.valueOf(100L)),
                LiquidityProviderExecutionSnapshot.EMPTY,
                dailyState(),
                deepExternalBook(),
                List.of(),
                new AutoMarketPressure(-100, 0, -100, -100, -100),
                new AutoMarketPressure(100, 0, 100, 100, 100),
                true
        ));

        assertThat(positive.blendedPricePressure()).isEqualTo(0.4);
        assertThat(negative.blendedPricePressure()).isEqualTo(-0.4);
        assertThat(quantity(positive, "BUY")).isGreaterThan(quantity(negative, "BUY"));
        assertThat(positive.bidPrice()).isLessThan(positive.askPrice());
        assertThat(negative.bidPrice()).isLessThan(negative.askPrice());
    }

    @Test
    void plan_youngStaleQuote_respectsMinimumLifetimeAndDoesNotDuplicateSide() {
        AutoOrder youngBuy = openOrder(
                100L,
                "BUY",
                new BigDecimal("9500.00"),
                50L,
                NOW.minusSeconds(10)
        );

        LiquidityProviderQuotePlan plan = planner.plan(inputWithOrders(List.of(youngBuy)));

        assertThat(plan.cancellationOrders()).doesNotContain(youngBuy);
        assertThat(plan.retainedBuyOpenQuantity()).isEqualTo(50L);
        assertThat(plan.proposedOrders().stream().filter(order -> "BUY".equals(order.side()))).isEmpty();
    }

    @Test
    void plan_staleQuoteAfterMinimumLifetime_cancelsAndReplacesOnlyAfterTwoTickDrift() {
        AutoOrder staleBuy = openOrder(
                100L,
                "BUY",
                new BigDecimal("9500.00"),
                50L,
                NOW.minusSeconds(60)
        );

        LiquidityProviderQuotePlan plan = planner.plan(inputWithOrders(List.of(staleBuy)));

        assertThat(plan.cancellationOrders()).containsExactly(staleBuy);
        assertThat(plan.proposedOrders().stream().filter(order -> "BUY".equals(order.side())))
                .singleElement()
                .satisfies(order -> assertThat(order.price()).isEqualByComparingTo(plan.bidPrice()));
    }

    @Test
    void plan_quoteAtTtl_cancelsEvenWhenItsPriceMatchesTarget() {
        BigDecimal targetBid = planner.plan(input()).bidPrice();
        AutoOrder expiredBuy = openOrder(
                100L,
                "BUY",
                targetBid,
                50L,
                NOW.minusSeconds(300)
        );

        LiquidityProviderQuotePlan plan = planner.plan(inputWithOrders(List.of(expiredBuy)));

        assertThat(plan.cancellationOrders()).containsExactly(expiredBuy);
        assertThat(plan.proposedOrders().stream().filter(order -> "BUY".equals(order.side())))
                .hasSize(1);
    }

    @Test
    void plan_inventoryAtUpperBand_disablesBuyWithoutBlockingRiskReducingSell() {
        LiquidityProviderQuotePlan plan = planner.plan(input(
                mandate("LIVE"),
                account(1_500L, BigDecimal.valueOf(100L)),
                LiquidityProviderExecutionSnapshot.EMPTY,
                dailyState(),
                deepExternalBook(),
                List.of(),
                AutoMarketPressure.NEUTRAL,
                AutoMarketPressure.NEUTRAL,
                true
        ));

        assertThat(plan.targetBuyOpenQuantity()).isZero();
        assertThat(quantity(plan, "BUY")).isZero();
        assertThat(quantity(plan, "SELL")).isPositive();
        assertThat(plan.projectedInventoryQuantity()).isLessThan(1_500L);
    }

    @Test
    void plan_openOrdersCountAsWorstCaseExecutionAndHardHaltBeforeCapCanBeExceeded() {
        AutoOrder buy = openOrder(
                100L,
                "BUY",
                new BigDecimal("9900.00"),
                100L,
                NOW.minusSeconds(10)
        );
        LiquidityProviderExecutionSnapshot executions = new LiquidityProviderExecutionSnapshot(
                950L,
                0L,
                new BigDecimal("9500000.00"),
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2)
        );
        LiquidityProviderAccountSnapshot account = account(1_000L, BigDecimal.valueOf(100L));

        LiquidityProviderQuotePlan plan = planner.plan(input(
                mandate("LIVE"),
                account,
                executions,
                establishedDailyState(account, List.of(buy)),
                deepExternalBook(),
                List.of(buy),
                AutoMarketPressure.NEUTRAL,
                AutoMarketPressure.NEUTRAL,
                true
        ));

        assertThat(plan.stateStatus()).isEqualTo("HALTED");
        assertThat(plan.gateReason()).isEqualTo("OPEN_EXPOSURE_OVER_EXECUTION_LIMIT");
        assertThat(plan.limitBreached()).isTrue();
        assertThat(plan.cancellationOrders()).containsExactly(buy);
        assertThat(plan.executableOrders()).isEmpty();
    }

    @Test
    void plan_dailyNetAssetLossAtLimit_haltsAndCancelsQuotes() {
        AutoOrder sell = openOrder(
                101L,
                "SELL",
                new BigDecimal("10100.00"),
                50L,
                NOW.minusSeconds(10)
        );
        LiquidityProviderAccountSnapshot lossAccount = account(
                1_000L,
                new BigDecimal("12000.00")
        );

        LiquidityProviderQuotePlan plan = planner.plan(input(
                mandate("LIVE"),
                lossAccount,
                LiquidityProviderExecutionSnapshot.EMPTY,
                dailyStateWithOpeningNetAssetValue("22000000.00"),
                deepExternalBook(),
                List.of(sell),
                AutoMarketPressure.NEUTRAL,
                AutoMarketPressure.NEUTRAL,
                true
        ));

        assertThat(plan.riskProfit()).isEqualByComparingTo("-2000000.00");
        assertThat(plan.stateStatus()).isEqualTo("HALTED");
        assertThat(plan.gateReason()).isEqualTo("LOSS_LIMIT_REACHED");
        assertThat(plan.cancellationOrders()).containsExactly(sell);
    }

    @Test
    void plan_historicalCostLossDoesNotCarryIntoANewTradingDay() {
        LiquidityProviderQuotePlan plan = planner.plan(input(
                mandate("LIVE"),
                account(1_000L, new BigDecimal("12000.00")),
                LiquidityProviderExecutionSnapshot.EMPTY,
                dailyState(),
                deepExternalBook(),
                List.of(),
                AutoMarketPressure.NEUTRAL,
                AutoMarketPressure.NEUTRAL,
                true
        ));

        assertThat(plan.unrealizedProfit()).isEqualByComparingTo("-2000000.00");
        assertThat(plan.riskProfit()).isZero();
        assertThat(plan.stateStatus()).isEqualTo("QUOTING");
    }

    @Test
    void plan_minimumLifetimeNeverRetainsQuoteThatCrossesExternalBook() {
        AutoOrder marketableBuy = openOrder(
                102L,
                "BUY",
                new BigDecimal("10100.00"),
                50L,
                NOW.minusSeconds(10)
        );

        LiquidityProviderQuotePlan plan = planner.plan(inputWithOrders(List.of(marketableBuy)));

        assertThat(plan.cancellationOrders()).containsExactly(marketableBuy);
        assertThat(plan.retainedBuyOpenQuantity()).isZero();
    }

    @Test
    void plan_minimumLifetimeNeverRetainsCrossedOwnQuotes() {
        AutoOrder buy = openOrder(
                103L,
                "BUY",
                new BigDecimal("10050.00"),
                50L,
                NOW.minusSeconds(10)
        );
        AutoOrder sell = openOrder(
                104L,
                "SELL",
                new BigDecimal("10040.00"),
                50L,
                NOW.minusSeconds(10)
        );

        LiquidityProviderQuotePlan plan = planner.plan(inputWithOrders(List.of(buy, sell)));

        assertThat(plan.cancellationOrders()).containsExactly(buy, sell);
        assertThat(plan.retainedBuyOpenQuantity()).isZero();
        assertThat(plan.retainedSellOpenQuantity()).isZero();
        assertThat(plan.bidPrice()).isLessThan(plan.askPrice());
    }

    @Test
    void plan_invalidExecutionMode_haltsAndCancelsUnexpectedOrders() {
        AutoOrder existing = openOrder(
                100L,
                "BUY",
                new BigDecimal("9900.00"),
                50L,
                NOW.minusSeconds(10)
        );
        LiquidityProviderAccountSnapshot account = account(
                1_000L,
                new BigDecimal("10000.00")
        );
        LiquidityProviderQuotePlan plan = planner.plan(input(
                mandate("INVALID"),
                account,
                LiquidityProviderExecutionSnapshot.EMPTY,
                establishedDailyState(account, List.of(existing)),
                deepExternalBook(),
                List.of(existing),
                AutoMarketPressure.NEUTRAL,
                AutoMarketPressure.NEUTRAL,
                true
        ));

        assertThat(plan.stateStatus()).isEqualTo("HALTED");
        assertThat(plan.gateReason()).isEqualTo("INVALID_EXECUTION_MODE");
        assertThat(plan.proposedOrders()).isEmpty();
        assertThat(plan.executableOrders()).isEmpty();
        assertThat(plan.cancellationOrders()).containsExactly(existing);
    }

    @Test
    void plan_policyChangedAfterDailyStateWasEstablished_haltsForRestOfTradingDay() {
        AutoOrder existing = openOrder(
                105L,
                "BUY",
                new BigDecimal("9900.00"),
                50L,
                NOW.minusSeconds(60)
        );
        LiquidityProviderDailyState previousPolicyState = dailyState(
                "22000000.00",
                false,
                2L
        );

        LiquidityProviderQuotePlan plan = planner.plan(input(
                mandate("LIVE"),
                account(1_000L, BigDecimal.valueOf(100L)),
                LiquidityProviderExecutionSnapshot.EMPTY,
                previousPolicyState,
                deepExternalBook(),
                List.of(existing),
                AutoMarketPressure.NEUTRAL,
                AutoMarketPressure.NEUTRAL,
                true
        ));

        assertThat(plan.stateStatus()).isEqualTo("HALTED");
        assertThat(plan.gateReason()).isEqualTo("POLICY_CHANGED_DURING_SESSION");
        assertThat(plan.limitBreached()).isTrue();
        assertThat(plan.cancellationOrders()).containsExactly(existing);
    }

    @Test
    void plan_dailyStateMissingAfterExecution_haltsInsteadOfResettingLossBaseline() {
        LiquidityProviderExecutionSnapshot executions = new LiquidityProviderExecutionSnapshot(
                1L,
                0L,
                new BigDecimal("10000.00"),
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2)
        );

        LiquidityProviderQuotePlan plan = planner.plan(input(
                mandate("LIVE"),
                account(1_000L, BigDecimal.valueOf(100L)),
                executions,
                dailyState(),
                deepExternalBook(),
                List.of(),
                AutoMarketPressure.NEUTRAL,
                AutoMarketPressure.NEUTRAL,
                true
        ));

        assertThat(plan.stateStatus()).isEqualTo("HALTED");
        assertThat(plan.gateReason()).isEqualTo("DAILY_STATE_INCONSISTENT");
        assertThat(plan.limitBreached()).isTrue();
        assertThat(plan.executableOrders()).isEmpty();
    }

    @Test
    void plan_previousHardLimitBreach_staysHaltedForTradingDay() {
        LiquidityProviderQuotePlan plan = planner.plan(input(
                mandate("LIVE"),
                account(1_000L, BigDecimal.valueOf(100L)),
                LiquidityProviderExecutionSnapshot.EMPTY,
                dailyState("22000000.00", true, 1L),
                deepExternalBook(),
                List.of(),
                AutoMarketPressure.NEUTRAL,
                AutoMarketPressure.NEUTRAL,
                true
        ));

        assertThat(plan.stateStatus()).isEqualTo("HALTED");
        assertThat(plan.gateReason()).isEqualTo("PREVIOUS_HARD_LIMIT_BREACH");
        assertThat(plan.limitBreached()).isTrue();
    }

    @Test
    void plan_selfTradeGroupMismatch_haltsBeforeCreatingAnyQuote() {
        LiquidityProviderAccountSnapshot mismatched = new LiquidityProviderAccountSnapshot(
                200L,
                "ACTIVE",
                "LIQUIDITY_PROVIDER",
                "LP:OTHER",
                new BigDecimal("10000000.00"),
                1_000L,
                0L,
                BigDecimal.valueOf(100L),
                10L,
                "LIQUIDITY_PROVIDER",
                "ACTIVE",
                "LP:ONE",
                "LIQUIDITY_PROVIDER",
                "DEMO001",
                "ACTIVE",
                TRADE_DATE.minusDays(1),
                null,
                0,
                0
        );

        LiquidityProviderQuotePlan plan = planner.plan(input(
                mandate("LIVE"),
                mismatched,
                LiquidityProviderExecutionSnapshot.EMPTY,
                dailyState(),
                deepExternalBook(),
                List.of(),
                AutoMarketPressure.NEUTRAL,
                AutoMarketPressure.NEUTRAL,
                true
        ));

        assertThat(plan.stateStatus()).isEqualTo("HALTED");
        assertThat(plan.gateReason()).isEqualTo("SELF_TRADE_GROUP_MISMATCH");
    }

    @Test
    void plan_roleDeskForDifferentSymbol_haltsBeforeCreatingAnyQuote() {
        LiquidityProviderQuotePlan plan = planner.plan(input(
                mandate("LIVE"),
                account(1_000L, BigDecimal.valueOf(100L), "DEMO999"),
                LiquidityProviderExecutionSnapshot.EMPTY,
                dailyState(),
                deepExternalBook(),
                List.of(),
                AutoMarketPressure.NEUTRAL,
                AutoMarketPressure.NEUTRAL,
                true
        ));

        assertThat(plan.gateReason()).isEqualTo("ROLE_DESK_SYMBOL_MISMATCH");
    }

    private LiquidityProviderQuoteInput input() {
        return input(
                mandate("LIVE"),
                account(1_000L, BigDecimal.valueOf(100L)),
                LiquidityProviderExecutionSnapshot.EMPTY,
                dailyState(),
                deepExternalBook(),
                List.of(),
                AutoMarketPressure.NEUTRAL,
                AutoMarketPressure.NEUTRAL,
                true
        );
    }

    private LiquidityProviderQuoteInput inputWithOrders(List<AutoOrder> orders) {
        LiquidityProviderAccountSnapshot account = account(1_000L, BigDecimal.valueOf(100L));
        return input(
                mandate("LIVE"),
                account,
                LiquidityProviderExecutionSnapshot.EMPTY,
                establishedDailyState(account, orders),
                deepExternalBook(),
                orders,
                AutoMarketPressure.NEUTRAL,
                AutoMarketPressure.NEUTRAL,
                true
        );
    }

    private LiquidityProviderQuoteInput input(
            LiquidityProviderMandate mandate,
            LiquidityProviderAccountSnapshot account,
            LiquidityProviderExecutionSnapshot executions,
            LiquidityProviderDailyState dailyState,
            LiquidityProviderExternalBook externalBook,
            List<AutoOrder> orders,
            AutoMarketPressure primaryPressure,
            AutoMarketPressure secondaryPressure,
            boolean marketTradingEnabled
    ) {
        return new LiquidityProviderQuoteInput(
                mandate,
                market(primaryPressure, secondaryPressure),
                TRADE_DATE,
                NOW,
                account,
                executions,
                dailyState,
                externalBook,
                orders,
                false,
                marketTradingEnabled
        );
    }

    private LiquidityProviderMandate mandate(String mode) {
        return mandate(mode, "0.050000", "0.010000", 100L);
    }

    private LiquidityProviderMandate mandate(
            String mode,
            String targetOpenRate,
            String singleOrderRate,
            long maxOrderQuantity
    ) {
        return new LiquidityProviderMandate(
                1L,
                10L,
                200L,
                "DEMO001",
                "LP-DEMO001",
                mode,
                "ACTIVE",
                TRADE_DATE.minusDays(1),
                null,
                4,
                12,
                maxOrderQuantity,
                10_000L,
                new BigDecimal(targetOpenRate),
                new BigDecimal("0.080000"),
                new BigDecimal(singleOrderRate),
                5,
                new BigDecimal("0.100000"),
                new BigDecimal("0.100000"),
                new BigDecimal("2.0000"),
                1_000L,
                500L,
                3,
                new BigDecimal("0.700000"),
                new BigDecimal("0.250000"),
                4,
                1,
                true,
                30,
                2,
                300,
                30,
                new BigDecimal("100000.00"),
                null,
                1L
        );
    }

    private LiquidityProviderAccountSnapshot account(
            long holdingQuantity,
            BigDecimal averagePrice
    ) {
        return account(holdingQuantity, averagePrice, "DEMO001");
    }

    private LiquidityProviderAccountSnapshot account(
            long holdingQuantity,
            BigDecimal averagePrice,
            String deskCode
    ) {
        return new LiquidityProviderAccountSnapshot(
                200L,
                "ACTIVE",
                "LIQUIDITY_PROVIDER",
                "LP:ONE",
                new BigDecimal("10000000.00"),
                holdingQuantity,
                0L,
                averagePrice,
                10L,
                "LIQUIDITY_PROVIDER",
                "ACTIVE",
                "LP:ONE",
                "LIQUIDITY_PROVIDER",
                deskCode,
                "ACTIVE",
                TRADE_DATE.minusDays(1),
                null,
                0,
                0
        );
    }

    private LiquidityProviderDailyState dailyState() {
        return LiquidityProviderDailyState.empty(TRADE_DATE, 1L);
    }

    private LiquidityProviderDailyState dailyStateWithOpeningNetAssetValue(String amount) {
        return dailyState(amount, false, 1L);
    }

    private LiquidityProviderDailyState dailyState(
            String amount,
            boolean limitBreached,
            long policyVersion
    ) {
        return new LiquidityProviderDailyState(
                true,
                TRADE_DATE,
                1L,
                10_000L,
                1_000L,
                2_000L,
                0L,
                0L,
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2),
                0L,
                0L,
                new BigDecimal(amount),
                1L,
                limitBreached,
                policyVersion,
                0L
        );
    }

    private LiquidityProviderDailyState establishedDailyState(
            LiquidityProviderAccountSnapshot account,
            List<AutoOrder> orders
    ) {
        long submittedBuyQuantity = orders.stream()
                .filter(order -> "BUY".equals(order.side()))
                .mapToLong(AutoOrder::quantity)
                .sum();
        long submittedSellQuantity = orders.stream()
                .filter(order -> "SELL".equals(order.side()))
                .mapToLong(AutoOrder::quantity)
                .sum();
        BigDecimal submittedBuyAmount = orders.stream()
                .filter(order -> "BUY".equals(order.side()))
                .map(order -> order.limitPrice().multiply(BigDecimal.valueOf(order.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2);
        BigDecimal submittedSellAmount = orders.stream()
                .filter(order -> "SELL".equals(order.side()))
                .map(order -> order.limitPrice().multiply(BigDecimal.valueOf(order.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2);
        BigDecimal reservedBuyCash = orders.stream()
                .filter(order -> "BUY".equals(order.side()))
                .map(AutoOrder::reservedCash)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal openingNetAssetValue = account.availableCash()
                .add(reservedBuyCash)
                .add(new BigDecimal("10000.00").multiply(BigDecimal.valueOf(account.holdingQuantity())))
                .setScale(2);
        return new LiquidityProviderDailyState(
                true,
                TRADE_DATE,
                1L,
                10_000L,
                1_000L,
                2_000L,
                submittedBuyQuantity,
                submittedSellQuantity,
                submittedBuyAmount,
                submittedSellAmount,
                0L,
                0L,
                openingNetAssetValue,
                1L,
                false,
                1L,
                0L
        );
    }

    private LiquidityProviderExternalBook deepExternalBook() {
        return new LiquidityProviderExternalBook(
                new BigDecimal("9900.00"),
                new BigDecimal("10100.00"),
                10_000L,
                10_000L
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

    private AutoOrder openOrder(
            long id,
            String side,
            BigDecimal price,
            long quantity,
            LocalDateTime createdAt
    ) {
        BigDecimal reservedCash = "BUY".equals(side)
                ? price.multiply(BigDecimal.valueOf(quantity))
                : BigDecimal.ZERO.setScale(2);
        return new AutoOrder(
                id,
                200L,
                "DEMO001",
                side,
                quantity,
                0L,
                reservedCash,
                price,
                null,
                null,
                createdAt.plusSeconds(300),
                createdAt
        );
    }

    private long quantity(LiquidityProviderQuotePlan plan, String side) {
        return plan.proposedOrders().stream()
                .filter(order -> side.equals(order.side()))
                .mapToLong(AutoMarketPlannedOrder::quantity)
                .sum();
    }
}
