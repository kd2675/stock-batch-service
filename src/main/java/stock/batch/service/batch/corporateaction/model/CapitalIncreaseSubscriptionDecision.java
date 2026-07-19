package stock.batch.service.batch.corporateaction.model;

import java.math.BigDecimal;

public record CapitalIncreaseSubscriptionDecision(
        Long entitlementId,
        long accountId,
        long shareQuantity,
        BigDecimal cashAmount
) {

    public CapitalIncreaseSubscriptionDecision {
        if (accountId <= 0) {
            throw new IllegalArgumentException("Capital increase subscription account id must be positive");
        }
        if (shareQuantity <= 0) {
            throw new IllegalArgumentException("Capital increase subscription share quantity must be positive");
        }
        if (cashAmount == null || cashAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Capital increase subscription cash amount must be positive");
        }
    }
}
