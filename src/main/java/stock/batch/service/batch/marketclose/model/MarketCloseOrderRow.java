package stock.batch.service.batch.marketclose.model;

import java.math.BigDecimal;

public record MarketCloseOrderRow(
        long id,
        long accountId,
        String symbol,
        String side,
        long remainingQuantity,
        BigDecimal reservedCash
) {
}
