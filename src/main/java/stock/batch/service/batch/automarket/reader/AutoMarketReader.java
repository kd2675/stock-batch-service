package stock.batch.service.batch.automarket.reader;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoOrder;
import stock.batch.service.batch.automarket.model.AutoParticipant;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;
import stock.batch.service.batch.automarket.model.ListingAutoAccountConfig;

@Component
@RequiredArgsConstructor
public class AutoMarketReader {

    private static final BigDecimal DEFAULT_TICK_SIZE = BigDecimal.valueOf(100);

    private final JdbcTemplate jdbcTemplate;

    public List<AutoParticipant> findEnabledParticipants() {
        return jdbcTemplate.query(
                """
                select user_key,
                       display_name,
                       profile_type
                from stock_auto_participant
                where enabled = true
                  and withdrawn_at is null
                order by user_key asc
                """,
                (rs, rowNum) -> new AutoParticipant(
                        rs.getString("user_key"),
                        rs.getString("display_name"),
                        AutoParticipantProfileType.parseOrDefault(rs.getString("profile_type"))
                )
        );
    }

    public boolean accountExists(String userKey) {
        Integer exists = jdbcTemplate.queryForObject(
                "select count(*) from stock_account where user_key = ?",
                Integer.class,
                userKey
        );
        return exists != null && exists > 0;
    }

    public List<AutoMarketConfig> findEnabledConfigs() {
        return jdbcTemplate.query(
                """
                select c.symbol,
                       c.intensity,
                       c.max_order_quantity,
                       c.order_ttl_seconds,
                       i.tradable_shares,
                       i.tick_size,
                       p.current_price,
                       p.previous_close,
                       r.score as report_score
                from stock_auto_market_config c
                join stock_order_book_instrument i on i.symbol = c.symbol and i.enabled = true
                join stock_order_book_market_config m on m.symbol = c.symbol and m.enabled = true and m.market_status = 'OPEN'
                join stock_price p on p.symbol = c.symbol
                left join stock_instrument_report_event r
                  on r.id = (
                      select re.id
                      from stock_instrument_report_event re
                      where re.symbol = c.symbol
                      order by re.created_at desc, re.id desc
                      limit 1
                  )
                 and r.event_type <> 'DELETE'
                where c.enabled = true
                order by c.symbol asc
                """,
                (rs, rowNum) -> mapConfig(rs)
        );
    }

    public List<AutoParticipantStrategy> findEnabledParticipantStrategies(AutoMarketConfig config) {
        return jdbcTemplate.query(
                """
                select a.id as account_id,
                       coalesce(ps.intensity, ?) as intensity,
                       p.profile_type
                from stock_auto_participant p
                join stock_account a on a.user_key = p.user_key
                left join stock_auto_participant_symbol_config ps
                  on ps.user_key = p.user_key
                 and ps.symbol = ?
                where p.enabled = true
                  and p.withdrawn_at is null
                  and coalesce(ps.enabled, true) = true
                order by p.user_key asc
                """,
                (rs, rowNum) -> new AutoParticipantStrategy(
                        rs.getLong("account_id"),
                        clamp(rs.getInt("intensity"), 1, 10),
                        AutoParticipantProfileType.parseOrDefault(rs.getString("profile_type"))
                ),
                config.intensity(),
                config.symbol()
        );
    }

    public List<ListingAutoAccountConfig> findEnabledListingAutoAccountConfigs(AutoMarketConfig config) {
        return jdbcTemplate.query(
                """
                select c.symbol,
                       a.id as account_id,
                       c.user_key,
                       c.position_side,
                       c.max_order_quantity,
                       c.order_ttl_seconds,
                       c.price_offset_ticks,
                       i.tick_size,
                       p.current_price
                from stock_listing_auto_account_config c
                join stock_account a on a.user_key = c.user_key and a.status = 'ACTIVE'
                join stock_order_book_instrument i on i.symbol = c.symbol and i.enabled = true
                join stock_order_book_market_config m on m.symbol = c.symbol and m.enabled = true and m.market_status = 'OPEN'
                join stock_price p on p.symbol = c.symbol
                where c.enabled = true
                  and c.symbol = ?
                order by c.symbol asc
                """,
                (rs, rowNum) -> new ListingAutoAccountConfig(
                        normalizeSymbol(rs.getString("symbol")),
                        rs.getLong("account_id"),
                        rs.getString("user_key"),
                        rs.getString("position_side"),
                        Math.max(1, rs.getInt("max_order_quantity")),
                        Math.max(1, rs.getInt("order_ttl_seconds")),
                        Math.max(0, rs.getInt("price_offset_ticks")),
                        positiveOrDefault(rs.getBigDecimal("tick_size"), DEFAULT_TICK_SIZE),
                        rs.getBigDecimal("current_price")
                ),
                config.symbol()
        );
    }

    public List<AutoOrder> findExpiredAutoOrders(AutoMarketConfig config, LocalDateTime threshold) {
        return jdbcTemplate.query(
                """
                select o.id, o.account_id, o.symbol, o.side, o.quantity, o.filled_quantity, o.reserved_cash
                from stock_order o
                join stock_account a on a.id = o.account_id
                join stock_auto_participant p on p.user_key = a.user_key
                where o.symbol = ?
                  and o.status in ('PENDING', 'PARTIALLY_FILLED')
                  and o.market_type = 'ORDER_BOOK'
                  and o.created_at < ?
                order by o.created_at asc
                limit 200
                for update
                """,
                (rs, rowNum) -> new AutoOrder(
                        rs.getLong("id"),
                        rs.getLong("account_id"),
                        rs.getString("symbol"),
                        rs.getString("side"),
                        rs.getLong("quantity"),
                        rs.getLong("filled_quantity"),
                        rs.getBigDecimal("reserved_cash")
                ),
                config.symbol(),
                threshold
        );
    }

