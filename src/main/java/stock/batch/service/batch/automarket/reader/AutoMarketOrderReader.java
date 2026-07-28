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
import stock.batch.service.batch.automarket.model.AutoMarketOrderBookSnapshot;
import stock.batch.service.batch.automarket.model.AutoOrder;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

@Component
public class AutoMarketOrderReader {

    private final JdbcClient jdbcClient;
    private final String lockClause;
    private final String lockedOrderTable;
    private final String expiryOrderTable;

    public AutoMarketOrderReader(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate));
        boolean mysql = isMySql(jdbcTemplate);
        this.lockClause = mysql ? "for update skip locked" : "for update";
        this.lockedOrderTable = mysql ? "stock_order force index (primary)" : "stock_order";
        this.expiryOrderTable = mysql
                ? "stock_order o force index (idx_stock_order_market_status_symbol)"
                : "stock_order o";
    }

    public List<AutoOrder> findExpiredAutoOrders(
            AutoMarketConfig config,
            Map<AutoParticipantProfileType, LocalDateTime> thresholdsByProfile,
            LocalDateTime now,
            int limit
    ) {
        if (thresholdsByProfile == null || thresholdsByProfile.isEmpty()) {
            throw new IllegalArgumentException("Auto-order expiry thresholds are required");
        }
        List<AutoParticipantProfileType> profileTypes = thresholdsByProfile.keySet()
                .stream()
                .sorted()
                .toList();
        LocalDateTime fallbackThreshold = thresholdsByProfile.values()
                .stream()
                .min(LocalDateTime::compareTo)
                .orElseThrow();
        String profileThresholdCases = profileTypes.stream()
                .map(profileType -> "when '%s' then :threshold_%s".formatted(
                        profileType.name(),
                        profileType.name().toLowerCase(Locale.ROOT)
                ))
                .collect(Collectors.joining(System.lineSeparator()));
        JdbcClient.StatementSpec statement = jdbcClient.sql(
                """
                select o.id,
                       o.account_id,
                       o.symbol,
                       o.side,
                       o.quantity,
                       o.filled_quantity,
                       o.reserved_cash,
                       o.limit_price,
                       coalesce(o.auto_profile_type, p.profile_type) as profile_type,
                       coalesce(o.auto_behavior_model_version, 'V3') as behavior_model_version,
                       o.expires_at,
                       o.created_at
                  from %s
                join stock_account a on a.id = o.account_id
                join stock_auto_participant p on p.user_key = a.user_key
                where o.symbol = :symbol
                  and o.status in ('PENDING', 'PARTIALLY_FILLED')
                  and o.market_type = 'ORDER_BOOK'
                  and o.quantity > o.filled_quantity
                  and (
                       (o.expires_at is not null and o.expires_at <= :now)
                       or (
                           o.expires_at is null
                           and o.created_at < case coalesce(o.auto_profile_type, p.profile_type)
                               %s
                               else :fallbackThreshold
                           end
                       )
                  )
                order by o.created_at asc, o.id asc
                limit :limit
                """.formatted(expiryOrderTable, profileThresholdCases)
        )
                .param("symbol", config.symbol())
                .param("fallbackThreshold", fallbackThreshold)
                .param("now", now)
                .param("limit", Math.max(1, limit));
        for (AutoParticipantProfileType profileType : profileTypes) {
            statement = statement.param(
                    "threshold_" + profileType.name().toLowerCase(Locale.ROOT),
                    thresholdsByProfile.get(profileType)
            );
        }
        return statement
                .query((rs, rowNum) -> AutoMarketReaderMapper.toAutoParticipantOrder(rs))
                .list();
    }

    public List<AutoOrder> findExpiredInstitutionOrders(
            String symbol,
            LocalDateTime now,
            int limit
    ) {
        return jdbcClient.sql(
                        """
                        select o.id,
                               o.account_id,
                               o.symbol,
                               o.side,
                               o.quantity,
                               o.filled_quantity,
                               o.reserved_cash,
                               o.limit_price,
                               o.expires_at,
                               o.created_at
                          from %s
                          join stock_order_strategy_origin strategy_origin
                            on strategy_origin.order_id = o.id
                           and strategy_origin.origin_type = 'INSTITUTIONAL_INVESTOR'
                         where o.symbol = :symbol
                           and o.status in ('PENDING', 'PARTIALLY_FILLED')
                           and o.market_type = 'ORDER_BOOK'
                           and o.quantity > o.filled_quantity
                           and o.expires_at is not null
                           and o.expires_at <= :now
                         order by o.expires_at asc, o.id asc
                         limit :limit
                        """.formatted(expiryOrderTable)
                )
                .param("symbol", symbol)
                .param("now", now)
                .param("limit", Math.max(1, limit))
                .query((rs, rowNum) -> new AutoOrder(
                        rs.getLong("id"),
                        rs.getLong("account_id"),
                        rs.getString("symbol"),
                        rs.getString("side"),
                        rs.getLong("quantity"),
                        rs.getLong("filled_quantity"),
                        rs.getBigDecimal("reserved_cash"),
                        rs.getBigDecimal("limit_price"),
                        null,
                        null,
                        rs.getObject("expires_at", LocalDateTime.class),
                        rs.getObject("created_at", LocalDateTime.class)
                ))
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
                            candidate == null ? null : candidate.limitPrice(),
                            candidate == null ? null : candidate.profileType(),
                            candidate == null ? null : candidate.behaviorModelVersion(),
                            candidate == null ? null : candidate.expiresAt(),
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

    public AutoMarketOrderBookSnapshot findOrderBookSnapshot(String symbol) {
        return jdbcClient.sql(
                """
                select max(case when side = 'BUY' then limit_price end) as best_bid,
                       min(case when side = 'SELL' then limit_price end) as best_ask,
                       coalesce(sum(case when side = 'BUY' then quantity - filled_quantity else 0 end), 0)
                           as open_buy_quantity,
                       coalesce(sum(case when side = 'SELL' then quantity - filled_quantity else 0 end), 0)
                           as open_sell_quantity
                  from stock_order
                 where market_type = 'ORDER_BOOK'
                   and symbol = :symbol
                   and status in ('PENDING', 'PARTIALLY_FILLED')
                   and quantity > filled_quantity
                """
        )
                .param("symbol", symbol)
                .query((rs, rowNum) -> new AutoMarketOrderBookSnapshot(
                        rs.getBigDecimal("best_bid"),
                        rs.getBigDecimal("best_ask"),
                        rs.getLong("open_buy_quantity"),
                        rs.getLong("open_sell_quantity")
                ))
                .single();
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
