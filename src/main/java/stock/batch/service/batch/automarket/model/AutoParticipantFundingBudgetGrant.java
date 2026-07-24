package stock.batch.service.batch.automarket.model;

import java.math.BigDecimal;

public record AutoParticipantFundingBudgetGrant(
        long accountId,
        BigDecimal amount,
        String sourceKey
) {
    public AutoParticipantFundingBudgetGrant {
        if (accountId <= 0 || amount == null || amount.signum() <= 0 || sourceKey == null || sourceKey.isBlank()) {
            throw new IllegalArgumentException("A funding budget grant requires account, positive amount, and source key");
        }
    }
}
