package stock.batch.service.batch.automarket.model;

import java.math.BigDecimal;

public record AutoOrder(
        long id,
        long accountId,
        String symbol,
        String side,
        long quantity,
        long filledQuantity,
        BigDecimal reservedCash
) {
}
