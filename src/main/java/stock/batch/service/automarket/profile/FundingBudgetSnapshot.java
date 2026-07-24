package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

public record FundingBudgetSnapshot(
        BigDecimal paydayAvailable,
        BigDecimal dividendAvailable
) {
    public static final FundingBudgetSnapshot EMPTY = new FundingBudgetSnapshot(
            BigDecimal.ZERO,
            BigDecimal.ZERO
    );

    public FundingBudgetSnapshot {
        paydayAvailable = nonNegative(paydayAvailable);
        dividendAvailable = nonNegative(dividendAvailable);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
    }
}
