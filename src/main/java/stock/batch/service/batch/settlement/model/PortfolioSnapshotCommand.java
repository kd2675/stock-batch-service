package stock.batch.service.batch.settlement.model;

import java.math.BigDecimal;

public record PortfolioSnapshotCommand(
        long accountId,
        String userKey,
        BigDecimal cashBalance,
        BigDecimal marketValue,
        long holdingQuantity,
        long reservedSellQuantity,
        long holdingPositionCount,
        BigDecimal totalAsset,
        BigDecimal returnRate
) {
}
