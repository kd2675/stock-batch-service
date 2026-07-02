package stock.batch.service.automarket.biz;

import java.math.BigDecimal;

record AutoMarketOrderBookState(
        BigDecimal bestBid,
        BigDecimal bestAsk,
        long openBuyQuantity,
        long openSellQuantity
) {

    private static final String BUY = "BUY";
    private static final String SELL = "SELL";

    AutoMarketOrderBookState withPlacedOrder(String side, BigDecimal price, long quantity) {
        long normalizedQuantity = Math.max(0L, quantity);
        if (BUY.equals(side)) {
            BigDecimal nextBestBid = bestBid == null || price.compareTo(bestBid) > 0 ? price : bestBid;
            return new AutoMarketOrderBookState(nextBestBid, bestAsk, openBuyQuantity + normalizedQuantity, openSellQuantity);
        }
        if (SELL.equals(side)) {
            BigDecimal nextBestAsk = bestAsk == null || price.compareTo(bestAsk) < 0 ? price : bestAsk;
            return new AutoMarketOrderBookState(bestBid, nextBestAsk, openBuyQuantity, openSellQuantity + normalizedQuantity);
        }
        return this;
    }

    double orderPressure() {
        long totalQuantity = openBuyQuantity + openSellQuantity;
        if (totalQuantity <= 0) {
            return 0;
        }
        return Math.clamp((double) (openBuyQuantity - openSellQuantity) / totalQuantity, -1, 1);
    }
}
