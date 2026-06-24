package stock.batch.service.batch.execution.writer;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderBookPriceWriter {

    public static final String PROVIDER = "internal-order-book";

    private final JdbcTemplate jdbcTemplate;

    public void updateLastTradePrice(String symbol, BigDecimal executionPrice, LocalDateTime executedAt) {
        int updatedRows = jdbcTemplate.update(
                """
                update stock_price
                set current_price = ?,
                    price_time = ?,
                    provider = 'internal-order-book'
                where symbol = ?
                """,
                executionPrice,
                executedAt,
                symbol
        );
        if (updatedRows == 0) {
            jdbcTemplate.update(
                    """
                    insert into stock_price(symbol, current_price, previous_close, price_time, provider)
                    values (?, ?, ?, ?, 'internal-order-book')
                    """,
                    symbol,
                    executionPrice,
                    executionPrice,
                    executedAt
            );
        }
        jdbcTemplate.update(
                """
                insert into stock_price_tick(symbol, price, provider, price_time, created_at)
                values (?, ?, 'internal-order-book', ?, ?)
                """,
                symbol,
                executionPrice,
                executedAt,
                executedAt
        );
    }
}
