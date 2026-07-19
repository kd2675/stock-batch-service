package stock.batch.service.batch.settlement.model;

import java.math.BigDecimal;

public record PortfolioSnapshotCommand(
        long closeCycleId,
        long closeRunId,
        long accountId,
        String userKey,
        BigDecimal cashBalance,
        BigDecimal pendingSubscriptionAsset,
        BigDecimal marketValue,
        long holdingQuantity,
        long reservedSellQuantity,
        long holdingPositionCount,
        BigDecimal totalAsset,
        BigDecimal returnRate,
        String inputHash,
        String calculationVersion,
        String dataQualityStatus
) {
}
