package stock.batch.service.batch.automarket.model;

import java.time.LocalDate;

public record AutoMarketDailyRegime(
        String symbol,
        LocalDate simulationTradeDate,
        AutoMarketRegimePhase regimePhase,
        AutoMarketRegimePhase sourceRegimePhase,
        AutoMarketPressure pressure,
        long seed
) {
    public AutoMarketDailyRegime {
        regimePhase = regimePhase == null ? AutoMarketRegimePhase.SLOT_0600 : regimePhase;
        sourceRegimePhase = sourceRegimePhase == null ? regimePhase : sourceRegimePhase;
        pressure = pressure == null ? AutoMarketPressure.NEUTRAL : pressure;
    }

    public AutoMarketDailyRegime(
            String symbol,
            LocalDate simulationTradeDate,
            AutoMarketRegimePhase regimePhase,
            AutoMarketPressure pressure,
            long seed
    ) {
        this(symbol, simulationTradeDate, regimePhase, regimePhase, pressure, seed);
    }
}
