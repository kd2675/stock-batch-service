package stock.batch.service.batch.corporateaction.model;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public record AutoParticipantEventProfilePolicy(
        AutoParticipantProfileType profileType,
        BigDecimal shareholderSubscriptionRate,
        BigDecimal publicOfferingSubscriptionRate,
        BigDecimal maxCashAllocationRate
) {
}
