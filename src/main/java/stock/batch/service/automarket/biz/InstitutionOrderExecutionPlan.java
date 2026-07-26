package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.time.LocalDateTime;

record InstitutionOrderExecutionPlan(
        boolean executable,
        String reason,
        BigDecimal price,
        long quantity,
        boolean aggressive,
        LocalDateTime expiresAt
) {

    static InstitutionOrderExecutionPlan rejected(String reason) {
        return new InstitutionOrderExecutionPlan(
                false,
                reason,
                null,
                0L,
                false,
                null
        );
    }
}
