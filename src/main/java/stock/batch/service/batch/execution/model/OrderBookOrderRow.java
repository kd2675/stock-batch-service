package stock.batch.service.batch.execution.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
        BigDecimal reservedCash,
        LocalDateTime createdAt
) {
    public long remainingQuantity() {
        return quantity - filledQuantity;
    }
}
