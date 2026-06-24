package stock.batch.service.batch.corporateaction.model;

import java.math.BigDecimal;

public record DelistingOrderRow(
        long id,
        long accountId,
        String symbol,
        String side,
        long remainingQuantity,
        BigDecimal reservedCash
) {
}
