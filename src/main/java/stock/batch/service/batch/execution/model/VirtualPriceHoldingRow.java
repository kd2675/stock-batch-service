package stock.batch.service.batch.execution.model;

import java.math.BigDecimal;

public record VirtualPriceHoldingRow(
        long id,
        long quantity,
        BigDecimal averagePrice
) {
}
