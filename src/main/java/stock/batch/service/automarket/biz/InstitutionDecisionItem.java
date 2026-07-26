package stock.batch.service.automarket.biz;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoMarketPressure;

record InstitutionDecisionItem(
        String symbol,
        AutoMarketPressure primaryPressure,
        AutoMarketPressure secondaryPressure,
        BigDecimal blendedPricePressure,
        BigDecimal blendedAssetPreferencePressure,
        BigDecimal blendedVolatilityPressure,
        BigDecimal blendedLiquidityPressure,
        BigDecimal blendedExecutionAggressionPressure,
        BigDecimal return5Day,
        BigDecimal return20Day,
        BigDecimal reportPressure,
        BigDecimal currentPrice,
        BigDecimal liquidAssetAmount,
        long actualQuantity,
        long openBuyQuantity,
        long openSellQuantity,
        long projectedQuantity,
        BigDecimal actualAllocationRate,
        BigDecimal projectedAllocationRate,
        BigDecimal baseAllocationRate,
        BigDecimal targetStockAllocationRate,
        BigDecimal targetAllocationRate,
        BigDecimal targetAmount,
        BigDecimal rawTradeAmount,
        BigDecimal gatedTradeAmount,
        long gatedQuantity,
        InstitutionDecisionAction action,
        String decisionReason,
        String gateReason,
        long referenceDailyVolume,
        long dailyGrossQuantityLimit,
        BigDecimal dailyGrossNotionalLimit,
        long remainingDailyQuantityBudget,
        BigDecimal remainingDailyNotionalBudget
) {
}
