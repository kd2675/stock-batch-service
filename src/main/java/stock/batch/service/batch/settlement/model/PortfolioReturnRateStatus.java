package stock.batch.service.batch.settlement.model;

import java.math.BigDecimal;

public enum PortfolioReturnRateStatus {
    DEFINED,
    UNDEFINED_ZERO_CONTRIBUTION,
    UNDEFINED_NEGATIVE_CONTRIBUTION;

    public static PortfolioReturnRateStatus from(BigDecimal netContribution) {
        int comparison = netContribution.compareTo(BigDecimal.ZERO);
        if (comparison > 0) {
            return DEFINED;
        }
        if (comparison == 0) {
            return UNDEFINED_ZERO_CONTRIBUTION;
        }
        return UNDEFINED_NEGATIVE_CONTRIBUTION;
    }
}
