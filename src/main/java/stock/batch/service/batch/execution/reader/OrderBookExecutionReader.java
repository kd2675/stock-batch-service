package stock.batch.service.batch.execution.reader;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.execution.model.OrderBookHoldingRow;
import stock.batch.service.batch.execution.model.OrderBookOrderRow;

@Component
public class OrderBookExecutionReader {

    private final JdbcClient jdbcClient;
    private final String lockClause;

    public OrderBookExecutionReader(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate));
        this.lockClause = resolveLockClause(jdbcTemplate);
    }

    public List<String> findExecutableSymbols() {
        return findExecutableSymbolCandidates(Integer.MAX_VALUE);
    }

    public List<String> findExecutableSymbolCandidates(int limit) {
        return jdbcClient.sql(
                """
                select distinct o.symbol
                from stock_order o
                join stock_order_book_market_config c on c.symbol = o.symbol and c.enabled = true and c.market_status = 'OPEN'
                join stock_order_book_instrument i on i.symbol = o.symbol and i.enabled = true
                where o.status in ('PENDING', 'PARTIALLY_FILLED')
                  and o.market_type = 'ORDER_BOOK'
                  and o.order_type in ('LIMIT', 'MARKET')
                order by o.symbol asc
                limit :limit
                """
        )
                .param("limit", Math.max(1, limit))
                .query(String.class)
                .list();
    }

    public boolean hasExecutablePair(String symbol) {
        Long executableOrderId = jdbcClient.sql(
                """
                select b.id
                  from stock_order b
                  join stock_order_book_market_config c on c.symbol = b.symbol and c.enabled = true and c.market_status = 'OPEN'
                  join stock_order_book_instrument i on i.symbol = b.symbol and i.enabled = true
                 where b.symbol = :symbol
                   and b.side = 'BUY'
                   and b.market_type = 'ORDER_BOOK'
                   and b.order_type in ('LIMIT', 'MARKET')
                   and b.status in ('PENDING', 'PARTIALLY_FILLED')
                   and b.quantity > b.filled_quantity
                   and (b.order_type = 'MARKET' or b.limit_price is not null)
                   and exists (
                       select 1
                         from stock_order s
                        where s.symbol = b.symbol
                          and s.side = 'SELL'
                          and s.market_type = 'ORDER_BOOK'
                          and s.order_type in ('LIMIT', 'MARKET')
                          and s.status in ('PENDING', 'PARTIALLY_FILLED')
                          and s.quantity > s.filled_quantity
                          and s.account_id <> b.account_id
                          and (
                              (b.order_type = 'MARKET' and s.order_type = 'LIMIT' and s.limit_price is not null)
                              or
                              (b.order_type = 'LIMIT' and b.limit_price is not null and (
                                  (s.order_type = 'LIMIT' and s.limit_price <= b.limit_price)
                                  or s.order_type = 'MARKET'
                              ))
                          )
                   )
                 limit 1
                """
        )
                .param("symbol", symbol)
                .query(Long.class)
                .optional()
                .orElse(null);
        return executableOrderId != null;
    }

    public List<Long> findBestBuyCandidateIds(String symbol, int scanLimit) {
        return jdbcClient.sql(
                """
                select id
                from stock_order
                where symbol = :symbol
                  and side = 'BUY'
                  and market_type = 'ORDER_BOOK'
                  and order_type in ('LIMIT', 'MARKET')
                  and status in ('PENDING', 'PARTIALLY_FILLED')
                  and quantity > filled_quantity
                  and (order_type = 'MARKET' or limit_price is not null)
                order by case when order_type = 'MARKET' then 1 else 0 end desc,
                         limit_price desc,
                         created_at asc
                limit :scanLimit
                """
        )
                .param("symbol", symbol)
                .param("scanLimit", scanLimit)
                .query(Long.class)
                .list();
    }

    public OrderBookOrderRow findBuyCandidateForUpdate(long orderId) {
        return jdbcClient.sql(
                """
                select id, account_id, symbol, side, order_type, limit_price, quantity, filled_quantity, average_fill_price, reserved_cash
                from stock_order
                where id = :orderId
                  and side = 'BUY'
                  and market_type = 'ORDER_BOOK'
                  and order_type in ('LIMIT', 'MARKET')
                  and status in ('PENDING', 'PARTIALLY_FILLED')
                  and quantity > filled_quantity
                  and (order_type = 'MARKET' or limit_price is not null)
                %s
                """.formatted(lockClause)
        )
                .param("orderId", orderId)
                .query((rs, rowNum) -> mapOrderRow(rs))
                .optional()
                .orElse(null);
    }

    public OrderBookOrderRow findBestSell(String symbol, OrderBookOrderRow buyOrder) {
        if ("MARKET".equals(buyOrder.orderType())) {
            return findBestLimitSellForMarketBuy(symbol, buyOrder.accountId());
        }
        return findBestSellForLimitBuy(symbol, buyOrder.accountId(), buyOrder.limitPrice());
    }

    private OrderBookOrderRow findBestLimitSellForMarketBuy(String symbol, long buyAccountId) {
        return jdbcClient.sql(
                """
                select id, account_id, symbol, side, order_type, limit_price, quantity, filled_quantity, average_fill_price, reserved_cash
                from stock_order
                where symbol = :symbol
                  and side = 'SELL'
                  and market_type = 'ORDER_BOOK'
                  and order_type = 'LIMIT'
                  and status in ('PENDING', 'PARTIALLY_FILLED')
                  and quantity > filled_quantity
                  and account_id <> :buyAccountId
                  and limit_price is not null
                order by limit_price asc, created_at asc
                limit 1
                %s
                """.formatted(lockClause)
        )
                .param("symbol", symbol)
                .param("buyAccountId", buyAccountId)
                .query((rs, rowNum) -> mapOrderRow(rs))
                .optional()
                .orElse(null);
    }

    private OrderBookOrderRow findBestSellForLimitBuy(String symbol, long buyAccountId, BigDecimal buyLimitPrice) {
        return jdbcClient.sql(
                """
                select id, account_id, symbol, side, order_type, limit_price, quantity, filled_quantity, average_fill_price, reserved_cash
                from stock_order
                where symbol = :symbol
                  and side = 'SELL'
                  and market_type = 'ORDER_BOOK'
                  and order_type in ('LIMIT', 'MARKET')
                  and status in ('PENDING', 'PARTIALLY_FILLED')
                  and quantity > filled_quantity
                  and account_id <> :buyAccountId
                  and ((order_type = 'LIMIT' and limit_price <= :buyLimitPrice) or order_type = 'MARKET')
                order by case when order_type = 'MARKET' then 1 else 0 end desc,
                         limit_price asc,
                         created_at asc
                limit 1
                %s
                """.formatted(lockClause)
        )
                .param("symbol", symbol)
                .param("buyAccountId", buyAccountId)
                .param("buyLimitPrice", buyLimitPrice)
                .query((rs, rowNum) -> mapOrderRow(rs))
                .optional()
                .orElse(null);
    }

    private String resolveLockClause(JdbcTemplate jdbcTemplate) {
        String productName = jdbcTemplate.execute(
                (ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName()
        );
        if (productName != null && productName.toLowerCase(Locale.ROOT).contains("mysql")) {
            return "for update skip locked";
        }
        return "for update";
    }

    public boolean hasEnoughCash(long accountId, BigDecimal shortfall) {
        Long count = jdbcClient.sql(
                """
                select count(*)
                  from stock_account
                 where id = :accountId
                   and cash_balance >= :shortfall
                """
        )
                .param("accountId", accountId)
                .param("shortfall", shortfall)
                .query(Long.class)
                .single();
        return count > 0;
    }

    public OrderBookHoldingRow findHoldingForUpdate(long accountId, String symbol) {
        return jdbcClient.sql(
                """
                select id, quantity, reserved_quantity, average_price
                  from stock_holding
                 where account_id = :accountId
                   and symbol = :symbol
                 for update
                """
        )
                .param("accountId", accountId)
                .param("symbol", symbol)
                .query((rs, rowNum) -> mapHoldingRow(rs))
                .optional()
                .orElse(null);
    }

    private OrderBookOrderRow mapOrderRow(ResultSet rs) throws SQLException {
        return new OrderBookOrderRow(
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
        );
    }

    private OrderBookHoldingRow mapHoldingRow(ResultSet rs) throws SQLException {
        return new OrderBookHoldingRow(
                rs.getLong("id"),
                rs.getLong("quantity"),
                rs.getLong("reserved_quantity"),
                rs.getBigDecimal("average_price")
        );
    }

}
