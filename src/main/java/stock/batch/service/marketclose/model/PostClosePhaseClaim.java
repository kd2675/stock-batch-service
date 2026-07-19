package stock.batch.service.marketclose.model;

import java.time.LocalDateTime;

public record PostClosePhaseClaim(
        long cycleId,
        PostClosePhase phase,
        int attemptNo,
        String ownerId,
        LocalDateTime leaseUntil
) {
}
