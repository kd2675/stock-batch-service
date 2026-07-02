package stock.batch.service.batch.execution.reader;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.execution.model.VirtualPriceHoldingRow;
import stock.batch.service.batch.execution.model.VirtualPriceOrderCandidate;

@Component
public class VirtualPriceExecutionReader {

    private final JdbcClient jdbcClient;

    public VirtualPriceExecutionReader(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    public List<VirtualPriceOrderCandidate> findCandidatesForUpdate(int scanLimit) {
        return jdbcClient.sql(
                """
                select o.id, o.account_id, o.symbol, o.side, o.order_type, o.limit_price,
                       o.quantity, o.filled_quantity, o.average_fill_price, o.reserved_cash, p.current_price
                from stock_order o
                join stock_price p on p.symbol = o.symbol
                join stock_virtual_market_config c on c.symbol = o.symbol and c.enabled = true and c.market_status = 'OPEN'
                where o.status in ('PENDING', 'PARTIALLY_FILLED')
                  and o.market_type = 'VIRTUAL_PRICE'
                order by o.created_at asc
                limit ?
                for update
                """
        )
                .params(scanLimit)
                .query((rs, rowNum) -> mapOrderCandidate(rs))
                .list();
    }

    public VirtualPriceHoldingRow findHoldingForUpdate(long accountId, String symbol) {
        return jdbcClient.sql(
                """
                select id, quantity, average_price
                  from stock_holding
                 where account_id = ?
                   and symbol = ?
                 for update
                """
        )
                .params(accountId, symbol)
                .query((rs, rowNum) -> mapHoldingRow(rs))
                .optional()
                .orElse(null);
    }

    private VirtualPriceOrderCandidate mapOrderCandidate(ResultSet rs) throws SQLException {
        return new VirtualPriceOrderCandidate(
                rs.getLong("id"),
                rs.getLong("account_id"),
                rs.getString("symbol"),
                rs.getString("side"),
                rs.getString("order_type"),
                rs.getBigDecimal("limit_price"),
                rs.getLong("quantity"),
                rs.getLong("filled_quantity"),
                rs.getBigDecimal("average_fill_price"),
                rs.getBigDecimal("reserved_cash"),
                rs.getBigDecimal("current_price")
        );
    }

    private VirtualPriceHoldingRow mapHoldingRow(ResultSet rs) throws SQLException {
        return new VirtualPriceHoldingRow(
                rs.getLong("id"),
                rs.getLong("quantity"),
                rs.getBigDecimal("average_price")
        );
    }

}
