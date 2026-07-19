package stock.batch.service.batch.settlement.model;

import java.math.BigDecimal;

public record AccountSettlementTarget(
        long closeCycleId,
        long closeRunId,
        long accountId,
        String userKey,
        BigDecimal cashBalance,
        BigDecimal netCashFlow,
        BigDecimal marketValue,
        BigDecimal pendingSubscriptionAsset,
        long holdingQuantity,
        long reservedSellQuantity,
        long holdingPositionCount
) {
}
