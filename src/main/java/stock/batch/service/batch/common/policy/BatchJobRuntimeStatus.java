package stock.batch.service.batch.common.policy;

import java.time.LocalDateTime;

public record BatchJobRuntimeStatus(
        String jobName,
        boolean schedulerConfigured,
        boolean runtimeEnabled,
        boolean effectiveEnabled,
        String updatedBy,
        LocalDateTime updatedAt
) {
}
