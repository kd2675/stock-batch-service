package stock.batch.service.batch.automarket.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AutoOrder(
        long id,
        long accountId,
        String symbol,
        String side,
        long quantity,
        long filledQuantity,
        BigDecimal reservedCash,
        AutoParticipantProfileType profileType,
        LocalDateTime createdAt
) {
    public AutoOrder(
            long id,
            long accountId,
            String symbol,
            String side,
            long quantity,
            long filledQuantity,
            BigDecimal reservedCash
    ) {
        this(id, accountId, symbol, side, quantity, filledQuantity, reservedCash, null, null);
    }
}
