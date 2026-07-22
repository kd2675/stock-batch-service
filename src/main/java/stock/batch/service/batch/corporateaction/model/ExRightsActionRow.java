package stock.batch.service.batch.corporateaction.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ExRightsActionRow(
        long id,
        String symbol,
        String actionType,
        String offeringType,
        long shareQuantity,
        BigDecimal issuePrice,
        LocalDate exRightsDate,
        BigDecimal theoreticalExRightsPrice,
        BigDecimal dividendAmount,
        Long entitlementCloseCycleId,
        Long entitlementCloseRunId
) {
    public ExRightsActionRow(
            long id,
            String symbol,
            String actionType,
            String offeringType,
            long shareQuantity,
            BigDecimal issuePrice,
            LocalDate exRightsDate,
            BigDecimal theoreticalExRightsPrice,
            BigDecimal dividendAmount
    ) {
        this(
                id,
                symbol,
                actionType,
                offeringType,
                shareQuantity,
                issuePrice,
                exRightsDate,
                theoreticalExRightsPrice,
                dividendAmount,
                null,
                null
        );
    }
}
