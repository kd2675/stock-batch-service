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
        LocalDateTime createdAt,
        String fundingBudgetType
) {
    public OrderBookOrderRow(
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
        this(
                id,
                accountId,
                symbol,
                side,
                orderType,
                limitPrice,
                quantity,
                filledQuantity,
                averageFillPrice,
                reservedCash,
                createdAt,
                null
        );
    }

    public long remainingQuantity() {
        return quantity - filledQuantity;
    }
}
