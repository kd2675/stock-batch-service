package stock.batch.service.batch.automarket.model;

public record AutoMarketPressure(
        int price,
        int assetPreference,
        int volatility,
        int liquidity,
        int executionAggression
) {
    public static final AutoMarketPressure NEUTRAL = new AutoMarketPressure(0, 0, 0, 0, 0);

    public AutoMarketPressure {
        price = clamp(price);
        assetPreference = clamp(assetPreference);
        volatility = clamp(volatility);
        liquidity = clamp(liquidity);
        executionAggression = clamp(executionAggression);
    }

    private static int clamp(int value) {
        return Math.clamp(value, -100, 100);
    }
}
