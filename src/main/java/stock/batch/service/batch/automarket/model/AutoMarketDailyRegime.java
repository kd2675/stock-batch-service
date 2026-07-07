package stock.batch.service.batch.automarket.model;

import java.time.LocalDate;

public record AutoMarketDailyRegime(
        String symbol,
        LocalDate simulationTradeDate,
        AutoMarketRegimePhase regimePhase,
        AutoMarketPriceDirection priceDirection,
        AutoMarketAssetPreference assetPreference,
        int directionIntensity,
        int volatilityLevel,
        int liquidityLevel,
        int executionAggressionLevel,
        long seed
) {
    public AutoMarketDailyRegime {
        directionIntensity = Math.clamp(directionIntensity, 1, 10);
        volatilityLevel = Math.clamp(volatilityLevel, 1, 10);
        liquidityLevel = Math.clamp(liquidityLevel, 1, 10);
        executionAggressionLevel = Math.clamp(executionAggressionLevel, 1, 10);
        regimePhase = regimePhase == null ? AutoMarketRegimePhase.OPENING : regimePhase;
        priceDirection = priceDirection == null ? AutoMarketPriceDirection.NEUTRAL : priceDirection;
        assetPreference = assetPreference == null ? AutoMarketAssetPreference.BALANCED : assetPreference;
    }
}
