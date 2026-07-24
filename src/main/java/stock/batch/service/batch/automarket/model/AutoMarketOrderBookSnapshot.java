package stock.batch.service.batch.automarket.model;

import java.math.BigDecimal;

public record AutoMarketOrderBookSnapshot(
        BigDecimal bestBid,
        BigDecimal bestAsk,
        long openBuyQuantity,
        long openSellQuantity
) {
    public AutoMarketOrderBookSnapshot {
        openBuyQuantity = Math.max(0L, openBuyQuantity);
        openSellQuantity = Math.max(0L, openSellQuantity);
    }
}
