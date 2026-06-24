package stock.batch.service.batch.corporateaction.writer;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CorporateActionPriceWriter {

    private final JdbcTemplate jdbcTemplate;

    public void upsertPrice(String symbol, BigDecimal price, String provider, LocalDateTime now) {
        int updatedRows = jdbcTemplate.update(
                """
                update stock_price
                   set current_price = ?,
                       previous_close = ?,
                       price_time = ?,
                       provider = ?
                 where symbol = ?
                """,
                price,
                price,
                now,
                provider,
                symbol
        );
        if (updatedRows > 0) {
            return;
        }
        jdbcTemplate.update(
                """
                insert into stock_price(symbol, current_price, previous_close, price_time, provider)
                values (?, ?, ?, ?, ?)
                """,
                symbol,
                price,
                price,
                now,
                provider
        );
    }

    public void insertPriceTick(String symbol, BigDecimal price, String provider, LocalDateTime now) {
        jdbcTemplate.update(
                """
                insert into stock_price_tick(symbol, price, provider, price_time, created_at)
                values (?, ?, ?, ?, ?)
                """,
                symbol,
                price,
                provider,
                now,
                now
        );
    }

    public void insertCurrentPriceTick(String symbol, String provider, LocalDateTime now) {
        jdbcTemplate.update(
                """
                insert into stock_price_tick(symbol, price, provider, price_time, created_at)
                select symbol, current_price, ?, ?, ?
                  from stock_price
                 where symbol = ?
                """,
                provider,
                now,
                now,
                symbol
        );
    }
}
