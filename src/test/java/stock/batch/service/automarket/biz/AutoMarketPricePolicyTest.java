package stock.batch.service.automarket.biz;

import org.junit.jupiter.api.Test;
import stock.batch.service.batch.automarket.model.AutoMarketConfig;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AutoMarketPricePolicyTest {

    @Test
    void normalizePriceWithinDailyLimit_roundsToTickAndKeepsDailyLimit() {
        AutoMarketConfig config = new AutoMarketConfig(
                "005930",
                3,
                15,
                300,
                new BigDecimal("100.00"),
                new BigDecimal("70000.00"),
                new BigDecimal("70000.00"),
                new BigDecimal("30.00"),
                null
        );

        BigDecimal upperLimit = AutoMarketPricePolicy.normalizePriceWithinDailyLimit(
                new BigDecimal("999999.00"),
                config,
                config.tickSize()
        );
        BigDecimal rounded = AutoMarketPricePolicy.normalizePriceWithinDailyLimit(
                new BigDecimal("70149.00"),
                config,
                config.tickSize()
        );

        assertThat(upperLimit).isEqualByComparingTo(new BigDecimal("91000.00"));
        assertThat(rounded).isEqualByComparingTo(new BigDecimal("70100.00"));
    }

    @Test
    void positiveOrDefault_returnsDefaultForNullAndNonPositiveValues() {
        BigDecimal fallback = new BigDecimal("100.00");

        assertThat(AutoMarketPricePolicy.positiveOrDefault(null, fallback)).isEqualByComparingTo(fallback);
        assertThat(AutoMarketPricePolicy.positiveOrDefault(BigDecimal.ZERO, fallback)).isEqualByComparingTo(fallback);
        assertThat(AutoMarketPricePolicy.positiveOrDefault(new BigDecimal("50.00"), fallback))
                .isEqualByComparingTo(new BigDecimal("50.00"));
    }
}
