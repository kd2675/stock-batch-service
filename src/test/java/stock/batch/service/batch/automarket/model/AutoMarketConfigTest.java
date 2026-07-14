package stock.batch.service.batch.automarket.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class AutoMarketConfigTest {

    @Test
    void pressure_primaryAndSecondary_blendsEveryAxisAtSixtyForty() {
        AutoMarketConfig config = config(
                new AutoMarketPressure(100, -100, 50, -50, 25),
                new AutoMarketPressure(-100, 100, -50, 50, -25)
        );

        assertThat(config.dailyPricePressure()).isCloseTo(0.2, offset(0.000001));
        assertThat(config.assetPreferencePressure()).isCloseTo(-0.2, offset(0.000001));
        assertThat(config.volatilityPressure()).isCloseTo(0.1, offset(0.000001));
        assertThat(config.liquidityPressure()).isCloseTo(-0.1, offset(0.000001));
        assertThat(config.executionAggressionPressure()).isCloseTo(0.05, offset(0.000001));
    }

    @Test
    void multipliers_signedPressure_canReduceOrIncreaseBehavior() {
        AutoMarketConfig reduced = config(
                new AutoMarketPressure(0, 0, -100, -100, -100),
                new AutoMarketPressure(0, 0, -100, -100, -100)
        );
        AutoMarketConfig increased = config(
                new AutoMarketPressure(0, 0, 100, 100, 100),
                new AutoMarketPressure(0, 0, 100, 100, 100)
        );

        assertThat(reduced.volatilityMultiplier()).isEqualTo(0.35);
        assertThat(reduced.liquidityMultiplier()).isEqualTo(0.2);
        assertThat(reduced.executionAggressionStrength()).isZero();
        assertThat(increased.volatilityMultiplier()).isEqualTo(1.65);
        assertThat(increased.liquidityMultiplier()).isEqualTo(1.8);
        assertThat(increased.executionAggressionStrength()).isEqualTo(1.0);
    }

    private AutoMarketConfig config(AutoMarketPressure primary, AutoMarketPressure secondary) {
        return new AutoMarketConfig(
                "ZQ001",
                "ORDERBOOK",
                100,
                90,
                1000L,
                BigDecimal.ONE,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                new BigDecimal("30.00"),
                null,
                AutoMarketDistributionBias.NEUTRAL,
                AutoMarketDistributionBias.NEUTRAL,
                primary,
                secondary
        );
    }
}
