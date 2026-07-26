package stock.batch.service.automarket.biz;

import java.math.BigDecimal;

record InstitutionPortfolioPolicy(
        long portfolioId,
        long participantId,
        long accountId,
        String portfolioCode,
        String displayName,
        String investmentStyle,
        String executionMode,
        BigDecimal baseStockAllocationRate,
        BigDecimal minStockAllocationRate,
        BigDecimal maxStockAllocationRate,
        BigDecimal primaryRegimeWeight,
        BigDecimal assetPreferenceSensitivity,
        BigDecimal volatilitySensitivity,
        BigDecimal entryThresholdRate,
        BigDecimal exitThresholdRate,
        BigDecimal dailyTurnoverLimitRate,
        BigDecimal maxDecisionTurnoverRate,
        int decisionIntervalMinutes,
        long policyVersion
) {

    boolean shadow() {
        return "SHADOW".equals(executionMode);
    }

    boolean pilot() {
        return "PILOT".equals(executionMode);
    }
}
