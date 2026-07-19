package stock.batch.service.batch.automarket.model;

import java.math.BigDecimal;

public record AutoParticipantCashDeposit(
        long accountId,
        BigDecimal amount,
        String reason
) {

    public AutoParticipantCashDeposit {
        if (accountId <= 0) {
            throw new IllegalArgumentException("accountId must be positive");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
    }
}
