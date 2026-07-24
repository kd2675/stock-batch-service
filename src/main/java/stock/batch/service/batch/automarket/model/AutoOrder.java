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
        BigDecimal limitPrice,
        AutoParticipantProfileType profileType,
        AutoParticipantBehaviorModelVersion behaviorModelVersion,
        LocalDateTime expiresAt,
        LocalDateTime createdAt
) {
    public long remainingQuantity() {
        return Math.max(0L, quantity - filledQuantity);
    }

    public AutoOrder(
            long id,
            long accountId,
            String symbol,
            String side,
            long quantity,
            long filledQuantity,
            BigDecimal reservedCash
    ) {
        this(id, accountId, symbol, side, quantity, filledQuantity, reservedCash, null, null, null, null, null);
    }

    public AutoOrder(
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
        this(id, accountId, symbol, side, quantity, filledQuantity, reservedCash, null, profileType, null, null, createdAt);
    }
}
