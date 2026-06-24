package stock.batch.service.batch.corporateaction.model;

import java.math.BigDecimal;

public record ExRightsActionRow(
        long id,
        String symbol,
        String actionType,
        BigDecimal theoreticalExRightsPrice,
        BigDecimal dividendAmount
) {
}
