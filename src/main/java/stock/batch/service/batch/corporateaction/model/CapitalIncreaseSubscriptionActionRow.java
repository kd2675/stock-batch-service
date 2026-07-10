package stock.batch.service.batch.corporateaction.model;

import java.math.BigDecimal;

public record CapitalIncreaseSubscriptionActionRow(
        long id,
        String symbol,
        String offeringType,
        long shareQuantity,
        BigDecimal issuePrice
) {
}
