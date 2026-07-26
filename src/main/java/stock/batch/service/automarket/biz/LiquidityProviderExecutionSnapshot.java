package stock.batch.service.automarket.biz;

import java.math.BigDecimal;

record LiquidityProviderExecutionSnapshot(
        long buyQuantity,
        long sellQuantity,
        BigDecimal buyAmount,
        BigDecimal sellAmount,
        BigDecimal realizedProfit
) {

    static final LiquidityProviderExecutionSnapshot EMPTY = new LiquidityProviderExecutionSnapshot(
            0L,
            0L,
            BigDecimal.ZERO.setScale(2),
            BigDecimal.ZERO.setScale(2),
            BigDecimal.ZERO.setScale(2)
    );

    long grossQuantity() {
        return saturatingAdd(buyQuantity, sellQuantity);
    }

    private long saturatingAdd(long left, long right) {
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
