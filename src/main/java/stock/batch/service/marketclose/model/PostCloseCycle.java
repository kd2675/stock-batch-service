package stock.batch.service.marketclose.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record PostCloseCycle(
        long id,
        LocalDate businessDate,
        PostCloseScopeType scopeType,
        String scopeKey,
        PostCloseCycleKind cycleKind,
        String skipReason,
        PostClosePhase phase,
        PostCloseCycleStatus status,
        int phaseRevision,
        long version,
        Long closeRunId,
        LocalDateTime settlementEligibleAt,
        int attemptCount,
        String ownerId,
        LocalDateTime leaseUntil,
        LocalDateTime nextRetryAt
) {
}
