package stock.batch.service.batch.automarket.model;

import java.math.BigDecimal;

public record AutoParticipantProfileConfig(
        AutoParticipantProfileType profileType,
        BigDecimal newsWeight,
        BigDecimal momentumWeight,
        BigDecimal contrarianWeight,
        BigDecimal lossAversionWeight,
        BigDecimal herdingWeight,
        BigDecimal marketMakingWeight,
        BigDecimal overconfidenceWeight,
        BigDecimal noiseWeight,
        BigDecimal panicSellWeight,
        BigDecimal dipBuyWeight,
        BigDecimal orderMultiplier,
        BigDecimal aggressionMultiplier,
        BigDecimal orderTtlMultiplier,
        BigDecimal quantityMultiplier,
        BigDecimal holdingPatienceWeight,
        BigDecimal deepLossHoldWeight,
        BigDecimal profitTakingWeight,
        BigDecimal recurringDepositAmount,
        BigDecimal recurringDepositIntervalValue,
        RecurringCashIntervalUnit recurringDepositIntervalUnit,
        Integer recurringDepositIntervalDays
) {
}
