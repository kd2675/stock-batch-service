package stock.batch.service.automarket.biz;

import java.math.BigDecimal;

record AutoMarketPlannedOrder(
        long accountId,
        String symbol,
        String side,
        BigDecimal price,
        long quantity
) {

    BigDecimal reservedCash() {
        if (!"BUY".equals(side)) {
            return BigDecimal.ZERO;
        }
        return price.multiply(BigDecimal.valueOf(quantity));
    }
}
