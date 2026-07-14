package stock.batch.service.batch.automarket.model;

public record AutoMarketDistributionBias(
        int pricePressure,
        int assetPreferencePressure,
        int volatilityPressure,
        int liquidityPressure,
        int executionAggressionPressure
) {
    public static final AutoMarketDistributionBias NEUTRAL = new AutoMarketDistributionBias(0, 0, 0, 0, 0);

    public AutoMarketDistributionBias {
        pricePressure = clamp(pricePressure);
        assetPreferencePressure = clamp(assetPreferencePressure);
        volatilityPressure = clamp(volatilityPressure);
        liquidityPressure = clamp(liquidityPressure);
        executionAggressionPressure = clamp(executionAggressionPressure);
    }

    private static int clamp(int value) {
        return Math.clamp(value, -100, 100);
    }
}
