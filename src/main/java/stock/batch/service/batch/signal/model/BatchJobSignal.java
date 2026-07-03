package stock.batch.service.batch.signal.model;

import java.time.LocalDateTime;

public record BatchJobSignal(
        long id,
        String signalType,
        String jobName,
        String executionMode,
        String symbol,
        String requestedBy,
        LocalDateTime requestedAt
) {
}
