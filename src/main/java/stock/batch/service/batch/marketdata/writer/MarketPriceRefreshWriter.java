package stock.batch.service.batch.marketdata.writer;

import java.math.BigDecimal;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.marketdata.model.MarketPriceRefreshCommand;
import stock.batch.service.marketdata.provider.MarketPriceQuote;

@Component
@RequiredArgsConstructor
public class MarketPriceRefreshWriter {

    private final JdbcTemplate jdbcTemplate;

    public void write(MarketPriceRefreshCommand command) {
        MarketPriceQuote quote = command.quote();
        upsertPrice(quote, command.target().currentPrice());
        insertPriceTick(quote);
    }

    private void upsertPrice(MarketPriceQuote quote, BigDecimal previousPrice) {
        int updatedRows = jdbcTemplate.update(
                "update stock_price set current_price = ?, price_time = ?, provider = ? where symbol = ?",
                quote.currentPrice(),
                quote.priceTime(),
                quote.provider(),
                quote.symbol()
        );
        if (updatedRows > 0) {
            return;
        }

        BigDecimal previousClose = previousPrice == null ? quote.currentPrice() : previousPrice;
        jdbcTemplate.update(
                """
                insert into stock_price(symbol, current_price, previous_close, price_time, provider)
                values (?, ?, ?, ?, ?)
                """,
                quote.symbol(),
                quote.currentPrice(),
                previousClose,
                quote.priceTime(),
                quote.provider()
        );
    }

    private void insertPriceTick(MarketPriceQuote quote) {
        jdbcTemplate.update(
                """
                insert into stock_price_tick(symbol, price, provider, price_time, created_at)
                values (?, ?, ?, ?, ?)
                """,
                quote.symbol(),
                quote.currentPrice(),
                quote.provider(),
                quote.priceTime(),
                quote.priceTime()
        );
    }
}
