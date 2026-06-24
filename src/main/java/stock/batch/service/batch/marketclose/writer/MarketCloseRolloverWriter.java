package stock.batch.service.batch.marketclose.writer;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MarketCloseRolloverWriter {

    private final JdbcTemplate jdbcTemplate;

    public int rolloverClosingPrices() {
        return jdbcTemplate.update(
                """
                update stock_price
                   set previous_close = current_price
                 where previous_close <> current_price
                """
        );
    }
}
