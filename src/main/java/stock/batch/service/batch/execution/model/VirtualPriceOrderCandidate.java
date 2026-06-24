package stock.batch.service.batch.execution.model;

import java.math.BigDecimal;

public record VirtualPriceOrderCandidate(
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
        BigDecimal currentPrice
) {
}
