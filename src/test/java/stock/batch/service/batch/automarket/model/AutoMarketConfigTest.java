package stock.batch.service.batch.automarket.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AutoMarketConfigTest {

    @Test
    void dailyPricePressure_secondaryModifierCanReverseWeakPrimaryDirection() {
        AutoMarketConfig config = config(
                AutoMarketPriceDirection.UP,
                AutoMarketAssetPreference.BALANCED,
                4,
                -10,
                0
        );

        assertThat(config.dailyPricePressure()).isLessThan(0.0);
    }

    @Test
    void dailyPricePressure_strongPrimaryDirectionSurvivesOppositeSecondaryModifier() {
        AutoMarketConfig config = config(
                AutoMarketPriceDirection.UP,
                AutoMarketAssetPreference.BALANCED,
                9,
                -10,
                0
        );

        assertThat(config.dailyPricePressure()).isGreaterThan(0.0);
    }

    @Test
    void assetPreferencePressure_secondaryModifierCanReverseWeakPrimaryPreference() {
        AutoMarketConfig config = config(
                AutoMarketPriceDirection.NEUTRAL,
                AutoMarketAssetPreference.STOCK,
                4,
                0,
                -10
        );

        assertThat(config.assetPreferencePressure()).isLessThan(0.0);
    }

    @Test
    void effectiveLevels_blendPrimaryAndThirtyMinuteSecondaryLevel() {
        AutoMarketConfig config = config(
                AutoMarketPriceDirection.NEUTRAL,
                AutoMarketAssetPreference.BALANCED,
                8,
                0,
                0,
                2,
                10,
                9,
                7
        );

        assertThat(config.effectivePriceDirectionIntensity()).isEqualTo(6);
        assertThat(config.effectiveVolatilityLevel()).isEqualTo(7);
        assertThat(config.effectiveLiquidityLevel()).isEqualTo(7);
        assertThat(config.effectiveExecutionAggressionLevel()).isEqualTo(6);
    }

    private AutoMarketConfig config(
            AutoMarketPriceDirection priceDirection,
            AutoMarketAssetPreference assetPreference,
            int directionIntensity,
            int priceDirectionModifier,
            int assetPreferenceModifier
    ) {
        return config(
                priceDirection,
                assetPreference,
                directionIntensity,
                priceDirectionModifier,
                assetPreferenceModifier,
                0,
                0,
                0,
                0
        );
    }

    private AutoMarketConfig config(
            AutoMarketPriceDirection priceDirection,
            AutoMarketAssetPreference assetPreference,
            int directionIntensity,
            int priceDirectionModifier,
            int assetPreferenceModifier,
            int directionIntensityModifier,
            int volatilityModifier,
            int liquidityModifier,
            int executionAggressionModifier
    ) {
        return new AutoMarketConfig(
                "ZQ001",
                "ORDERBOOK",
                5,
                100,
                90,
                1000L,
                BigDecimal.ONE,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                new BigDecimal("30.00"),
                null,
                priceDirection,
                assetPreference,
                directionIntensity,
                5,
                5,
                5,
                priceDirectionModifier,
                assetPreferenceModifier,
                directionIntensityModifier,
                volatilityModifier,
                liquidityModifier,
                executionAggressionModifier
        );
    }
}
