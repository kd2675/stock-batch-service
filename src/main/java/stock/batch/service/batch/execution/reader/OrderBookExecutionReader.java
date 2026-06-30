package stock.batch.service.batch.execution.reader;

import java.math.BigDecimal;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.execution.model.OrderBookHoldingRow;
import stock.batch.service.batch.execution.model.OrderBookOrderRow;

@Component
@RequiredArgsConstructor
public class OrderBookExecutionReader {

    private final JdbcTemplate jdbcTemplate;

    public List<String> findExecutableSymbols() {
        return jdbcTemplate.queryForList(
                """
                select distinct o.symbol
                from stock_order o
                join stock_order_book_market_config c on c.symbol = o.symbol and c.enabled = true and c.market_status = 'OPEN'
                join stock_order_book_instrument i on i.symbol = o.symbol and i.enabled = true
                where o.status in ('PENDING', 'PARTIALLY_FILLED')
                  and o.market_type = 'ORDER_BOOK'
                  and o.order_type in ('LIMIT', 'MARKET')
                order by o.symbol asc
                """,
                String.class
        );
    }

    public List<OrderBookOrderRow> findBestBuyCandidates(String symbol, int scanLimit) {
        return jdbcTemplate.query(
                """
                select id, account_id, symbol, side, order_type, limit_price, quantity, filled_quantity, average_fill_price, reserved_cash
                from stock_order
                where symbol = ?
                  and side = 'BUY'
                  and market_type = 'ORDER_BOOK'
                  and order_type in ('LIMIT', 'MARKET')
                  and status in ('PENDING', 'PARTIALLY_FILLED')
                  and (order_type = 'MARKET' or limit_price is not null)
                order by case when order_type = 'MARKET' then 1 else 0 end desc,
                         limit_price desc,
                         created_at asc
                limit ?
                for update
                """,
                (rs, rowNum) -> new OrderBookOrderRow(
                        rs.getLong("id"),
                        rs.getLong("account_id"),
                        rs.getString("symbol"),
                        rs.getString("side"),
                        rs.getString("order_type"),
                        rs.getBigDecimal("limit_price"),
                        rs.getLong("quantity"),
                        rs.getLong("filled_quantity"),
                        rs.getBigDecimal("average_fill_price"),
                        rs.getBigDecimal("reserved_cash")
                ),
                symbol,
                scanLimit
        );
    }

    public OrderBookOrderRow findBestSell(String symbol, OrderBookOrderRow buyOrder) {
        String pricePredicate = "MARKET".equals(buyOrder.orderType())
                ? "and order_type = 'LIMIT' and limit_price is not null"
                : "and ((order_type = 'LIMIT' and limit_price <= ?) or order_type = 'MARKET')";
        String orderBy = "MARKET".equals(buyOrder.orderType())
                ? "order by limit_price asc, created_at asc"
                : "order by case when order_type = 'MARKET' then 1 else 0 end desc, limit_price asc, created_at asc";
        Object[] params = "MARKET".equals(buyOrder.orderType())
                ? new Object[]{symbol, buyOrder.accountId()}
                : new Object[]{symbol, buyOrder.accountId(), buyOrder.limitPrice()};
        List<OrderBookOrderRow> rows = jdbcTemplate.query(
                String.format(
                        """
                        select id, account_id, symbol, side, order_type, limit_price, quantity, filled_quantity, average_fill_price, reserved_cash
                        from stock_order
                        where symbol = ?
                          and side = 'SELL'
                          and market_type = 'ORDER_BOOK'
                          and order_type in ('LIMIT', 'MARKET')
                          and status in ('PENDING', 'PARTIALLY_FILLED')
                          and account_id <> ?
                          %s
                        %s
                        limit 1
                        for update
                        """,
                        pricePredicate,
                        orderBy
                ),
                (rs, rowNum) -> new OrderBookOrderRow(
                        rs.getLong("id"),
                        rs.getLong("account_id"),
                        rs.getString("symbol"),
                        rs.getString("side"),
                        rs.getString("order_type"),
                        rs.getBigDecimal("limit_price"),
                        rs.getLong("quantity"),
                        rs.getLong("filled_quantity"),
                        rs.getBigDecimal("average_fill_price"),
                        rs.getBigDecimal("reserved_cash")
                ),
                params
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean hasEnoughCash(long accountId, BigDecimal shortfall) {
        Long count = jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_account
                 where id = ?
                   and cash_balance >= ?
                """,
                Long.class,
                accountId,
                shortfall
        );
        return count != null && count > 0;
    }

    public OrderBookHoldingRow findHoldingForUpdate(long accountId, String symbol) {
        List<OrderBookHoldingRow> rows = jdbcTemplate.query(
                """
                select id, quantity, reserved_quantity, average_price
                  from stock_holding
                 where account_id = ?
                   and symbol = ?
                 for update
                """,
                (rs, rowNum) -> new OrderBookHoldingRow(
                        rs.getLong("id"),
                        rs.getLong("quantity"),
                        rs.getLong("reserved_quantity"),
                        rs.getBigDecimal("average_price")
                ),
                accountId,
                symbol
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

}
