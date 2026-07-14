package stock.batch.service.batch.automarket.model;

import java.time.LocalDate;

public record AutoMarketDailyRegime(
        String symbol,
        LocalDate simulationTradeDate,
        AutoMarketRegimePhase regimePhase,
        AutoMarketPressure pressure,
        long seed
) {
    public AutoMarketDailyRegime {
        regimePhase = regimePhase == null ? AutoMarketRegimePhase.SLOT_0600 : regimePhase;
        pressure = pressure == null ? AutoMarketPressure.NEUTRAL : pressure;
    }
}
