package stock.batch.service.batch.automarket.reader;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoOrder;
import stock.batch.service.batch.automarket.model.ListingAutoAccountConfig;

@Component
public class AutoMarketOrderReader {

    private final JdbcClient jdbcClient;
    private final String lockClause;
    private final String lockedOrderTable;

    public AutoMarketOrderReader(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate));
        boolean mysql = isMySql(jdbcTemplate);
        this.lockClause = mysql ? "for update skip locked" : "for update";
        this.lockedOrderTable = mysql ? "stock_order force index (primary)" : "stock_order";
    }

    public List<AutoOrder> findExpiredAutoOrders(AutoMarketConfig config, LocalDateTime candidateThreshold, int limit) {
        List<Long> orderIds = findExpiredAutoOrderIds(config.symbol(), candidateThreshold, limit);
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql(
                """
                select o.id,
                       o.account_id,
                       o.symbol,
                       o.side,
                       o.quantity,
                       o.filled_quantity,
                       o.reserved_cash,
                       p.profile_type,
                       o.created_at
                from stock_order o
                join stock_account a on a.id = o.account_id
                join stock_auto_participant p on p.user_key = a.user_key
                where o.symbol = :symbol
                  and o.id in (:orderIds)
                  and o.status in ('PENDING', 'PARTIALLY_FILLED')
                  and o.market_type = 'ORDER_BOOK'
                  and o.quantity > o.filled_quantity
                order by o.created_at asc, o.id asc
                """
        )
                .param("symbol", config.symbol())
                .param("orderIds", orderIds)
                .query((rs, rowNum) -> AutoMarketReaderMapper.toAutoParticipantOrder(rs))
                .list();
    }

    public List<AutoOrder> findExpiredListingAutoOrders(ListingAutoAccountConfig config, LocalDateTime threshold) {
        List<Long> orderIds = findExpiredListingAutoOrderIds(config, threshold);
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql(
                """
                select o.id, o.account_id, o.symbol, o.side, o.quantity, o.filled_quantity, o.reserved_cash
                from stock_order o
                where o.symbol = :symbol
                  and o.account_id = :accountId
                  and o.id in (:orderIds)
                  and o.status in ('PENDING', 'PARTIALLY_FILLED')
                  and o.market_type = 'ORDER_BOOK'
                  and o.quantity > o.filled_quantity
                order by o.created_at asc, o.id asc
                """
        )
                .param("symbol", config.symbol())
                .param("accountId", config.accountId())
                .param("orderIds", orderIds)
                .query((rs, rowNum) -> AutoMarketReaderMapper.toListingAutoAccountOrder(rs))
                .list();
    }

    public List<AutoOrder> findOpenListingAutoOrders(ListingAutoAccountConfig config, String side) {
        return jdbcClient.sql(
                """
                select o.id, o.account_id, o.symbol, o.side, o.quantity, o.filled_quantity,
                       o.reserved_cash, o.created_at
                  from stock_order o
                 where o.symbol = :symbol
                   and o.account_id = :accountId
                   and o.side = :side
                   and o.status in ('PENDING', 'PARTIALLY_FILLED')
                   and o.market_type = 'ORDER_BOOK'
                   and o.quantity > o.filled_quantity
                 order by o.created_at asc, o.id asc
                 limit 200
                """
        )
                .param("symbol", config.symbol())
                .param("accountId", config.accountId())
                .param("side", side)
                .query((rs, rowNum) -> AutoMarketReaderMapper.toListingAutoAccountOrder(rs))
                .list();
    }

    private List<Long> findExpiredAutoOrderIds(String symbol, LocalDateTime candidateThreshold, int limit) {
        return jdbcClient.sql(
                """
                select o.id
                from stock_order o
                where o.symbol = :symbol
                  and o.status in ('PENDING', 'PARTIALLY_FILLED')
                  and o.market_type = 'ORDER_BOOK'
                  and o.quantity > o.filled_quantity
                  and o.created_at < :candidateThreshold
                order by o.created_at asc, o.id asc
                limit :limit
                """
        )
                .param("symbol", symbol)
                .param("candidateThreshold", candidateThreshold)
                .param("limit", Math.max(1, limit))
                .query(Long.class)
                .list();
    }

    private List<Long> findExpiredListingAutoOrderIds(ListingAutoAccountConfig config, LocalDateTime threshold) {
        return jdbcClient.sql(
                """
                select o.id
                from stock_order o
                where o.symbol = :symbol
                  and o.account_id = :accountId
                  and o.status in ('PENDING', 'PARTIALLY_FILLED')
                  and o.market_type = 'ORDER_BOOK'
                  and o.quantity > o.filled_quantity
                  and o.created_at < :threshold
                order by o.created_at asc, o.id asc
                limit 200
                """
        )
                .param("symbol", config.symbol())
                .param("accountId", config.accountId())
                .param("threshold", threshold)
                .query(Long.class)
                .list();
    }

    public List<AutoOrder> lockOpenOrdersForUpdate(List<AutoOrder> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<Long, AutoOrder> candidateById = candidates.stream()
                .collect(Collectors.toMap(AutoOrder::id, Function.identity(), (left, right) -> left));
        return jdbcClient.sql(
                """
                select id, account_id, symbol, side, quantity, filled_quantity, reserved_cash
                  from %s
                 where id in (:orderIds)
                   and status in ('PENDING', 'PARTIALLY_FILLED')
                   and market_type = 'ORDER_BOOK'
                   and quantity > filled_quantity
                 order by id asc
                %s
                """.formatted(lockedOrderTable, lockClause)
        )
                .param("orderIds", candidateById.keySet())
                .query((rs, rowNum) -> {
                    AutoOrder candidate = candidateById.get(rs.getLong("id"));
                    return new AutoOrder(
                            rs.getLong("id"),
                            rs.getLong("account_id"),
                            rs.getString("symbol"),
                            rs.getString("side"),
                            rs.getLong("quantity"),
                            rs.getLong("filled_quantity"),
                            rs.getBigDecimal("reserved_cash"),
                            candidate == null ? null : candidate.profileType(),
                            candidate == null ? null : candidate.createdAt()
                    );
                })
                .list();
    }

    public BigDecimal findBestPrice(String symbol, String side) {
        if ("BUY".equals(side)) {
            return findBestBuyPrice(symbol);
        }
        return findBestSellPrice(symbol);
    }

    public BigDecimal findBestExternalPrice(String symbol, String side, long excludedAccountId) {
        String direction = "BUY".equals(side) ? "desc" : "asc";
        return jdbcClient.sql(
                """
                select limit_price
                  from stock_order
                 where symbol = :symbol
                   and side = :side
                   and account_id <> :excludedAccountId
                   and market_type = 'ORDER_BOOK'
                   and order_type = 'LIMIT'
                   and status in ('PENDING', 'PARTIALLY_FILLED')
                   and limit_price is not null
                   and quantity > filled_quantity
                 order by limit_price %s, created_at asc, id asc
                 limit 1
                """.formatted(direction)
        )
                .param("symbol", symbol)
                .param("side", side)
                .param("excludedAccountId", excludedAccountId)
                .query(BigDecimal.class)
                .optional()
                .orElse(null);
    }

    private BigDecimal findBestBuyPrice(String symbol) {
        return jdbcClient.sql(
                """
                select limit_price
                from stock_order
                where symbol = :symbol
                  and side = 'BUY'
                  and market_type = 'ORDER_BOOK'
                  and order_type = 'LIMIT'
                  and status in ('PENDING', 'PARTIALLY_FILLED')
                  and limit_price is not null
                  and quantity > filled_quantity
                order by limit_price desc, created_at asc
                limit 1
                """
        )
                .param("symbol", symbol)
                .query(BigDecimal.class)
                .optional()
                .orElse(null);
    }

    private BigDecimal findBestSellPrice(String symbol) {
        return jdbcClient.sql(
                """
                select limit_price
                from stock_order
                where symbol = :symbol
                  and side = 'SELL'
                  and market_type = 'ORDER_BOOK'
                  and order_type = 'LIMIT'
                  and status in ('PENDING', 'PARTIALLY_FILLED')
                  and limit_price is not null
                  and quantity > filled_quantity
                order by limit_price asc, created_at asc
                limit 1
                """
        )
                .param("symbol", symbol)
                .query(BigDecimal.class)
                .optional()
                .orElse(null);
    }

    public long getOpenOrderQuantity(String symbol, String side) {
        Long quantity = jdbcClient.sql(
                """
                select coalesce(sum(quantity - filled_quantity), 0)
                from stock_order
                where symbol = :symbol
                  and side = :side
                  and market_type = 'ORDER_BOOK'
                  and status in ('PENDING', 'PARTIALLY_FILLED')
                  and quantity > filled_quantity
                """
        )
                .param("symbol", symbol)
                .param("side", side)
                .query(Long.class)
                .single();
        return quantity == null ? 0L : Math.max(0L, quantity);
    }

    public long getOpenOrderQuantity(long accountId, String symbol, String side) {
        Long quantity = jdbcClient.sql(
                """
                select coalesce(sum(quantity - filled_quantity), 0)
                from stock_order
                where account_id = :accountId
                  and symbol = :symbol
                  and side = :side
                  and market_type = 'ORDER_BOOK'
                  and status in ('PENDING', 'PARTIALLY_FILLED')
                  and quantity > filled_quantity
                """
        )
                .param("accountId", accountId)
                .param("symbol", symbol)
                .param("side", side)
                .query(Long.class)
                .single();
        return quantity == null ? 0L : Math.max(0L, quantity);
    }

    private boolean isMySql(JdbcTemplate jdbcTemplate) {
        String productName = jdbcTemplate.execute(
                (ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName()
        );
        return productName != null && productName.toLowerCase(Locale.ROOT).contains("mysql");
    }
}
