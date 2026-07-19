package stock.batch.service.batch.signal.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record BatchJobSignal(
        long id,
        String signalType,
        String jobName,
        String executionMode,
        String symbol,
        String requestedBy,
        LocalDateTime requestedAt,
        LocalDate requestedBusinessDate,
        Long requestedSessionEpoch,
        Long expectedCycleId,
        LocalDateTime eligibleAt,
        LocalDateTime nextAttemptAt,
        int attemptCount,
        int maxAttempts,
        String claimToken,
        LocalDateTime leaseUntil
) {

    public BatchJobSignal(
            long id,
            String signalType,
            String jobName,
            String executionMode,
            String symbol,
            String requestedBy,
            LocalDateTime requestedAt
    ) {
        this(
                id,
                signalType,
                jobName,
                executionMode,
                symbol,
                requestedBy,
                requestedAt,
                requestedAt.toLocalDate(),
                null,
                null,
                requestedAt,
                requestedAt,
                1,
                12,
                "test-claim",
                requestedAt.plusMinutes(3)
        );
    }
}
