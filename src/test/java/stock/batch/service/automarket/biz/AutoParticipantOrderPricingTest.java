package stock.batch.service.automarket.biz;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import stock.batch.service.automarket.profile.AutoProfileBehaviorRegistry;
import stock.batch.service.automarket.profile.NoiseTraderBehavior;
import stock.batch.service.automarket.profile.ProfilePolicy;
import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoMarketDistributionBias;
import stock.batch.service.batch.automarket.model.AutoMarketPressure;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class AutoParticipantOrderPricingTest {

    private final AutoParticipantOrderPricing pricing = new AutoParticipantOrderPricing();
    private final ProfilePolicy policy = new NoiseTraderBehavior().defaultPolicy();
    private final ProfilePolicy maxAggressionPolicy = new ProfilePolicy(
            policy.newsWeight(),
            policy.momentumWeight(),
            policy.contrarianWeight(),
            policy.lossAversionWeight(),
            policy.herdingWeight(),
            policy.marketMakingWeight(),
            policy.overconfidenceWeight(),
            policy.profitTakingWeight(),
            policy.orderMultiplier(),
            2.0,
            policy.orderTtlMultiplier(),
            0.0,
            policy.quantityMultiplier(),
            policy.panicSellWeight(),
            policy.dipBuyWeight(),
            policy.holdingPatienceWeight(),
            policy.deepLossHoldWeight(),
            policy.recurringDepositAmount(),
            policy.recurringDepositIntervalValue(),
            policy.recurringDepositIntervalUnit()
    );

    @Test
    void createAutoPrice_maxExecutionAggression_buyCrossesBestAsk() {
        AutoMarketConfig config = maxExecutionAggressionConfig();
        AutoMarketOrderBookState orderBookState = new AutoMarketOrderBookState(
                new BigDecimal("99.00"),
                new BigDecimal("101.00"),
                100,
                100
        );

        BigDecimal price = pricing.createAutoPrice(config, 5, "BUY", maxAggressionPolicy, orderBookState);

        assertThat(price).isGreaterThanOrEqualTo(new BigDecimal("101.00"));
    }

    @Test
    void createAutoPrice_maxExecutionAggression_sellCrossesBestBid() {
        AutoMarketConfig config = maxExecutionAggressionConfig();
        AutoMarketOrderBookState orderBookState = new AutoMarketOrderBookState(
                new BigDecimal("99.00"),
                new BigDecimal("101.00"),
                100,
                100
        );

        BigDecimal price = pricing.createAutoPrice(config, 5, "SELL", maxAggressionPolicy, orderBookState);

        assertThat(price).isLessThanOrEqualTo(new BigDecimal("99.00"));
    }

    @Test
    void crossingChance_positivePricePressure_favorsBuyAndSuppressesSell() {
        AutoMarketConfig config = pressureConfig(new BigDecimal("100.00"), 50, 0, 0, 0);

        double buyChance = pricing.crossingChance(config, "BUY", policy, 0.5);
        double sellChance = pricing.crossingChance(config, "SELL", policy, 0.5);

        assertThat(buyChance).isCloseTo(0.573, offset(0.000001));
        assertThat(sellChance).isCloseTo(0.323, offset(0.000001));
    }

    @Test
    void crossingChance_negativePricePressure_reversesDirectionalAdvantage() {
        AutoMarketConfig config = pressureConfig(new BigDecimal("100.00"), -50, 0, 0, 0);

        double buyChance = pricing.crossingChance(config, "BUY", policy, -0.5);
        double sellChance = pricing.crossingChance(config, "SELL", policy, -0.5);

        assertThat(buyChance).isCloseTo(0.323, offset(0.000001));
        assertThat(sellChance).isCloseTo(0.573, offset(0.000001));
    }

    @Test
    void crossingChance_maxAggression_keepsOppositeSideBelowCertainCrossing() {
        AutoMarketConfig config = pressureConfig(new BigDecimal("100.00"), 100, 0, 0, 100);

        double buyChance = pricing.crossingChance(config, "BUY", policy, 1.0);
        double sellChance = pricing.crossingChance(config, "SELL", policy, 1.0);

        assertThat(buyChance).isEqualTo(1.0);
        assertThat(sellChance).isCloseTo(0.3255, offset(0.000001));
    }

    @Test
    void crossingChance_zeroProfileAggression_disablesCrossing() {
        AutoMarketConfig config = pressureConfig(new BigDecimal("100.00"), 100, 0, 0, 100);
        ProfilePolicy passivePolicy = policyWithAggression(0.0);

        double buyChance = pricing.crossingChance(config, "BUY", passivePolicy, 1.0);
        double sellChance = pricing.crossingChance(config, "SELL", passivePolicy, 1.0);

        assertThat(buyChance).isZero();
        assertThat(sellChance).isZero();
    }

    @Test
    void crossingChance_maxProfileAggression_preservesDirectionalDifferenceAfterBaseCap() {
        AutoMarketConfig config = pressureConfig(new BigDecimal("100.00"), 50, 0, 0, 0);
        ProfilePolicy aggressivePolicy = policyWithAggression(5.0);

        double buyChance = pricing.crossingChance(config, "BUY", aggressivePolicy, 0.5);
        double sellChance = pricing.crossingChance(config, "SELL", aggressivePolicy, 0.5);

        assertThat(buyChance).isCloseTo(0.85, offset(0.000001));
        assertThat(sellChance).isCloseTo(0.725, offset(0.000001));
    }

    @Test
    void crossingChance_allDefaultNonMarketMakingProfiles_followPressureDirection() {
        AutoMarketConfig config = pressureConfig(new BigDecimal("100.00"), 50, 0, 0, 0);
        List<ProfilePolicy> policies = AutoProfileBehaviorRegistry.createDefault()
                .defaultPolicies()
                .values()
                .stream()
                .filter(defaultPolicy -> defaultPolicy.marketMakingWeight() < 0.8)
                .toList();

        assertThat(policies).hasSize(26).allSatisfy(defaultPolicy -> {
            assertThat(pricing.crossingChance(config, "BUY", defaultPolicy, 0.5))
                    .isGreaterThan(pricing.crossingChance(config, "SELL", defaultPolicy, 0.5));
            assertThat(pricing.crossingChance(config, "BUY", defaultPolicy, -0.5))
                    .isLessThan(pricing.crossingChance(config, "SELL", defaultPolicy, -0.5));
        });
    }

    @Test
    void createAutoPrice_marketMaker_remainsPassiveAtSameSideBestQuotes() {
        AutoMarketConfig config = pressureConfig(new BigDecimal("100.00"), 100, 100, 100, 100);
        ProfilePolicy marketMakerPolicy = AutoProfileBehaviorRegistry.createDefault()
                .behavior(AutoParticipantProfileType.MARKET_MAKER)
                .defaultPolicy();
        AutoMarketOrderBookState orderBookState = new AutoMarketOrderBookState(
                new BigDecimal("99.00"),
                new BigDecimal("101.00"),
                100,
                100
        );

        BigDecimal buyPrice = pricing.createAutoPrice(config, 10, "BUY", marketMakerPolicy, orderBookState);
        BigDecimal sellPrice = pricing.createAutoPrice(config, 10, "SELL", marketMakerPolicy, orderBookState);

        assertThat(buyPrice).isEqualByComparingTo("99.00");
        assertThat(sellPrice).isEqualByComparingTo("101.00");
    }

    @Test
    void createAutoPrice_marketMakerWithoutLegalPassivePrice_returnsInvalidPrice() {
        AutoMarketConfig config = new AutoMarketConfig(
                "ZQ001",
                "ORDERBOOK",
                100,
                90,
                1000L,
                BigDecimal.ONE,
                new BigDecimal("70.00"),
                new BigDecimal("100.00"),
                new BigDecimal("30.00"),
                null
        );
        ProfilePolicy marketMakerPolicy = AutoProfileBehaviorRegistry.createDefault()
                .behavior(AutoParticipantProfileType.MARKET_MAKER)
                .defaultPolicy();

        BigDecimal price = pricing.createAutoPrice(
                config,
                10,
                "BUY",
                marketMakerPolicy,
                new AutoMarketOrderBookState(null, new BigDecimal("70.00"), 0, 100)
        );

        assertThat(price).isZero();
    }

    @Test
    void createPassivePrice_samePressure_movesDifferentPriceLevelsBySameRate() {
        AutoMarketOrderBookState emptyOrderBook = new AutoMarketOrderBookState(null, null, 0, 0);

        BigDecimal lowPrice = pricing.createPassivePrice(
                pressureConfig(new BigDecimal("10000.00"), 50, 0, 0, 0),
                "BUY",
                0.5,
                1.0,
                emptyOrderBook
        );
        BigDecimal highPrice = pricing.createPassivePrice(
                pressureConfig(new BigDecimal("100000.00"), 50, 0, 0, 0),
                "BUY",
                0.5,
                1.0,
                emptyOrderBook
        );

        assertThat(lowPrice).isEqualByComparingTo("10030.00");
        assertThat(highPrice).isEqualByComparingTo("100300.00");
    }

    @Test
    void createPassivePrice_ratioMove_isCappedAtEightTenthsPercent() {
        AutoMarketConfig config = pressureConfig(new BigDecimal("100000.00"), 100, 100, 0, 0);

        BigDecimal price = pricing.createPassivePrice(
                config,
                "BUY",
                1.0,
                config.volatilityMultiplier(),
                new AutoMarketOrderBookState(null, null, 0, 0)
        );

        assertThat(price).isEqualByComparingTo("100800.00");
    }

    @Test
    void createPassivePrice_ratioMove_doesNotBypassCrossingDecision() {
        AutoMarketConfig config = pressureConfig(new BigDecimal("100000.00"), 100, 100, 0, 0);
        AutoMarketOrderBookState buyOrderBookState = new AutoMarketOrderBookState(
                new BigDecimal("99000.00"),
                new BigDecimal("100500.00"),
                100,
                100
        );
        AutoMarketOrderBookState sellOrderBookState = new AutoMarketOrderBookState(
                new BigDecimal("99500.00"),
                new BigDecimal("101000.00"),
                100,
                100
        );

        BigDecimal buyPrice = pricing.createPassivePrice(
                config,
                "BUY",
                1.0,
                config.volatilityMultiplier(),
                buyOrderBookState
        );
        BigDecimal sellPrice = pricing.createPassivePrice(
                config,
                "SELL",
                -1.0,
                config.volatilityMultiplier(),
                sellOrderBookState
        );

        assertThat(buyPrice).isEqualByComparingTo("100400.00");
        assertThat(sellPrice).isEqualByComparingTo("99600.00");
    }

    @Test
    void createPassivePrice_noLegalPriceBeforeLowerLimitAsk_returnsInvalidPrice() {
        AutoMarketConfig config = new AutoMarketConfig(
                "ZQ001",
                "ORDERBOOK",
                100,
                90,
                1000L,
                BigDecimal.ONE,
                new BigDecimal("70.00"),
                new BigDecimal("100.00"),
                new BigDecimal("30.00"),
                null
        );

        BigDecimal price = pricing.createPassivePrice(
                config,
                "BUY",
                1.0,
                1.0,
                new AutoMarketOrderBookState(null, new BigDecimal("70.00"), 0, 100)
        );

        assertThat(price).isZero();
    }

    @Test
    void avoidSelfCross_buyMovesBelowOwnBestAsk() {
        BigDecimal price = pricing.avoidSelfCross(
                maxExecutionAggressionConfig(),
                "BUY",
                new BigDecimal("105.00"),
                null,
                new BigDecimal("101.00")
        );

        assertThat(price).isLessThan(new BigDecimal("101.00"));
    }

    @Test
    void avoidSelfCross_sellMovesAboveOwnBestBid() {
        BigDecimal price = pricing.avoidSelfCross(
                maxExecutionAggressionConfig(),
                "SELL",
                new BigDecimal("95.00"),
                new BigDecimal("99.00"),
                null
        );

        assertThat(price).isGreaterThan(new BigDecimal("99.00"));
    }

    private AutoMarketConfig maxExecutionAggressionConfig() {
        return new AutoMarketConfig(
                "ZQ001",
                "ORDERBOOK",
                100,
                90,
                1000L,
                new BigDecimal("1.00"),
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                new BigDecimal("30.00"),
                null,
                AutoMarketDistributionBias.NEUTRAL,
                AutoMarketDistributionBias.NEUTRAL,
                new AutoMarketPressure(0, 0, 0, 100, 100),
                new AutoMarketPressure(0, 0, 0, 100, 100)
        );
    }

    private AutoMarketConfig pressureConfig(
            BigDecimal currentPrice,
            int pricePressure,
            int volatilityPressure,
            int liquidityPressure,
            int executionAggressionPressure
    ) {
        AutoMarketPressure pressure = new AutoMarketPressure(
                pricePressure,
                0,
                volatilityPressure,
                liquidityPressure,
                executionAggressionPressure
        );
        return new AutoMarketConfig(
                "ZQ001",
                "ORDERBOOK",
                100,
                90,
                1000L,
                BigDecimal.ONE,
                currentPrice,
                currentPrice,
                new BigDecimal("30.00"),
                null,
                AutoMarketDistributionBias.NEUTRAL,
                AutoMarketDistributionBias.NEUTRAL,
                pressure,
                pressure
        );
    }

    private ProfilePolicy policyWithAggression(double aggressionMultiplier) {
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
                aggressionMultiplier,
                policy.orderTtlMultiplier(),
                policy.noiseWeight(),
                policy.quantityMultiplier(),
                policy.panicSellWeight(),
                policy.dipBuyWeight(),
                policy.holdingPatienceWeight(),
                policy.deepLossHoldWeight(),
                policy.recurringDepositAmount(),
                policy.recurringDepositIntervalValue(),
                policy.recurringDepositIntervalUnit()
        );
    }
}
