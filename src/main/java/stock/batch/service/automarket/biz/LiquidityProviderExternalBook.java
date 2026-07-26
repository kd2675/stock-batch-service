package stock.batch.service.automarket.biz;

import java.math.BigDecimal;

record LiquidityProviderExternalBook(
        BigDecimal bestBid,
        BigDecimal bestAsk,
        long buyDepthQuantity,
        long sellDepthQuantity
) {

    static final LiquidityProviderExternalBook EMPTY =
            new LiquidityProviderExternalBook(null, null, 0L, 0L);

    boolean crossed() {
        return bestBid != null && bestAsk != null && bestBid.compareTo(bestAsk) >= 0;
    }
}
