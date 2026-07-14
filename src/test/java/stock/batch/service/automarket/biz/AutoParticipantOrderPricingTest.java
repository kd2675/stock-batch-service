package stock.batch.service.automarket.biz;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import stock.batch.service.automarket.profile.NoiseTraderBehavior;
import stock.batch.service.automarket.profile.ProfilePolicy;
import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoMarketDistributionBias;
import stock.batch.service.batch.automarket.model.AutoMarketPressure;

import static org.assertj.core.api.Assertions.assertThat;

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
}
