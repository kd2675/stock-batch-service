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
        String fundingBudgetType,
        String selfTradeGroupId
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
            LocalDateTime createdAt,
            String fundingBudgetType
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
                fundingBudgetType,
                null
        );
    }

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
                null,
                null
        );
    }

    public String resolvedSelfTradeGroupId() {
        if (selfTradeGroupId != null && !selfTradeGroupId.isBlank()) {
            return selfTradeGroupId;
        }
        return "ACCOUNT:" + accountId;
    }

    public long remainingQuantity() {
        return quantity - filledQuantity;
    }
}
