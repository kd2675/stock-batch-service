package stock.batch.service.batch.automarket.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record AutoMarketRegimeModifier(
        String symbol,
        LocalDate simulationTradeDate,
        AutoMarketRegimePhase regimePhase,
        LocalDateTime modifierWindowStartAt,
        int priceDirectionModifier,
        int assetPreferenceModifier,
        int directionIntensityModifier,
        int volatilityModifier,
        int liquidityModifier,
        int executionAggressionModifier,
        long seed
) {
    public AutoMarketRegimeModifier {
        regimePhase = regimePhase == null ? AutoMarketRegimePhase.OPENING : regimePhase;
        priceDirectionModifier = clampSecondaryPressure(priceDirectionModifier);
        assetPreferenceModifier = clampSecondaryPressure(assetPreferenceModifier);
        directionIntensityModifier = clampSecondaryLevel(directionIntensityModifier);
        volatilityModifier = clampSecondaryLevel(volatilityModifier);
        liquidityModifier = clampSecondaryLevel(liquidityModifier);
        executionAggressionModifier = clampSecondaryLevel(executionAggressionModifier);
    }

    private static int clampSecondaryPressure(int value) {
        return Math.clamp(value, -10, 10);
    }

    private static int clampSecondaryLevel(int value) {
        if (value <= 0) {
            return 0;
        }
        return Math.clamp(value, 1, 10);
    }
}
