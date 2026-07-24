package stock.batch.service.batch.execution.reader;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.execution.model.OrderBookHoldingRow;
import stock.batch.service.batch.execution.model.OrderBookMatchCandidate;
import stock.batch.service.batch.execution.model.OrderBookOrderRow;

@Component
public class OrderBookExecutionReader {

    private final JdbcClient jdbcClient;
    private final String lockedOrderTable;

    public OrderBookExecutionReader(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate));
        boolean mysql = isMySql(jdbcTemplate);
        this.lockedOrderTable = mysql ? "stock_order force index (primary)" : "stock_order";
    }

    public List<String> findExecutableSymbolCandidates(int limit) {
        return jdbcClient.sql(
                """
                select c.symbol
                  from stock_order_book_market_config c
                  join stock_order_book_instrument i
                    on i.symbol = c.symbol
                   and i.enabled = true
                 where c.enabled = true
                   and c.market_status = 'OPEN'
                   and exists (
                       select 1
                         from stock_order b
                        where b.symbol = c.symbol
                          and b.side = 'BUY'
                          and b.status in ('PENDING', 'PARTIALLY_FILLED')
                          and b.market_type = 'ORDER_BOOK'
                          and b.order_type in ('LIMIT', 'MARKET')
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
                                     (b.order_type = 'MARKET'
                                         and s.order_type = 'LIMIT'
                                         and s.limit_price is not null)
                                     or
                                     (b.order_type = 'LIMIT'
                                         and b.limit_price is not null
                                         and (
                                             s.order_type = 'MARKET'
                                             or (s.order_type = 'LIMIT' and s.limit_price <= b.limit_price)
                                         ))
                                 )
                          )
                   )
                 order by c.symbol asc
                limit :limit
                """
        )
                .param("limit", Math.max(1, limit))
                .query(String.class)
                .list();
    }

    public List<String> findOpenOrderBookSymbolsAfter(String afterSymbol, int limit) {
        return jdbcClient.sql(
                """
                select c.symbol
                  from stock_order_book_market_config c
                  join stock_order_book_instrument i
                    on i.symbol = c.symbol
                   and i.enabled = true
                 where c.enabled = true
                   and c.market_status = 'OPEN'
                   and c.symbol > :afterSymbol
                 order by c.symbol asc
                limit :limit
                """
        )
                .param("afterSymbol", afterSymbol == null ? "" : afterSymbol)
                .param("limit", Math.max(1, limit))
                .query(String.class)
                .list();
    }

    public Optional<OrderBookMatchCandidate> findBestMatchCandidate(String symbol, int scanLimit) {
        return jdbcClient.sql(
                """
                with buy_scan as (
                    select id, account_id, order_type, limit_price, created_at
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
                              created_at asc,
                              id asc
                     limit :scanLimit
                ),
                sell_scan as (
                    select id, account_id, order_type, limit_price, created_at
                      from stock_order
                     where symbol = :symbol
                       and side = 'SELL'
                       and market_type = 'ORDER_BOOK'
                       and order_type in ('LIMIT', 'MARKET')
                       and status in ('PENDING', 'PARTIALLY_FILLED')
                       and quantity > filled_quantity
                       and (order_type = 'MARKET' or limit_price is not null)
                     order by case when order_type = 'MARKET' then 1 else 0 end desc,
                              limit_price asc,
                              created_at asc,
                              id asc
                     limit :scanLimit
                ),
                best_buy as (
                    select id, account_id, order_type, limit_price, created_at
                      from buy_scan
                     order by case when order_type = 'MARKET' then 1 else 0 end desc,
                              limit_price desc,
                              created_at asc,
                              id asc
                     limit 1
                ),
                best_sell as (
                    select id, account_id, order_type, limit_price, created_at
                      from sell_scan
                     order by case when order_type = 'MARKET' then 1 else 0 end desc,
                              limit_price asc,
                              created_at asc,
                              id asc
                     limit 1
                ),
                alternate_buy_account as (
                    select id, account_id, order_type, limit_price, created_at
                      from stock_order
                     where symbol = :symbol
                       and side = 'BUY'
                       and market_type = 'ORDER_BOOK'
                       and order_type in ('LIMIT', 'MARKET')
                       and status in ('PENDING', 'PARTIALLY_FILLED')
                       and quantity > filled_quantity
                       and (order_type = 'MARKET' or limit_price is not null)
                       and exists (
                           select 1
                             from best_buy b
                             join best_sell s on s.account_id = b.account_id
                       )
                       and account_id <> (select account_id from best_sell)
                     order by case when order_type = 'MARKET' then 1 else 0 end desc,
                              limit_price desc,
                              created_at asc,
                              id asc
                     limit 1
                ),
                alternate_sell_account as (
                    select id, account_id, order_type, limit_price, created_at
                      from stock_order
                     where symbol = :symbol
                       and side = 'SELL'
                       and market_type = 'ORDER_BOOK'
                       and order_type in ('LIMIT', 'MARKET')
                       and status in ('PENDING', 'PARTIALLY_FILLED')
                       and quantity > filled_quantity
                       and (order_type = 'MARKET' or limit_price is not null)
                       and exists (
                           select 1
                             from best_buy b
                             join best_sell s on s.account_id = b.account_id
                       )
                       and account_id <> (select account_id from best_buy)
                     order by case when order_type = 'MARKET' then 1 else 0 end desc,
                              limit_price asc,
                              created_at asc,
                              id asc
                     limit 1
                ),
                fallback_limit_buy as (
                    select id, account_id, order_type, limit_price, created_at
                      from stock_order
                     where symbol = :symbol
                       and side = 'BUY'
                       and market_type = 'ORDER_BOOK'
                       and order_type = 'LIMIT'
                       and status in ('PENDING', 'PARTIALLY_FILLED')
                       and quantity > filled_quantity
                       and limit_price is not null
                       and exists (select 1 from best_sell where order_type = 'MARKET')
                       and account_id <> (select account_id from best_sell)
                     order by limit_price desc, created_at asc, id asc
                     limit 1
                ),
                fallback_limit_sell as (
                    select id, account_id, order_type, limit_price, created_at
                      from stock_order
                     where symbol = :symbol
                       and side = 'SELL'
                       and market_type = 'ORDER_BOOK'
                       and order_type = 'LIMIT'
                       and status in ('PENDING', 'PARTIALLY_FILLED')
                       and quantity > filled_quantity
                       and limit_price is not null
                       and exists (select 1 from best_buy where order_type = 'MARKET')
                       and account_id <> (select account_id from best_buy)
                     order by limit_price asc, created_at asc, id asc
                     limit 1
                ),
                buy_candidates as (
                    select id, account_id, order_type, limit_price, created_at from buy_scan
                    union
                    select id, account_id, order_type, limit_price, created_at from alternate_buy_account
                    union
                    select id, account_id, order_type, limit_price, created_at from fallback_limit_buy
                ),
                sell_candidates as (
                    select id, account_id, order_type, limit_price, created_at from sell_scan
                    union
                    select id, account_id, order_type, limit_price, created_at from alternate_sell_account
                    union
                    select id, account_id, order_type, limit_price, created_at from fallback_limit_sell
                )
                select b.id as buy_order_id,
                       b.account_id as buy_account_id,
                       s.id as sell_order_id,
                       s.account_id as sell_account_id
                  from buy_candidates b
                  join sell_candidates s
                    on s.account_id <> b.account_id
                   and (
                       (b.order_type = 'MARKET'
                           and s.order_type = 'LIMIT'
                           and s.limit_price is not null)
                       or
                       (b.order_type = 'LIMIT'
                           and b.limit_price is not null
                           and (
                               s.order_type = 'MARKET'
                               or (s.order_type = 'LIMIT' and s.limit_price <= b.limit_price)
                           ))
                   )
                 order by case when b.order_type = 'MARKET' then 1 else 0 end desc,
                          b.limit_price desc,
                          b.created_at asc,
                          b.id asc,
                          case when s.order_type = 'MARKET' then 1 else 0 end desc,
                          s.limit_price asc,
                          s.created_at asc,
                          s.id asc
                 limit 1
                """
        )
                .param("symbol", symbol)
                .param("scanLimit", scanLimit)
                .query((rs, rowNum) -> new OrderBookMatchCandidate(
                        rs.getLong("buy_order_id"),
                        rs.getLong("buy_account_id"),
                        rs.getLong("sell_order_id"),
                        rs.getLong("sell_account_id")
                ))
                .optional();
    }

    public List<OrderBookOrderRow> findMatchOrdersForUpdate(OrderBookMatchCandidate candidate) {
        long firstOrderId = Math.min(candidate.buyOrderId(), candidate.sellOrderId());
        long secondOrderId = Math.max(candidate.buyOrderId(), candidate.sellOrderId());
        // Keep one remote round trip without using an IN-list locking range. On InnoDB under
        // REPEATABLE READ, `id in (?, ?) for update` can next-key lock the gap after the second
        // order and block an unrelated auto-increment INSERT. Joining two exact PK values keeps
        // both record locks ordered while leaving the adjacent insert gap open.
        return jdbcClient.sql(
                """
                select stock_order.id,
                       stock_order.account_id,
                       stock_order.symbol,
                       stock_order.side,
                       stock_order.order_type,
                       stock_order.limit_price,
                       stock_order.quantity,
                       stock_order.filled_quantity,
                       stock_order.average_fill_price,
                       stock_order.reserved_cash,
                       stock_order.created_at,
                       stock_order.funding_budget_type
                  from %s
                  join (
                      select cast(:firstOrderId as decimal(19, 0)) as id
                      union all
                      select cast(:secondOrderId as decimal(19, 0)) as id
                  ) selected_order
                    on selected_order.id = stock_order.id
                 where stock_order.market_type = 'ORDER_BOOK'
                   and stock_order.order_type in ('LIMIT', 'MARKET')
                   and stock_order.status in ('PENDING', 'PARTIALLY_FILLED')
                   and stock_order.quantity > stock_order.filled_quantity
                   and (stock_order.order_type = 'MARKET' or stock_order.limit_price is not null)
                 order by stock_order.id asc
                for update
                """.formatted(lockedOrderTable)
        )
                .param("firstOrderId", firstOrderId)
                .param("secondOrderId", secondOrderId)
                .query((rs, rowNum) -> mapOrderRow(rs))
                .list();
    }

    private boolean isMySql(JdbcTemplate jdbcTemplate) {
        String productName = jdbcTemplate.execute(
                (ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName()
        );
        return productName != null && productName.toLowerCase(Locale.ROOT).contains("mysql");
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
                rs.getBigDecimal("reserved_cash"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getString("funding_budget_type")
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
