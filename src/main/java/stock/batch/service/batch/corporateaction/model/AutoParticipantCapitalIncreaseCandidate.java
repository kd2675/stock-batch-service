package stock.batch.service.batch.corporateaction.model;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public record AutoParticipantCapitalIncreaseCandidate(
        Long entitlementId,
        long accountId,
        AutoParticipantProfileType profileType,
        BigDecimal cashBalance,
        long availableShareQuantity
) {
}
