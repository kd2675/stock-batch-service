package stock.batch.service.batch.automarket.model;

import java.math.BigDecimal;

public record AutoParticipantTradingSnapshot(
        long accountId,
        BigDecimal cashBalance,
        long availableQuantity,
        BigDecimal averagePrice,
        BigDecimal recentDividendCashAmount
) {
}
