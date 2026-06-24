package stock.batch.service.marketclose.biz;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class MarketCloseRolloverServiceTest {

    @Autowired
    private MarketCloseRolloverService marketCloseRolloverService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from stock_price_tick");
        jdbcTemplate.update("delete from stock_price");
    }

    @Test
    void rolloverClosingPrices_copiesCurrentPriceToPreviousCloseOnlyForChangedPrices() {
        insertPrice("005930", "73500.00", "70000.00", "internal-order-book");
        insertPrice("000660", "120000.00", "120000.00", "kis");

        int processedCount = marketCloseRolloverService.rolloverClosingPrices();

        assertThat(processedCount).isEqualTo(1);
        assertThat(queryDecimal("select previous_close from stock_price where symbol = '005930'"))
                .isEqualByComparingTo(new BigDecimal("73500.00"));
        assertThat(queryDecimal("select current_price from stock_price where symbol = '005930'"))
                .isEqualByComparingTo(new BigDecimal("73500.00"));
        assertThat(queryString("select provider from stock_price where symbol = '005930'"))
                .isEqualTo("internal-order-book");
        assertThat(queryDecimal("select previous_close from stock_price where symbol = '000660'"))
                .isEqualByComparingTo(new BigDecimal("120000.00"));
    }

    @Test
    void rolloverClosingPrices_afterAlreadyRolledOver_isIdempotent() {
        insertPrice("005930", "73500.00", "70000.00", "internal-order-book");
        marketCloseRolloverService.rolloverClosingPrices();

        int processedCount = marketCloseRolloverService.rolloverClosingPrices();

        assertThat(processedCount).isZero();
    }

    private void insertPrice(String symbol, String currentPrice, String previousClose, String provider) {
        jdbcTemplate.update(
                """
                insert into stock_price(symbol, current_price, previous_close, price_time, provider)
                values (?, ?, ?, ?, ?)
                """,
                symbol,
                new BigDecimal(currentPrice),
                new BigDecimal(previousClose),
                LocalDateTime.now(),
                provider
        );
    }

    private BigDecimal queryDecimal(String sql) {
        return jdbcTemplate.queryForObject(sql, BigDecimal.class);
    }

    private String queryString(String sql) {
        return jdbcTemplate.queryForObject(sql, String.class);
    }
}
