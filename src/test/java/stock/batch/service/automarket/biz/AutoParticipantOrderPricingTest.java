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
    void directionalPricePressure_profileSensitivityChangesPriceResponseWithoutChangingActivityLevel() {
        AutoMarketConfig config = pressureConfig(new BigDecimal("100.00"), 100, 0, 0, 0);
        ProfilePolicy marketMakerPolicy = AutoProfileBehaviorRegistry.createDefault()
                .behavior(AutoParticipantProfileType.MARKET_MAKER)
                .defaultPolicy();
        ProfilePolicy newsReactivePolicy = AutoProfileBehaviorRegistry.createDefault()
                .behavior(AutoParticipantProfileType.NEWS_REACTIVE)
                .defaultPolicy();

        double marketMakerPressure = pricing.directionalPricePressure(config, 5, marketMakerPolicy);
        double newsReactivePressure = pricing.directionalPricePressure(config, 5, newsReactivePolicy);

        assertThat(List.of(marketMakerPressure, newsReactivePressure))
                .containsExactly(0.15, 0.65);
    }

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
    void createMarketMakingPrice_depthTicks_distributesQuotesBehindSameSideBest() {
        AutoMarketConfig config = pressureConfig(new BigDecimal("100.00"), 0, 0, 0, 0);

        BigDecimal topBuyPrice = pricing.createMarketMakingPrice(
                config, "BUY", new BigDecimal("99.00"), new BigDecimal("101.00"), 0.0, 0
        );
        BigDecimal deepBuyPrice = pricing.createMarketMakingPrice(
                config, "BUY", new BigDecimal("99.00"), new BigDecimal("101.00"), 0.0, 2
        );
        BigDecimal topSellPrice = pricing.createMarketMakingPrice(
                config, "SELL", new BigDecimal("99.00"), new BigDecimal("101.00"), 0.0, 0
        );
        BigDecimal deepSellPrice = pricing.createMarketMakingPrice(
                config, "SELL", new BigDecimal("99.00"), new BigDecimal("101.00"), 0.0, 2
        );

        assertThat(List.of(topBuyPrice, deepBuyPrice, topSellPrice, deepSellPrice))
                .containsExactly(
                        new BigDecimal("99.00"),
                        new BigDecimal("97.00"),
                        new BigDecimal("101.00"),
                        new BigDecimal("103.00")
                );
    }

    @Test
    void createMarketMakingPrice_pressureMovesBothQuoteSidesInPriceDirection() {
        AutoMarketConfig config = pressureConfig(new BigDecimal("100.00"), 0, 0, 0, 0);

        BigDecimal positiveBuyPrice = pricing.createMarketMakingPrice(
                config, "BUY", new BigDecimal("99.00"), new BigDecimal("101.00"), 1.0, 0
        );
        BigDecimal negativeSellPrice = pricing.createMarketMakingPrice(
                config, "SELL", new BigDecimal("99.00"), new BigDecimal("101.00"), -1.0, 0
        );

        assertThat(positiveBuyPrice).isEqualByComparingTo("101.00");
        assertThat(negativeSellPrice).isEqualByComparingTo("99.00");
    }

    @Test
    void createAutoPrice_marketMakerAtLowerLimit_keepsLegalLimitPrice() {
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

        assertThat(price).isEqualByComparingTo("70.00");
    }

    @Test
    void createDirectionalLimitPrice_samePressure_movesDifferentPriceLevelsBySameRate() {
        BigDecimal lowPrice = pricing.createDirectionalLimitPrice(
                pressureConfig(new BigDecimal("10000.00"), 50, 0, 0, 0),
                "BUY",
                1.0,
                1.0
        );
        BigDecimal highPrice = pricing.createDirectionalLimitPrice(
                pressureConfig(new BigDecimal("100000.00"), 50, 0, 0, 0),
                "BUY",
                1.0,
                1.0
        );

        assertThat(lowPrice).isEqualByComparingTo("10060.00");
        assertThat(highPrice).isEqualByComparingTo("100600.00");
    }

    @Test
    void createDirectionalLimitPrice_ratioMove_isCappedAtEightTenthsPercent() {
        AutoMarketConfig config = pressureConfig(new BigDecimal("100000.00"), 100, 100, 0, 0);

        BigDecimal price = pricing.createDirectionalLimitPrice(
                config,
                "BUY",
                1.0,
                config.volatilityMultiplier()
        );

        assertThat(price).isEqualByComparingTo("100800.00");
    }

    @Test
    void createDirectionalLimitPrice_ratioMove_canCrossOppositeQuoteWithoutRepricing() {
        AutoMarketConfig config = pressureConfig(new BigDecimal("100000.00"), 100, 100, 0, 0);

        BigDecimal buyPrice = pricing.createDirectionalLimitPrice(
                config,
                "BUY",
                1.0,
                config.volatilityMultiplier()
        );
        BigDecimal sellPrice = pricing.createDirectionalLimitPrice(
                config,
                "SELL",
                -1.0,
                config.volatilityMultiplier()
        );

        assertThat(buyPrice).isEqualByComparingTo("100800.00");
        assertThat(sellPrice).isEqualByComparingTo("99200.00");
    }

    @Test
    void createDirectionalLimitPrice_neutralPressure_keepsQuotesOffTheSameCenterLevel() {
        AutoMarketConfig config = pressureConfig(new BigDecimal("100.00"), 0, 0, 0, 0);

        BigDecimal buyPrice = pricing.createDirectionalLimitPrice(config, "BUY", 0.0, 1.0);
        BigDecimal sellPrice = pricing.createDirectionalLimitPrice(config, "SELL", 0.0, 1.0);

        assertThat(buyPrice).isLessThan(new BigDecimal("100.00"));
        assertThat(sellPrice).isGreaterThan(new BigDecimal("100.00"));
    }

    @Test
    void createDirectionalLimitPrice_atLowerLimit_returnsLegalLimitPrice() {
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

        BigDecimal price = pricing.createDirectionalLimitPrice(
                config,
                "BUY",
                1.0,
                1.0
        );

        assertThat(price).isEqualByComparingTo("70.00");
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
