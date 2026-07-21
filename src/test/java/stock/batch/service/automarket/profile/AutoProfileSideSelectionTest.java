package stock.batch.service.automarket.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;
import stock.batch.service.batch.automarket.model.RecurringCashIntervalUnit;

class AutoProfileSideSelectionTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("buyIntentCases")
    void chooseSide_buySignalWithoutCash_keepsBuyIntent(
            String caseName,
            AutoProfileBehavior behavior,
            int intensity,
            double momentum,
            double herd,
            double unrealizedReturn,
            double dividendCash,
            boolean atLowerPriceLimit
    ) {
        ProfileSignalContext context = context(
                behavior,
                intensity,
                momentum,
                herd,
                unrealizedReturn,
                10,
                BigDecimal.ZERO,
                dividendCash,
                atLowerPriceLimit,
                0
        );

        assertThat(behavior.chooseSide(context)).isEqualTo(AutoProfileBehavior.BUY);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sellIntentCases")
    void chooseSide_sellSignalWithoutHolding_keepsSellIntent(
            String caseName,
            AutoProfileBehavior behavior,
            int intensity,
            double momentum,
            double herd
    ) {
        ProfileSignalContext context = context(
                behavior,
                intensity,
                momentum,
                herd,
                0,
                0,
                BigDecimal.valueOf(1_000),
                0,
                false,
                0
        );

        assertThat(behavior.chooseSide(context)).isEqualTo(AutoProfileBehavior.SELL);
    }

    @Test
    void chooseSide_noHoldingAndSellIntent_keepsSellIntentForFeasibilityFiltering() {
        FixedBiasBehavior behavior = new FixedBiasBehavior(0.0);

        String side = behavior.chooseSide(context(behavior, 5, -0.8, 0, BigDecimal.valueOf(1_000), 0));

        assertThat(side).isEqualTo(AutoProfileBehavior.SELL);
    }

    @Test
    void chooseSide_noCashAndBuyIntent_keepsBuyIntentForFeasibilityFiltering() {
        FixedBiasBehavior behavior = new FixedBiasBehavior(1.0);

        String side = behavior.chooseSide(context(behavior, 5, 0.8, 10, BigDecimal.ZERO, 0));

        assertThat(side).isEqualTo(AutoProfileBehavior.BUY);
    }

    @Test
    void momentumFollower_noHoldingAndDownwardMomentum_expressesSellIntent() {
        MomentumFollowerBehavior behavior = new MomentumFollowerBehavior();

        String side = behavior.chooseSide(context(behavior, 5, -0.8, 0, BigDecimal.valueOf(1_000), 0));

        assertThat(side).isEqualTo(AutoProfileBehavior.SELL);
    }

    @Test
    void newsReactive_strongNegativeReport_expressesSellIntentRegardlessOfActivityLevel() {
        NewsReactiveBehavior behavior = new NewsReactiveBehavior();

        String side = behavior.chooseSide(contextWithReportScore(behavior, 10, 1));

        assertThat(side).isEqualTo(AutoProfileBehavior.SELL);
    }

    @Test
    void newsReactive_strongPositiveReport_expressesBuyIntentRegardlessOfActivityLevel() {
        NewsReactiveBehavior behavior = new NewsReactiveBehavior();

        String side = behavior.chooseSide(contextWithReportScore(behavior, 1, 10));

        assertThat(side).isEqualTo(AutoProfileBehavior.BUY);
    }

    @Test
    void dividendReinvestor_noHoldingAndNoDividend_staysIdle() {
        DividendReinvestorBehavior behavior = new DividendReinvestorBehavior();

        String side = behavior.chooseSide(context(behavior, 5, 0, 0, BigDecimal.valueOf(1_000), 0));

        assertThat(side).isNull();
    }

    @Test
    void marketMaker_noHoldingAndCashAvailable_keepsFirstBidIntent() {
        MarketMakerBehavior behavior = new MarketMakerBehavior();

        String side = behavior.chooseSide(context(behavior, 5, 0, 0, BigDecimal.valueOf(1_000), 0));

        assertThat(side).isEqualTo(AutoProfileBehavior.BUY);
    }

    @Test
    void marketMaker_lowStockAllocation_keepsBuyingUntilInventoryBand() {
        MarketMakerBehavior behavior = new MarketMakerBehavior();

        String side = behavior.chooseSide(
                context(behavior, 5, 0, 0, BigDecimal.valueOf(1_000), 0).withOrderIndex(1)
        );

        assertThat(side).isEqualTo(AutoProfileBehavior.BUY);
    }

    @Test
    void marketMaker_highStockAllocation_selectsSell() {
        MarketMakerBehavior behavior = new MarketMakerBehavior();

        String side = behavior.chooseSide(context(behavior, 5, 0, 100, BigDecimal.valueOf(1_000), 0));

        assertThat(side).isEqualTo(AutoProfileBehavior.SELL);
    }

    @Test
    void marketMaker_balancedStockAllocation_alternatesSides() {
        MarketMakerBehavior behavior = new MarketMakerBehavior();
        ProfileSignalContext context = context(behavior, 5, 0, 10, BigDecimal.valueOf(1_000), 0);

        assertThat(behavior.chooseSide(context)).isEqualTo(AutoProfileBehavior.BUY);
        assertThat(behavior.chooseSide(context.withOrderIndex(1))).isEqualTo(AutoProfileBehavior.SELL);
    }

    @Test
    void lossAverse_losingPosition_staysIdleInsteadOfAveragingDown() {
        LossAverseBehavior behavior = new LossAverseBehavior();

        String side = behavior.chooseSide(context(
                behavior, 5, 0, 0, -0.10, 10, BigDecimal.valueOf(1_000), 0, false, 0
        ));

        assertThat(side).isNull();
    }

    @Test
    void stopLoss_smallLoss_staysIdleUntilThreshold() {
        StopLossTraderBehavior behavior = new StopLossTraderBehavior();

        String side = behavior.chooseSide(context(
                behavior, 5, 0, 0, -0.01, 10, BigDecimal.valueOf(1_000), 0, false, 0
        ));

        assertThat(side).isNull();
    }

    @Test
    void stopLoss_thresholdLoss_selectsSell() {
        StopLossTraderBehavior behavior = new StopLossTraderBehavior();

        String side = behavior.chooseSide(context(
                behavior, 5, 0, 0, -0.05, 10, BigDecimal.valueOf(1_000), 0, false, 0
        ));

        assertThat(side).isEqualTo(AutoProfileBehavior.SELL);
    }

    @Test
    void buyBias_recurringFundingMetadataDoesNotChangeTradingPreference() {
        ProfilePolicy basePolicy = new NoiseTraderBehavior().defaultPolicy();
        ProfilePolicy fundedPolicy = withRecurringDeposit(basePolicy, new BigDecimal("100000000.00"));
        PolicyBehavior baseBehavior = new PolicyBehavior(basePolicy);
        PolicyBehavior fundedBehavior = new PolicyBehavior(fundedPolicy);

        double baseBias = baseBehavior.buyBias(context(baseBehavior, 5, 0, 10, BigDecimal.valueOf(1_000), 0));
        double fundedBias = fundedBehavior.buyBias(context(fundedBehavior, 5, 0, 10, BigDecimal.valueOf(1_000), 0));

        assertThat(fundedBias).isEqualTo(baseBias);
    }

    @Test
    void buyBias_inventoryPenaltyScalesWithInstrumentOrderCapacity() {
        NoiseTraderBehavior behavior = new NoiseTraderBehavior();

        double smallInventoryBias = behavior.buyBias(context(behavior, 5, 0, 20, BigDecimal.valueOf(1_000), 0));
        double largeInventoryBias = behavior.buyBias(context(behavior, 5, 0, 10_000, BigDecimal.valueOf(1_000), 0));

        assertThat(largeInventoryBias).isLessThan(smallInventoryBias);
    }

    private ProfileSignalContext context(
            AutoProfileBehavior behavior,
            int activityLevel,
            double momentumPressure,
            long availableQuantity,
            BigDecimal cashBalance,
            double recentDividendCashAmount
    ) {
        return context(
                behavior,
                activityLevel,
                momentumPressure,
                0,
                0,
                availableQuantity,
                cashBalance,
                recentDividendCashAmount,
                false,
                0
        );
    }

    private ProfileSignalContext contextWithReportScore(
            AutoProfileBehavior behavior,
            int activityLevel,
            int reportScore
    ) {
        AutoMarketConfig config = new AutoMarketConfig(
                "STOCK001",
                100,
                1200,
                1_000_000L,
                BigDecimal.ONE,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                reportScore
        );
        AutoParticipantStrategy strategy = new AutoParticipantStrategy(
                "auto-001",
                1L,
                activityLevel,
                behavior.type()
        );
        return new ProfileSignalContext(
                strategy,
                config,
                behavior.defaultPolicy(),
                activityLevel,
                0,
                0,
                0,
                10,
                BigDecimal.valueOf(1_000),
                BigDecimal.ZERO,
                false,
                0,
                0
        );
    }

    private ProfileSignalContext context(
            AutoProfileBehavior behavior,
            int activityLevel,
            double momentumPressure,
            double herdPressure,
            double unrealizedReturn,
            long availableQuantity,
            BigDecimal cashBalance,
            double recentDividendCashAmount,
            boolean atLowerPriceLimit,
            int orderIndex
    ) {
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
                activityLevel,
                behavior.type()
        );
        return new ProfileSignalContext(
                strategy,
                config,
                behavior.defaultPolicy(),
                activityLevel,
                momentumPressure,
                herdPressure,
                unrealizedReturn,
                availableQuantity,
                cashBalance,
                BigDecimal.valueOf(recentDividendCashAmount),
                atLowerPriceLimit,
                orderIndex,
                0
        );
    }

    private static Stream<Arguments> buyIntentCases() {
        return Stream.of(
                Arguments.of("average down", new AverageDownBuyerBehavior(), 5, 0, 0, -0.10, 0, false),
                Arguments.of("contrarian", new ContrarianBehavior(), 5, -0.80, 0, 0, 0, false),
                Arguments.of("day trader", new DayTraderBehavior(), 5, 0.80, 0, 0, 0, false),
                Arguments.of("dip buyer", new DipBuyerBehavior(), 5, -0.80, 0, 0, 0, false),
                Arguments.of("dividend reinvestor", new DividendReinvestorBehavior(), 5, 0, 0, 0, 100, false),
                Arguments.of("fomo buyer", new FomoBuyerBehavior(), 5, 0.80, 0, 0, 0, false),
                Arguments.of("herd follower", new HerdFollowerBehavior(), 5, 0, 0.80, 0, 0, false),
                Arguments.of("limit down trapped", new LimitDownTrappedBehavior(), 5, 0, 0, 0, 0, true),
                Arguments.of("long term holder", new LongTermHolderBehavior(), 5, 0, 0, -0.30, 0, false),
                Arguments.of("momentum follower", new MomentumFollowerBehavior(), 5, 0.80, 0, 0, 0, false),
                Arguments.of("overconfident", new OverconfidentBehavior(), 5, 0, 0, 0.10, 0, false),
                Arguments.of("payday accumulator", new PaydayAccumulatorBehavior(), 5, 0, 0, -0.10, 0, false),
                Arguments.of("scalper", new ScalperBehavior(), 5, 0.80, 0, 0, 0, false),
                Arguments.of("swing trader", new SwingTraderBehavior(), 5, -0.80, 0, 0, 0, false),
                Arguments.of("value anchor", new ValueAnchorBehavior(), 5, -0.80, 0, 0, 0, false)
        );
    }

    private static Stream<Arguments> sellIntentCases() {
        return Stream.of(
                Arguments.of("contrarian", new ContrarianBehavior(), 5, 0.80, 0),
                Arguments.of("day trader", new DayTraderBehavior(), 5, -0.80, 0),
                Arguments.of("herd follower", new HerdFollowerBehavior(), 5, 0, -0.80),
                Arguments.of("momentum follower", new MomentumFollowerBehavior(), 5, -0.80, 0),
                Arguments.of("panic seller", new PanicSellerBehavior(), 5, -0.80, 0),
                Arguments.of("stop loss trader", new StopLossTraderBehavior(), 5, -0.80, 0),
                Arguments.of("value anchor", new ValueAnchorBehavior(), 5, 0.80, 0)
        );
    }

    private static ProfilePolicy withRecurringDeposit(ProfilePolicy policy, BigDecimal amount) {
        return new ProfilePolicy(
                policy.newsWeight(),
                policy.momentumWeight(),
                policy.contrarianWeight(),
                policy.lossAversionWeight(),
                policy.herdingWeight(),
                policy.marketMakingWeight(),
                policy.overconfidenceWeight(),
                policy.profitTakingWeight(),
                policy.orderMultiplier(),
                policy.aggressionMultiplier(),
                policy.pricePressureSensitivity(),
                policy.orderTtlMultiplier(),
                policy.noiseWeight(),
                policy.quantityMultiplier(),
                policy.panicSellWeight(),
                policy.dipBuyWeight(),
                policy.holdingPatienceWeight(),
                policy.deepLossHoldWeight(),
                amount,
                BigDecimal.ONE,
                RecurringCashIntervalUnit.DAY
        );
    }

    private static final class FixedBiasBehavior extends AbstractAutoProfileBehavior {

        private final double fixedBuyBias;

        private FixedBiasBehavior(double fixedBuyBias) {
            super(AutoParticipantProfileType.NOISE_TRADER, new NoiseTraderBehavior().defaultPolicy());
            this.fixedBuyBias = fixedBuyBias;
        }

        @Override
        public double buyBias(ProfileSignalContext context) {
            return fixedBuyBias;
        }
    }

    private static final class PolicyBehavior extends AbstractAutoProfileBehavior {

        private PolicyBehavior(ProfilePolicy policy) {
            super(AutoParticipantProfileType.NOISE_TRADER, policy);
        }
    }
}
