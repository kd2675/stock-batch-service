package stock.batch.service.batch.corporateaction.model;

import java.math.BigDecimal;

public record DividendEntitlementRow(
        long id,
        long accountId,
        BigDecimal cashAmount
) {
}
