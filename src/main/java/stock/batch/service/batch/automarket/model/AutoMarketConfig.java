package stock.batch.service.batch.automarket.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AutoMarketConfig(
        String symbol,
        String market,
        int maxOrderQuantity,
        int orderTtlSeconds,
        long tradableShares,
        BigDecimal tickSize,
        BigDecimal currentPrice,
        BigDecimal previousClose,
        BigDecimal priceLimitRate,
        Integer reportScore,
        AutoMarketDistributionBias primaryDistributionBias,
        AutoMarketDistributionBias secondaryDistributionBias,
        AutoMarketPressure primaryPressure,
        AutoMarketPressure secondaryPressure,
        LocalDateTime reportCreatedAt
) {
    private static final double PRIMARY_REGIME_WEIGHT = 0.70;
    private static final double SECONDARY_MODIFIER_WEIGHT = 0.30;

    public AutoMarketConfig {
        primaryDistributionBias = primaryDistributionBias == null
                ? AutoMarketDistributionBias.NEUTRAL
                : primaryDistributionBias;
        secondaryDistributionBias = secondaryDistributionBias == null
                ? AutoMarketDistributionBias.NEUTRAL
                : secondaryDistributionBias;
        primaryPressure = primaryPressure == null ? AutoMarketPressure.NEUTRAL : primaryPressure;
        secondaryPressure = secondaryPressure == null ? AutoMarketPressure.NEUTRAL : secondaryPressure;
    }

    public AutoMarketConfig(
            String symbol,
            String market,
            int maxOrderQuantity,
            int orderTtlSeconds,
            long tradableShares,
            BigDecimal tickSize,
            BigDecimal currentPrice,
            BigDecimal previousClose,
            BigDecimal priceLimitRate,
            Integer reportScore,
            AutoMarketDistributionBias primaryDistributionBias,
            AutoMarketDistributionBias secondaryDistributionBias,
            AutoMarketPressure primaryPressure,
            AutoMarketPressure secondaryPressure
    ) {
        this(
                symbol, market, maxOrderQuantity, orderTtlSeconds, tradableShares, tickSize,
                currentPrice, previousClose, priceLimitRate, reportScore, primaryDistributionBias,
                secondaryDistributionBias, primaryPressure, secondaryPressure, null
        );
    }

    public AutoMarketConfig(
            String symbol,
            String market,
            int maxOrderQuantity,
            int orderTtlSeconds,
            long tradableShares,
            BigDecimal tickSize,
            BigDecimal currentPrice,
            BigDecimal previousClose,
            BigDecimal priceLimitRate,
            Integer reportScore,
            AutoMarketDistributionBias primaryDistributionBias,
            AutoMarketDistributionBias secondaryDistributionBias
    ) {
        this(
                symbol,
                market,
                maxOrderQuantity,
                orderTtlSeconds,
                tradableShares,
                tickSize,
                currentPrice,
                previousClose,
                priceLimitRate,
                reportScore,
                primaryDistributionBias,
                secondaryDistributionBias,
                null
        );
    }

    public AutoMarketConfig(
            String symbol,
            String market,
            int maxOrderQuantity,
            int orderTtlSeconds,
            long tradableShares,
            BigDecimal tickSize,
            BigDecimal currentPrice,
            BigDecimal previousClose,
            BigDecimal priceLimitRate,
            Integer reportScore,
            AutoMarketDistributionBias primaryDistributionBias,
            AutoMarketDistributionBias secondaryDistributionBias,
            LocalDateTime reportCreatedAt
    ) {
        this(
                symbol,
                market,
                maxOrderQuantity,
                orderTtlSeconds,
                tradableShares,
                tickSize,
                currentPrice,
                previousClose,
                priceLimitRate,
                reportScore,
                primaryDistributionBias,
                secondaryDistributionBias,
                AutoMarketPressure.NEUTRAL,
                AutoMarketPressure.NEUTRAL,
                reportCreatedAt
        );
    }

    public AutoMarketConfig(
            String symbol,
            String market,
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
                symbol, market, maxOrderQuantity, orderTtlSeconds, tradableShares, tickSize, currentPrice,
                previousClose, priceLimitRate, reportScore, AutoMarketDistributionBias.NEUTRAL,
                AutoMarketDistributionBias.NEUTRAL
        );
    }

    public AutoMarketConfig(
            String symbol,
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
                symbol, "ORDERBOOK", maxOrderQuantity, orderTtlSeconds, tradableShares, tickSize,
                currentPrice, previousClose, priceLimitRate, reportScore
        );
    }

    public AutoMarketConfig(
            String symbol,
            int maxOrderQuantity,
            int orderTtlSeconds,
            long tradableShares,
            BigDecimal tickSize,
            BigDecimal currentPrice,
            BigDecimal previousClose,
            Integer reportScore
    ) {
        this(
                symbol, "ORDERBOOK", maxOrderQuantity, orderTtlSeconds, tradableShares, tickSize,
                currentPrice, previousClose, BigDecimal.valueOf(30), reportScore
        );
    }

    public AutoMarketConfig withDailyRegime(AutoMarketDailyRegime regime) {
        if (regime == null) {
            return this;
        }
        return new AutoMarketConfig(
                symbol, market, maxOrderQuantity, orderTtlSeconds, tradableShares, tickSize, currentPrice,
                previousClose, priceLimitRate, reportScore, primaryDistributionBias, secondaryDistributionBias,
                regime.pressure(), secondaryPressure, reportCreatedAt
        );
    }

    public AutoMarketConfig withRegimeModifier(AutoMarketRegimeModifier modifier) {
        if (modifier == null) {
            return this;
        }
        return new AutoMarketConfig(
                symbol, market, maxOrderQuantity, orderTtlSeconds, tradableShares, tickSize, currentPrice,
                previousClose, priceLimitRate, reportScore, primaryDistributionBias, secondaryDistributionBias,
                primaryPressure, modifier.pressure(), reportCreatedAt
        );
    }

    public double dailyPricePressure() {
        return blendedPressure(primaryPressure.price(), secondaryPressure.price());
    }

    public double assetPreferencePressure() {
        return blendedPressure(primaryPressure.assetPreference(), secondaryPressure.assetPreference());
    }

    public double reportPricePressure() {
        if (reportScore == null) {
            return 0.0;
        }
        return (Math.clamp(reportScore, 1, 10) - 5.5) / 4.5;
    }

    public double volatilityPressure() {
        return blendedPressure(primaryPressure.volatility(), secondaryPressure.volatility());
    }

    public double liquidityPressure() {
        return blendedPressure(primaryPressure.liquidity(), secondaryPressure.liquidity());
    }

    public double executionAggressionPressure() {
        return blendedPressure(primaryPressure.executionAggression(), secondaryPressure.executionAggression());
    }

    public double volatilityMultiplier() {
        return Math.clamp(1.0 + volatilityPressure() * 0.65, 0.35, 1.65);
    }

    public double liquidityMultiplier() {
        return Math.clamp(1.0 + liquidityPressure() * 0.80, 0.20, 1.80);
    }

    public double executionAggressionMultiplier() {
        return Math.clamp(1.0 + executionAggressionPressure() * 0.85, 0.15, 1.85);
    }

    public double executionAggressionStrength() {
        return Math.clamp((executionAggressionPressure() + 1.0) / 2.0, 0.0, 1.0);
    }

    public int effectiveVolatilityLevel() {
        return pressureToLevel(volatilityPressure());
    }

    public int effectiveLiquidityLevel() {
        return pressureToLevel(liquidityPressure());
    }

    public int effectiveExecutionAggressionLevel() {
        return pressureToLevel(executionAggressionPressure());
    }

    private double blendedPressure(int primary, int secondary) {
        return Math.clamp(
                primary / 100.0 * PRIMARY_REGIME_WEIGHT + secondary / 100.0 * SECONDARY_MODIFIER_WEIGHT,
                -1.0,
                1.0
        );
    }

    private int pressureToLevel(double pressure) {
        return Math.clamp((int) Math.round((Math.clamp(pressure, -1.0, 1.0) + 1.0) * 4.5 + 1.0), 1, 10);
    }

}
