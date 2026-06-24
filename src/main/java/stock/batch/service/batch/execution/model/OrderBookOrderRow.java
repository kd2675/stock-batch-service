package stock.batch.service.batch.execution.model;

import java.math.BigDecimal;

public record OrderBookOrderRow(
        long id,
        long accountId,
        String symbol,
        String side,
        String orderType,
        BigDecimal limitPrice,
        long quantity,
        long filledQuantity,
        BigDecimal averageFillPrice,
        BigDecimal reservedCash
) {
    public long remainingQuantity() {
        return quantity - filledQuantity;
    }
}
