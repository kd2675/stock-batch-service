package stock.batch.service.batch.settlement.model;

import java.math.BigDecimal;

public record PortfolioSnapshotCommand(
        long accountId,
        String userKey,
        BigDecimal cashBalance,
        BigDecimal marketValue,
        BigDecimal totalAsset,
        BigDecimal returnRate
) {
}
