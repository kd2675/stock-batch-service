package stock.batch.service.batch.marketdata.reader;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.marketdata.model.MarketPriceRefreshTarget;

@Component
@RequiredArgsConstructor
public class MarketPriceRefreshTargetReader {

    private final JdbcTemplate jdbcTemplate;

    public List<MarketPriceRefreshTarget> readTargets() {
        return jdbcTemplate.query(
                """
                select watched.symbol, p.current_price, watched.reference_price
                from (
                    select symbol, max(reference_price) as reference_price
                    from (
                        select symbol, cast(null as decimal(19, 2)) as reference_price
                        from stock_instrument
                        where enabled = true
                        union all
                        select symbol, limit_price as reference_price
                        from stock_order
                        where status in ('PENDING', 'PARTIALLY_FILLED')
                          and limit_price is not null
                        union all
                        select symbol, average_price as reference_price
                        from stock_holding
                        where quantity > 0
                    ) watched_raw
                    group by symbol
                ) watched
                left join stock_price p on p.symbol = watched.symbol
                order by watched.symbol asc
                """,
                (rs, rowNum) -> new MarketPriceRefreshTarget(
                        rs.getString("symbol"),
                        rs.getBigDecimal("current_price"),
                        rs.getBigDecimal("reference_price")
                )
        );
    }
}
