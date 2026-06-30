package stock.batch.service.batch.automarket.reader;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoOrder;
import stock.batch.service.batch.automarket.model.AutoParticipant;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantRecentCashFlow;
import stock.batch.service.batch.automarket.model.AutoParticipantRecurringCashTarget;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;
import stock.batch.service.batch.automarket.model.AutoParticipantTradingSnapshot;
import stock.batch.service.batch.automarket.model.ListingAutoAccountConfig;
import stock.batch.service.batch.automarket.model.RecurringCashIntervalUnit;

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

    public List<String> findExistingAccountUserKeys(List<String> userKeys) {
        if (userKeys.isEmpty()) {
            return List.of();
        }
        String placeholders = userKeys.stream()
                .map(userKey -> "?")
                .collect(Collectors.joining(", "));
        return jdbcTemplate.queryForList(
                "select user_key from stock_account where user_key in (" + placeholders + ")",
                String.class,
                userKeys.toArray()
        );
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
                       i.price_limit_rate,
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

    public List<AutoParticipantProfileConfig> findParticipantProfileConfigs() {
        return jdbcTemplate.query(
                """
                select profile_type,
                       news_weight,
                       momentum_weight,
                       contrarian_weight,
                       loss_aversion_weight,
                       herding_weight,
                       market_making_weight,
                       overconfidence_weight,
                       noise_weight,
                       panic_sell_weight,
                       dip_buy_weight,
                       order_multiplier,
                       aggression_multiplier,
                       order_ttl_multiplier,
                       quantity_multiplier,
                       holding_patience_weight,
                       deep_loss_hold_weight,
                       profit_taking_weight,
                       recurring_deposit_amount,
                       recurring_deposit_interval_value,
                       recurring_deposit_interval_unit,
                       recurring_deposit_interval_days
                from stock_auto_participant_profile_config
                order by profile_type asc
                """,
                (rs, rowNum) -> new AutoParticipantProfileConfig(
                        AutoParticipantProfileType.parseOrDefault(rs.getString("profile_type")),
                        rs.getBigDecimal("news_weight"),
                        rs.getBigDecimal("momentum_weight"),
                        rs.getBigDecimal("contrarian_weight"),
                        rs.getBigDecimal("loss_aversion_weight"),
                        rs.getBigDecimal("herding_weight"),
                        rs.getBigDecimal("market_making_weight"),
                        rs.getBigDecimal("overconfidence_weight"),
                        rs.getBigDecimal("noise_weight"),
                        rs.getBigDecimal("panic_sell_weight"),
                        rs.getBigDecimal("dip_buy_weight"),
                        rs.getBigDecimal("order_multiplier"),
                        rs.getBigDecimal("aggression_multiplier"),
                        rs.getBigDecimal("order_ttl_multiplier"),
                        rs.getBigDecimal("quantity_multiplier"),
                        rs.getBigDecimal("holding_patience_weight"),
                        rs.getBigDecimal("deep_loss_hold_weight"),
                        rs.getBigDecimal("profit_taking_weight"),
                        rs.getBigDecimal("recurring_deposit_amount"),
                        rs.getBigDecimal("recurring_deposit_interval_value"),
                        RecurringCashIntervalUnit.parseOrNull(rs.getString("recurring_deposit_interval_unit")),
                        rs.getInt("recurring_deposit_interval_days")
                )
        );
    }

    public List<AutoParticipantStrategy> findEnabledParticipantStrategies(AutoMarketConfig config) {
        return findEnabledParticipantStrategiesBySymbol(List.of(config)).getOrDefault(config.symbol(), List.of());
    }

    public Map<String, List<AutoParticipantStrategy>> findEnabledParticipantStrategiesBySymbol(List<AutoMarketConfig> configs) {
        if (configs.isEmpty()) {
            return Map.of();
        }
        List<ActiveParticipantStrategy> activeParticipants = findActiveParticipantStrategies();
        Map<String, ParticipantSymbolStrategyConfig> symbolConfigByKey = findParticipantSymbolStrategyConfigs(
                configs.stream()
                        .map(AutoMarketConfig::symbol)
                        .distinct()
                        .toList()
        );
        Map<String, List<AutoParticipantStrategy>> strategiesBySymbol = new LinkedHashMap<>();
        for (AutoMarketConfig config : configs) {
            List<AutoParticipantStrategy> strategies = new ArrayList<>();
            for (ActiveParticipantStrategy participant : activeParticipants) {
                ParticipantSymbolStrategyConfig symbolConfig = symbolConfigByKey.get(strategyConfigKey(participant.userKey(), config.symbol()));
                if (symbolConfig != null && !symbolConfig.enabled()) {
                    continue;
                }
                int intensity = symbolConfig == null ? config.intensity() : symbolConfig.intensity();
                strategies.add(participant.toStrategy(clamp(intensity, 1, 10)));
            }
            strategiesBySymbol.put(config.symbol(), List.copyOf(strategies));
        }
        return strategiesBySymbol;
    }

    private List<ActiveParticipantStrategy> findActiveParticipantStrategies() {
        return jdbcTemplate.query(
                """
	                select p.user_key,
	                       a.id as account_id,
	                       p.profile_type,
	                       p.recurring_cash_amount,
	                       p.recurring_cash_interval_value,
	                       p.recurring_cash_interval_unit
	                from stock_auto_participant p
                join stock_account a on a.user_key = p.user_key and a.status = 'ACTIVE'
                where p.enabled = true
                  and p.withdrawn_at is null
                order by p.user_key asc
                """,
                (rs, rowNum) -> new ActiveParticipantStrategy(
	                        rs.getString("user_key"),
	                        rs.getLong("account_id"),
	                        AutoParticipantProfileType.parseOrDefault(rs.getString("profile_type")),
	                        rs.getBigDecimal("recurring_cash_amount"),
	                        rs.getBigDecimal("recurring_cash_interval_value"),
	                        RecurringCashIntervalUnit.parseOrNull(rs.getString("recurring_cash_interval_unit"))
	                )
        );
    }

    private Map<String, ParticipantSymbolStrategyConfig> findParticipantSymbolStrategyConfigs(List<String> symbols) {
        if (symbols.isEmpty()) {
            return Map.of();
        }
        String placeholders = symbols.stream()
                .map(symbol -> "?")
                .collect(Collectors.joining(", "));
        List<ParticipantSymbolStrategyConfig> configs = jdbcTemplate.query(
                """
                select user_key,
                       symbol,
                       enabled,
                       intensity
                from stock_auto_participant_symbol_config
                where symbol in (
                """ + placeholders + """
                )
                """,
                (rs, rowNum) -> new ParticipantSymbolStrategyConfig(
                        rs.getString("user_key"),
                        rs.getString("symbol"),
                        rs.getBoolean("enabled"),
                        rs.getInt("intensity")
                ),
                symbols.toArray()
        );
        Map<String, ParticipantSymbolStrategyConfig> byKey = new HashMap<>();
        for (ParticipantSymbolStrategyConfig config : configs) {
            byKey.put(strategyConfigKey(config.userKey(), config.symbol()), config);
        }
        return byKey;
    }

    public List<AutoParticipantRecurringCashTarget> findRecurringCashTargets() {
        return jdbcTemplate.query(
                """
                select a.id as account_id,
                       p.profile_type,
                       p.recurring_cash_amount,
                       p.recurring_cash_interval_value,
                       p.recurring_cash_interval_unit
                  from stock_auto_participant p
                  join stock_account a on a.user_key = p.user_key and a.status = 'ACTIVE'
                 where p.enabled = true
                   and p.withdrawn_at is null
                 order by p.user_key asc
                """,
                (rs, rowNum) -> new AutoParticipantRecurringCashTarget(
                        rs.getLong("account_id"),
                        AutoParticipantProfileType.parseOrDefault(rs.getString("profile_type")),
                        rs.getBigDecimal("recurring_cash_amount"),
                        rs.getBigDecimal("recurring_cash_interval_value"),
                        RecurringCashIntervalUnit.parseOrNull(rs.getString("recurring_cash_interval_unit"))
                )
        );
    }

    public List<AutoParticipantRecentCashFlow> findRecentCashFlows(
            List<Long> accountIds,
            Set<String> reasons,
            String createdBy,
            LocalDateTime since
    ) {
        if (accountIds.isEmpty() || reasons.isEmpty()) {
            return List.of();
        }
        String accountPlaceholders = accountIds.stream()
                .map(accountId -> "?")
                .collect(Collectors.joining(", "));
        List<String> sortedReasons = reasons.stream().sorted().toList();
        String reasonPlaceholders = sortedReasons.stream()
                .map(reason -> "?")
                .collect(Collectors.joining(", "));
        List<Object> args = new ArrayList<>(accountIds.size() + sortedReasons.size() + 2);
        args.addAll(accountIds);
        args.addAll(sortedReasons);
        args.add(createdBy);
        args.add(since);
        return jdbcTemplate.query(
                """
                select account_id, reason, created_at
                from stock_account_cash_flow
                where account_id in (%s)
                  and reason in (%s)
                  and created_by = ?
                  and created_at >= ?
                order by account_id asc, created_at desc, id desc
                """.formatted(accountPlaceholders, reasonPlaceholders),
                (rs, rowNum) -> new AutoParticipantRecentCashFlow(
                        rs.getLong("account_id"),
                        rs.getString("reason"),
                        rs.getTimestamp("created_at").toLocalDateTime()
                ),
                args.toArray()
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
                       i.price_limit_rate,
                       p.current_price,
                       p.previous_close
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
                        rs.getBigDecimal("current_price"),
                        positiveOrDefault(rs.getBigDecimal("previous_close"), rs.getBigDecimal("current_price")),
                        positiveOrDefault(rs.getBigDecimal("price_limit_rate"), BigDecimal.valueOf(30))
                ),
                config.symbol()
        );
    }

    public List<AutoOrder> findExpiredAutoOrders(AutoMarketConfig config, LocalDateTime candidateThreshold, int limit) {
        return jdbcTemplate.query(
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
                where o.symbol = ?
                  and o.status in ('PENDING', 'PARTIALLY_FILLED')
                  and o.market_type = 'ORDER_BOOK'
                  and o.created_at < ?
                order by o.created_at asc
                limit ?
                for update
                """,
                (rs, rowNum) -> new AutoOrder(
                        rs.getLong("id"),
                        rs.getLong("account_id"),
                        rs.getString("symbol"),
                        rs.getString("side"),
                        rs.getLong("quantity"),
                        rs.getLong("filled_quantity"),
                        rs.getBigDecimal("reserved_cash"),
                        AutoParticipantProfileType.parseOrDefault(rs.getString("profile_type")),
                        rs.getTimestamp("created_at").toLocalDateTime()
                ),
                config.symbol(),
                candidateThreshold,
                Math.max(1, limit)
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

    public List<AutoParticipantTradingSnapshot> findTradingSnapshots(
            List<Long> accountIds,
            String symbol,
            LocalDateTime recentDividendSince
    ) {
        if (accountIds.isEmpty()) {
            return List.of();
        }
        String placeholders = accountIds.stream()
                .map(accountId -> "?")
                .collect(Collectors.joining(", "));
        Object[] args = new Object[accountIds.size() + 2];
        args[0] = symbol;
        args[1] = recentDividendSince;
        for (int index = 0; index < accountIds.size(); index++) {
            args[index + 2] = accountIds.get(index);
        }
        return jdbcTemplate.query(
                """
                select a.id as account_id,
                       a.cash_balance,
                       coalesce(max(h.quantity - h.reserved_quantity), 0) as available_quantity,
                       coalesce(max(case when h.quantity > 0 then h.average_price else 0 end), 0) as average_price,
                       coalesce(sum(f.amount), 0) as recent_dividend_cash_amount
                from stock_account a
                left join stock_holding h
                  on h.account_id = a.id
                 and h.symbol = ?
                left join stock_account_cash_flow f
                  on f.account_id = a.id
                 and f.flow_type = 'DEPOSIT'
                 and f.reason = 'DIVIDEND_PAYMENT'
                 and f.created_at >= ?
                where a.id in (%s)
                group by a.id, a.cash_balance
                """.formatted(placeholders),
                (rs, rowNum) -> new AutoParticipantTradingSnapshot(
                        rs.getLong("account_id"),
                        zeroIfNull(rs.getBigDecimal("cash_balance")),
                        Math.max(0L, rs.getLong("available_quantity")),
                        zeroIfNull(rs.getBigDecimal("average_price")),
                        zeroIfNull(rs.getBigDecimal("recent_dividend_cash_amount"))
                ),
                args
        );
    }

    public BigDecimal getCashBalance(long accountId) {
        BigDecimal cash = jdbcTemplate.queryForObject(
                "select coalesce(max(cash_balance), 0) from stock_account where id = ?",
                BigDecimal.class,
                accountId
        );
        return cash == null ? BigDecimal.ZERO : cash;
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

    public Optional<BigDecimal> findLatestPriceAtOrBefore(String symbol, LocalDateTime priceTime) {
        List<BigDecimal> prices = jdbcTemplate.queryForList(
                """
                select price
                from stock_price_tick
                where symbol = ?
                  and price_time <= ?
                order by price_time desc, id desc
                limit 1
                """,
                BigDecimal.class,
                normalizeSymbol(symbol),
                priceTime
        );
        return prices.stream()
                .filter(price -> price != null && price.compareTo(BigDecimal.ZERO) > 0)
                .findFirst();
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
                positiveOrDefault(rs.getBigDecimal("price_limit_rate"), BigDecimal.valueOf(30)),
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

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String strategyConfigKey(String userKey, String symbol) {
        return userKey + "\n" + normalizeSymbol(symbol);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record ActiveParticipantStrategy(
            String userKey,
            long accountId,
            AutoParticipantProfileType profileType,
            BigDecimal recurringCashAmount,
            BigDecimal recurringCashIntervalValue,
            RecurringCashIntervalUnit recurringCashIntervalUnit
    ) {
        AutoParticipantStrategy toStrategy(int intensity) {
            return new AutoParticipantStrategy(
                    userKey,
                    accountId,
                    intensity,
                    profileType,
                    recurringCashAmount,
                    recurringCashIntervalValue,
                    recurringCashIntervalUnit
            );
        }
    }

    private record ParticipantSymbolStrategyConfig(
            String userKey,
            String symbol,
            boolean enabled,
            int intensity
    ) {
    }
}
