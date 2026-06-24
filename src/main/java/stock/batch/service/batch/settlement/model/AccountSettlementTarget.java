package stock.batch.service.batch.settlement.model;

import java.math.BigDecimal;

public record AccountSettlementTarget(
        long accountId,
        String userKey,
        BigDecimal cashBalance,
        BigDecimal netCashFlow,
        BigDecimal marketValue,
        BigDecimal reservedBuyCash
) {
}
