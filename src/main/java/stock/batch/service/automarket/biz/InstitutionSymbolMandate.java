package stock.batch.service.automarket.biz;

import java.math.BigDecimal;

record InstitutionSymbolMandate(
        String symbol,
        BigDecimal baseSymbolWeight,
        BigDecimal minPortfolioAllocationRate,
        BigDecimal maxPortfolioAllocationRate,
        BigDecimal pricePressureSensitivity,
        BigDecimal momentumSensitivity,
        BigDecimal valueSensitivity,
        BigDecimal reportSensitivity,
        long referenceDailyVolume,
        BigDecimal dailyParticipationRate
) {
}
