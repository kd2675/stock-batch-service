package stock.batch.service.batch.corporateaction.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ExRightsActionRow(
        long id,
        String symbol,
        String actionType,
        LocalDate exRightsDate,
        BigDecimal theoreticalExRightsPrice,
        BigDecimal dividendAmount
) {
}