    public List<AutoOrder> findExpiredListingAutoOrders(ListingAutoAccountConfig config, LocalDateTime threshold) {
        return jdbcTemplate.query(
                """
                select o.id, o.account_id, o.symbol, o.side, o.quantity, o.filled_quantity, o.reserved_cash
                from stock_order o
                where o.symbol = ?
                  and o.account_id = ?
                  and o.status in ('PENDING', 'PARTIALLY_FILLED')
                  and o.market_type = 'ORDER_BOOK'
                  and o.created_at < ?
                order by o.created_at asc
                limit 200
                for update
                """,
                (rs, rowNum) -> new AutoOrder(
                        rs.getLong("id"),
                        rs.getLong("account_id"),
                        rs.getString("symbol"),
                        rs.getString("side"),
                        rs.getLong("quantity"),
                        rs.getLong("filled_quantity"),
                        rs.getBigDecimal("reserved_cash")
                ),
                config.symbol(),
                config.accountId(),
                threshold
        );
    }

    public BigDecimal findBestPrice(String symbol, String side) {
        String orderBy = "BUY".equals(side) ? "desc" : "asc";
        List<BigDecimal> prices = jdbcTemplate.queryForList(
                String.format(
                        """
                        select limit_price
                        from stock_order
                        where symbol = ?
                          and side = ?
                          and market_type = 'ORDER_BOOK'
                          and order_type = 'LIMIT'
                          and status in ('PENDING', 'PARTIALLY_FILLED')
                          and limit_price is not null
                          and quantity > filled_quantity
                        order by limit_price %s, created_at asc
                        limit 1
                        """,
                        orderBy
                ),
                BigDecimal.class,
                symbol,
                side
        );
        return prices.isEmpty() ? null : prices.get(0);
    }

    public long getAvailableQuantity(long accountId, String symbol) {
        Long quantity = jdbcTemplate.queryForObject(
                """
                select coalesce(max(quantity - reserved_quantity), 0)
                from stock_holding
                where account_id = ?
                  and symbol = ?
                """,
                Long.class,
                accountId,
                symbol
        );
        return quantity == null ? 0L : quantity;
    }

    public BigDecimal getCashBalance(long accountId) {
        BigDecimal cash = jdbcTemplate.queryForObject(
                "select coalesce(max(cash_balance), 0) from stock_account where id = ?",
                BigDecimal.class,
                accountId
        );
        return cash == null ? BigDecimal.ZERO : cash;
    }

    public BigDecimal getAveragePrice(long accountId, String symbol) {
        BigDecimal averagePrice = jdbcTemplate.queryForObject(
                """
                select coalesce(max(average_price), 0)
                from stock_holding
                where account_id = ?
                  and symbol = ?
                  and quantity > 0
                """,
                BigDecimal.class,
                accountId,
                symbol
        );
        return averagePrice == null ? BigDecimal.ZERO : averagePrice;
    }

    public long getOpenOrderQuantity(String symbol, String side) {
        Long quantity = jdbcTemplate.queryForObject(
                """
                select coalesce(sum(quantity - filled_quantity), 0)
                from stock_order
                where symbol = ?
                  and side = ?
                  and market_type = 'ORDER_BOOK'
                  and status in ('PENDING', 'PARTIALLY_FILLED')
                  and quantity > filled_quantity
                """,
                Long.class,
                symbol,
                side
        );
        return quantity == null ? 0L : Math.max(0L, quantity);
    }

    public long getOpenOrderQuantity(long accountId, String symbol, String side) {
        Long quantity = jdbcTemplate.queryForObject(
                """
                select coalesce(sum(quantity - filled_quantity), 0)
                from stock_order
                where account_id = ?
                  and symbol = ?
                  and side = ?
                  and market_type = 'ORDER_BOOK'
                  and status in ('PENDING', 'PARTIALLY_FILLED')
                  and quantity > filled_quantity
                """,
                Long.class,
                accountId,
                symbol,
                side
        );
        return quantity == null ? 0L : Math.max(0L, quantity);
    }

    private AutoMarketConfig mapConfig(ResultSet rs) throws SQLException {
        return new AutoMarketConfig(
                normalizeSymbol(rs.getString("symbol")),
                clamp(rs.getInt("intensity"), 1, 10),
                Math.max(1, rs.getInt("max_order_quantity")),
                Math.max(1, rs.getInt("order_ttl_seconds")),
                Math.max(0L, rs.getLong("tradable_shares")),
                positiveOrDefault(rs.getBigDecimal("tick_size"), DEFAULT_TICK_SIZE),
                rs.getBigDecimal("current_price"),
                positiveOrDefault(rs.getBigDecimal("previous_close"), rs.getBigDecimal("current_price")),
                nullableInt(rs, "report_score")
        );
    }

    private Integer nullableInt(ResultSet rs, String columnName) throws SQLException {
        int value = rs.getInt(columnName);
        return rs.wasNull() ? null : value;
    }

    private BigDecimal positiveOrDefault(BigDecimal value, BigDecimal defaultValue) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0 ? defaultValue : value;
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
