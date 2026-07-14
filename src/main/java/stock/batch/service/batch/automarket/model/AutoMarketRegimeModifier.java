package stock.batch.service.batch.automarket.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record AutoMarketRegimeModifier(
        String symbol,
        LocalDate simulationTradeDate,
        AutoMarketRegimePhase regimePhase,
        LocalDateTime modifierWindowStartAt,
        AutoMarketPressure pressure,
        long seed
) {
    public AutoMarketRegimeModifier {
        regimePhase = regimePhase == null ? AutoMarketRegimePhase.SLOT_0600 : regimePhase;
        pressure = pressure == null ? AutoMarketPressure.NEUTRAL : pressure;
    }
}
