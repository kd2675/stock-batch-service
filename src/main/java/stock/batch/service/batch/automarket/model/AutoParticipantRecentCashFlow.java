package stock.batch.service.batch.automarket.model;

import java.time.LocalDateTime;

public record AutoParticipantRecentCashFlow(
        long accountId,
        String reason,
        LocalDateTime createdAt
) {
}
