package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

record LiquidityProviderMandate(
        long id,
        long participantId,
        long accountId,
        String symbol,
        String mandateCode,
        String executionMode,
        String status,
        LocalDate contractStartDate,
        LocalDate contractEndDate,
        int targetSpreadTicks,
        int maxSpreadTicks,
        long maxOrderQuantity,
        long referenceDailyVolume,
        BigDecimal targetOpenParticipationRate,
        BigDecimal maxOpenParticipationRate,
        BigDecimal maxSingleOrderParticipationRate,
        int externalDepthLevels,
        BigDecimal maxExternalDepthParticipationRate,
        BigDecimal dailyExecutionParticipationRate,
        BigDecimal dailySubmissionMultiplier,
        long targetInventoryQuantity,
        long inventoryBandQuantity,
        int inventorySkewTicks,
        BigDecimal primaryRegimeWeight,
        BigDecimal liquiditySizeSensitivity,
        int volatilitySpreadMaxTicks,
        int priceRegimeMaxSkewTicks,
        boolean passiveOnly,
        int minimumQuoteLifetimeSeconds,
        int repriceThresholdTicks,
        int orderTtlSeconds,
        int quoteIntervalSeconds,
        BigDecimal dailyLossLimitAmount,
        LocalDateTime nextQuoteAt,
        long policyVersion
) {

    boolean contractActiveOn(LocalDate tradeDate) {
        return tradeDate != null
                && contractStartDate != null
                && !tradeDate.isBefore(contractStartDate)
                && (contractEndDate == null || !tradeDate.isAfter(contractEndDate));
    }

    boolean live() {
        return "LIVE".equals(executionMode);
    }
}
