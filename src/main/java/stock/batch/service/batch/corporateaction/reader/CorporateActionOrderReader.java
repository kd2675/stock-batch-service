package stock.batch.service.batch.corporateaction.reader;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.corporateaction.model.DelistingOrderRow;

@Component
public class CorporateActionOrderReader {

    private final JdbcClient jdbcClient;

    public CorporateActionOrderReader(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate));
    }

    public List<DelistingOrderRow> findOpenOrderBookOrdersForUpdate(String symbol) {
        return jdbcClient.sql(
                """
                select id,
                       account_id,
                       symbol,
                       side,
                       quantity - filled_quantity as remaining_quantity,
                       reserved_cash
                  from stock_order
                 where symbol = :symbol
                   and market_type = 'ORDER_BOOK'
                   and status in ('PENDING', 'PARTIALLY_FILLED')
                 order by created_at asc, id asc
                 for update
                """
        )
                .param("symbol", symbol)
                .query((rs, rowNum) -> mapDelistingOrderRow(rs))
                .list();
    }

    public Set<String> findSymbolsWithOpenOrderBookOrders(List<String> symbols) {
        List<String> normalizedSymbols = symbols.stream()
                .filter(symbol -> symbol != null && !symbol.isBlank())
                .distinct()
                .sorted()
                .toList();
        if (normalizedSymbols.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbcClient.sql(
                """
                select distinct symbol
                  from stock_order
                 where symbol in (:symbols)
                   and market_type = 'ORDER_BOOK'
                   and status in ('PENDING', 'PARTIALLY_FILLED')
                """
        )
                .param("symbols", normalizedSymbols)
                .query(String.class)
                .list());
    }

    private DelistingOrderRow mapDelistingOrderRow(ResultSet rs) throws SQLException {
        return new DelistingOrderRow(
                rs.getLong("id"),
                rs.getLong("account_id"),
                rs.getString("symbol"),
                rs.getString("side"),
                rs.getLong("remaining_quantity"),
                rs.getBigDecimal("reserved_cash")
        );
    }
}
