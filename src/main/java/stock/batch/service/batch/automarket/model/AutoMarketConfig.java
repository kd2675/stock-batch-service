package stock.batch.service.batch.automarket.model;

import java.math.BigDecimal;

public record AutoMarketConfig(
        String symbol,
        String market,
        int intensity,
        int maxOrderQuantity,
        int orderTtlSeconds,
        long tradableShares,
        BigDecimal tickSize,
        BigDecimal currentPrice,
        BigDecimal previousClose,
        BigDecimal priceLimitRate,
        Integer reportScore,
        AutoMarketPriceDirection priceDirection,
        AutoMarketAssetPreference assetPreference,
        int directionIntensity,
        int volatilityLevel,
        int liquidityLevel,
        int executionAggressionLevel,
        int priceDirectionModifier,
        int assetPreferenceModifier,
        int directionIntensityModifier,
        int volatilityModifier,
        int liquidityModifier,
        int executionAggressionModifier
) {
    private static final double PRIMARY_REGIME_WEIGHT = 0.60;
    private static final double SECONDARY_MODIFIER_WEIGHT = 0.40;

    public AutoMarketConfig {
        priceDirection = priceDirection == null ? AutoMarketPriceDirection.NEUTRAL : priceDirection;
        assetPreference = assetPreference == null ? AutoMarketAssetPreference.BALANCED : assetPreference;
        directionIntensity = Math.clamp(directionIntensity, 1, 10);
        volatilityLevel = Math.clamp(volatilityLevel, 1, 10);
        liquidityLevel = Math.clamp(liquidityLevel, 1, 10);
        executionAggressionLevel = Math.clamp(executionAggressionLevel, 1, 10);
        priceDirectionModifier = clampSecondaryPressure(priceDirectionModifier);
        assetPreferenceModifier = clampSecondaryPressure(assetPreferenceModifier);
        directionIntensityModifier = clampSecondaryLevel(directionIntensityModifier);
        volatilityModifier = clampSecondaryLevel(volatilityModifier);
        liquidityModifier = clampSecondaryLevel(liquidityModifier);
        executionAggressionModifier = clampSecondaryLevel(executionAggressionModifier);
    }

    public AutoMarketConfig(
            String symbol,
            String market,
            int intensity,
            int maxOrderQuantity,
            int orderTtlSeconds,
            long tradableShares,
            BigDecimal tickSize,
            BigDecimal currentPrice,
            BigDecimal previousClose,
            BigDecimal priceLimitRate,
            Integer reportScore,
            AutoMarketPriceDirection priceDirection,
            AutoMarketAssetPreference assetPreference,
            int directionIntensity,
            int volatilityLevel,
            int liquidityLevel
    ) {
        this(
                symbol,
                market,
                intensity,
                maxOrderQuantity,
                orderTtlSeconds,
                tradableShares,
                tickSize,
                currentPrice,
                previousClose,
                priceLimitRate,
                reportScore,
                priceDirection,
                assetPreference,
                directionIntensity,
                volatilityLevel,
                liquidityLevel,
                5,
                0,
                0,
                0,
                0,
                0,
                0
        );
    }

    public AutoMarketConfig(
            String symbol,
            String market,
            int intensity,
            int maxOrderQuantity,
            int orderTtlSeconds,
            long tradableShares,
            BigDecimal tickSize,
            BigDecimal currentPrice,
            BigDecimal previousClose,
            BigDecimal priceLimitRate,
            Integer reportScore
    ) {
        this(
                symbol,
                market,
                intensity,
                maxOrderQuantity,
                orderTtlSeconds,
                tradableShares,
                tickSize,
                currentPrice,
                previousClose,
                priceLimitRate,
                reportScore,
                AutoMarketPriceDirection.NEUTRAL,
                AutoMarketAssetPreference.BALANCED,
                intensity,
                5,
                5,
                5,
                0,
                0,
                0,
                0,
                0,
                0
        );
    }

    public AutoMarketConfig(
            String symbol,
            int intensity,
            int maxOrderQuantity,
            int orderTtlSeconds,
            long tradableShares,
            BigDecimal tickSize,
            BigDecimal currentPrice,
            BigDecimal previousClose,
            BigDecimal priceLimitRate,
            Integer reportScore
    ) {
        this(
                symbol,
                "ORDERBOOK",
                intensity,
                maxOrderQuantity,
                orderTtlSeconds,
                tradableShares,
                tickSize,
                currentPrice,
                previousClose,
                priceLimitRate,
                reportScore,
                AutoMarketPriceDirection.NEUTRAL,
                AutoMarketAssetPreference.BALANCED,
                intensity,
                5,
                5,
                5,
                0,
                0,
                0,
                0,
                0,
                0
        );
    }

    public AutoMarketConfig(
            String symbol,
            int intensity,
            int maxOrderQuantity,
            int orderTtlSeconds,
            long tradableShares,
            BigDecimal tickSize,
            BigDecimal currentPrice,
            BigDecimal previousClose,
            Integer reportScore
    ) {
        this(
                symbol,
                "ORDERBOOK",
                intensity,
                maxOrderQuantity,
                orderTtlSeconds,
                tradableShares,
                tickSize,
                currentPrice,
                previousClose,
                BigDecimal.valueOf(30),
                reportScore,
                AutoMarketPriceDirection.NEUTRAL,
                AutoMarketAssetPreference.BALANCED,
                intensity,
                5,
                5,
                5,
                0,
                0,
                0,
                0,
                0,
                0
        );
    }

    public AutoMarketConfig withDailyRegime(AutoMarketDailyRegime regime) {
        if (regime == null) {
            return this;
        }
        return new AutoMarketConfig(
                symbol,
                market,
                intensity,
                maxOrderQuantity,
                orderTtlSeconds,
                tradableShares,
                tickSize,
                currentPrice,
                previousClose,
                priceLimitRate,
                reportScore,
                regime.priceDirection(),
                regime.assetPreference(),
                regime.directionIntensity(),
                regime.volatilityLevel(),
                regime.liquidityLevel(),
                regime.executionAggressionLevel(),
                priceDirectionModifier,
                assetPreferenceModifier,
                directionIntensityModifier,
                volatilityModifier,
                liquidityModifier,
                executionAggressionModifier
        );
    }

    public AutoMarketConfig withRegimeModifier(AutoMarketRegimeModifier modifier) {
        if (modifier == null) {
            return this;
        }
        return new AutoMarketConfig(
                symbol,
                market,
                intensity,
                maxOrderQuantity,
                orderTtlSeconds,
                tradableShares,
                tickSize,
                currentPrice,
                previousClose,
                priceLimitRate,
                reportScore,
                priceDirection,
                assetPreference,
                directionIntensity,
                volatilityLevel,
                liquidityLevel,
                executionAggressionLevel,
                modifier.priceDirectionModifier(),
                modifier.assetPreferenceModifier(),
                modifier.directionIntensityModifier(),
                modifier.volatilityModifier(),
                modifier.liquidityModifier(),
                modifier.executionAggressionModifier()
        );
    }

    public double dailyPricePressure() {
        return blendedDirectionalPressure(priceDirection.pressureSign(), directionIntensity, priceDirectionModifier);
    }

    public double assetPreferencePressure() {
        return blendedDirectionalPressure(assetPreference.buyPressureSign(), directionIntensity, assetPreferenceModifier);
    }

    public double reportPricePressure() {
        if (reportScore == null) {
            return 0.0;
        }
        return (Math.clamp(reportScore, 1, 10) - 5.5) / 4.5;
    }

    public double volatilityMultiplier() {
        return 0.65 + effectiveVolatilityLevel() / 10.0;
    }

    public double liquidityMultiplier() {
        return 0.60 + effectiveLiquidityLevel() / 8.0;
    }

    public double executionAggressionMultiplier() {
        return 0.45 + effectiveExecutionAggressionLevel() / 6.0;
    }

    public double executionAggressionStrength() {
        return effectiveExecutionAggressionLevel() / 10.0;
    }

    public int effectivePriceDirectionIntensity() {
        return blendedLevel(directionIntensity, directionIntensityModifier);
    }

    public int effectiveAssetPreferenceIntensity() {
        return blendedLevel(directionIntensity, directionIntensityModifier);
    }

    public int effectiveVolatilityLevel() {
        return blendedLevel(volatilityLevel, volatilityModifier);
    }

    public int effectiveLiquidityLevel() {
        return blendedLevel(liquidityLevel, liquidityModifier);
    }

    public int effectiveExecutionAggressionLevel() {
        return blendedLevel(executionAggressionLevel, executionAggressionModifier);
    }

    private double blendedDirectionalPressure(double primarySign, int primaryIntensity, int secondaryPressureValue) {
        double primaryPressure = primarySign * clampLevel(primaryIntensity) / 10.0;
        double secondaryPressure = clampSecondaryPressure(secondaryPressureValue) / 10.0;
        return Math.clamp(
                primaryPressure * PRIMARY_REGIME_WEIGHT + secondaryPressure * SECONDARY_MODIFIER_WEIGHT,
                -1.0,
                1.0
        );
    }

    private int blendedLevel(int primaryLevel, int secondaryLevel) {
        int clampedPrimaryLevel = clampLevel(primaryLevel);
        if (secondaryLevel <= 0) {
            return clampedPrimaryLevel;
        }
        return clampLevel((int) Math.round(
                clampedPrimaryLevel * PRIMARY_REGIME_WEIGHT + clampLevel(secondaryLevel) * SECONDARY_MODIFIER_WEIGHT
        ));
    }

    private static int clampLevel(int value) {
        return Math.clamp(value, 1, 10);
    }

    private static int clampSecondaryPressure(int value) {
        return Math.clamp(value, -10, 10);
    }

    private static int clampSecondaryLevel(int value) {
        if (value <= 0) {
            return 0;
        }
        return clampLevel(value);
    }
}
