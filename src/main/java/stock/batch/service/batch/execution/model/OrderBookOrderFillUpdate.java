package stock.batch.service.batch.execution.model;

import java.math.BigDecimal;

public record OrderBookOrderFillUpdate(
        long orderId,
        String status,
        long filledQuantity,
        BigDecimal averageFillPrice,
        BigDecimal reservedCash
) {
}
