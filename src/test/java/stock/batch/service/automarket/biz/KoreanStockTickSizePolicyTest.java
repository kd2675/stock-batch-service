package stock.batch.service.automarket.biz;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class KoreanStockTickSizePolicyTest {

    @Test
    void tickSizeForQuotePrice_appliesKoreanStockPriceBands() {
        assertThat(KoreanStockTickSizePolicy.tickSizeForQuotePrice("ORDERBOOK", new BigDecimal("1999.00"))).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(KoreanStockTickSizePolicy.tickSizeForQuotePrice("ORDERBOOK", new BigDecimal("2000.00"))).isEqualByComparingTo(new BigDecimal("5"));
        assertThat(KoreanStockTickSizePolicy.tickSizeForQuotePrice("ORDERBOOK", new BigDecimal("5000.00"))).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(KoreanStockTickSizePolicy.tickSizeForQuotePrice("ORDERBOOK", new BigDecimal("20000.00"))).isEqualByComparingTo(new BigDecimal("50"));
        assertThat(KoreanStockTickSizePolicy.tickSizeForQuotePrice("ORDERBOOK", new BigDecimal("50000.00"))).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(KoreanStockTickSizePolicy.tickSizeForQuotePrice("ORDERBOOK", new BigDecimal("200000.00"))).isEqualByComparingTo(new BigDecimal("500"));
        assertThat(KoreanStockTickSizePolicy.tickSizeForQuotePrice("ORDERBOOK", new BigDecimal("500000.00"))).isEqualByComparingTo(new BigDecimal("1000"));
    }

    @Test
    void moveByTicks_stepsAcrossPriceBandBoundary() {
        assertThat(AutoMarketPricePolicy.moveByTicks("ORDERBOOK", new BigDecimal("1999.00"), 1)).isEqualByComparingTo(new BigDecimal("2000.00"));
        assertThat(AutoMarketPricePolicy.moveByTicks("ORDERBOOK", new BigDecimal("2000.00"), -1)).isEqualByComparingTo(new BigDecimal("1999.00"));
        assertThat(AutoMarketPricePolicy.moveByTicks("ORDERBOOK", new BigDecimal("4995.00"), 1)).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(AutoMarketPricePolicy.moveByTicks("ORDERBOOK", new BigDecimal("5000.00"), -1)).isEqualByComparingTo(new BigDecimal("4995.00"));
    }
}
