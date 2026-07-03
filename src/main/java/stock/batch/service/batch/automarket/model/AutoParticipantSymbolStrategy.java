package stock.batch.service.batch.automarket.model;

import java.time.LocalDateTime;

public record AutoParticipantSymbolStrategy(
        String symbol,
        AutoParticipantStrategy strategy,
        LocalDateTime nextRunAt,
        int priority
) {
}
