package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.time.LocalDate;

record LiquidityProviderDailyState(
        boolean exists,
        LocalDate simulationTradeDate,
        long mandateId,
        long referenceDailyVolume,
        long executionQuantityLimit,
        long submissionQuantityLimit,
        long submittedBuyQuantity,
        long submittedSellQuantity,
        BigDecimal submittedBuyAmount,
        BigDecimal submittedSellAmount,
        long cancelledBuyQuantity,
        long cancelledSellQuantity,
        BigDecimal openingNetAssetValue,
        long quoteRunCount,
        String gateReason,
        boolean limitBreached,
        long policyVersion,
        long version
) {

    static LiquidityProviderDailyState empty(LocalDate tradeDate, long mandateId) {
        return new LiquidityProviderDailyState(
                false,
                tradeDate,
                mandateId,
                0L,
                0L,
                0L,
                0L,
                0L,
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2),
                0L,
                0L,
                BigDecimal.ZERO.setScale(2),
                0L,
                null,
                false,
                0L,
                0L
        );
    }

    long grossSubmittedQuantity() {
        if (submittedBuyQuantity > Long.MAX_VALUE - submittedSellQuantity) {
            return Long.MAX_VALUE;
        }
        return submittedBuyQuantity + submittedSellQuantity;
    }
}
