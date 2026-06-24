package stock.batch.service.batch.execution.model;

import java.math.BigDecimal;

public record OrderBookHoldingRow(
        long id,
        long quantity,
        long reservedQuantity,
        BigDecimal averagePrice
) {
}
