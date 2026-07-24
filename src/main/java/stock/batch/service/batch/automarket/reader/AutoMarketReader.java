package stock.batch.service.batch.automarket.reader;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoMarketHistoricalSignal;
import stock.batch.service.batch.automarket.model.AutoMarketDailyRegimePreparationConfig;
import stock.batch.service.batch.automarket.model.AutoMarketDistributionBias;
import stock.batch.service.batch.automarket.model.AutoMarketRegimeCountWeights;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;
import stock.batch.service.batch.automarket.model.AutoParticipantSymbolStrategy;
import stock.batch.service.batch.automarket.model.AutoParticipantTradingSnapshot;
import stock.batch.service.batch.automarket.model.RecurringCashIntervalUnit;
import web.common.core.utils.DeterministicSeed;

@Component
public class AutoMarketReader {

    private static final int LATEST_PRICE_SYMBOL_QUERY_CHUNK_SIZE = 100;

    private final JdbcClient jdbcClient;

    public AutoMarketReader(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate));
    }

    public List<AutoMarketConfig> findEnabledConfigs() {
        return jdbcClient.sql(
                """
                select c.symbol,
                       i.market,
                       c.primary_price_pressure_bias,
                       c.primary_asset_preference_pressure_bias,
                       c.primary_volatility_pressure_bias,
                       c.primary_liquidity_pressure_bias,
                       c.primary_execution_aggression_pressure_bias,
                       c.secondary_price_pressure_bias,
                       c.secondary_asset_preference_pressure_bias,
                       c.secondary_volatility_pressure_bias,
                       c.secondary_liquidity_pressure_bias,
                       c.secondary_execution_aggression_pressure_bias,
                       c.max_order_quantity,
                       c.order_ttl_seconds,
                       i.tradable_shares,
                       i.tick_size,
                       i.price_limit_rate,
                       p.current_price,
                       p.previous_close,
                       r.score as report_score,
                       r.created_at as report_created_at
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
                """
        )
                .query((rs, rowNum) -> AutoMarketReaderMapper.toConfig(rs))
                .list();
    }

    public List<AutoMarketConfig> findEnabledMaintenanceConfigs() {
        return jdbcClient.sql(
                """
                select c.symbol,
                       i.market,
                       0 as primary_price_pressure_bias,
                       0 as primary_asset_preference_pressure_bias,
                       0 as primary_volatility_pressure_bias,
                       0 as primary_liquidity_pressure_bias,
                       0 as primary_execution_aggression_pressure_bias,
                       0 as secondary_price_pressure_bias,
                       0 as secondary_asset_preference_pressure_bias,
                       0 as secondary_volatility_pressure_bias,
                       0 as secondary_liquidity_pressure_bias,
                       0 as secondary_execution_aggression_pressure_bias,
                       c.max_order_quantity,
                       c.order_ttl_seconds,
                       i.tradable_shares,
                       i.tick_size,
                       i.price_limit_rate,
                       p.current_price,
                       p.previous_close,
                       null as report_score,
                       null as report_created_at
                  from stock_auto_market_config c
                  join stock_order_book_instrument i
                    on i.symbol = c.symbol
                   and i.enabled = true
                  join stock_order_book_market_config m
                    on m.symbol = c.symbol
                   and m.enabled = true
                   and m.market_status = 'OPEN'
                  join stock_price p
                    on p.symbol = c.symbol
                 where c.enabled = true
                 order by c.symbol asc
                """
        )
                .query((rs, rowNum) -> AutoMarketReaderMapper.toConfig(rs))
                .list();
    }

    public List<AutoMarketConfig> findDailyRegimePreCreateConfigs() {
        return jdbcClient.sql(
                """
                select c.symbol,
                       i.market,
                       c.primary_price_pressure_bias,
                       c.primary_asset_preference_pressure_bias,
                       c.primary_volatility_pressure_bias,
                       c.primary_liquidity_pressure_bias,
                       c.primary_execution_aggression_pressure_bias,
                       c.secondary_price_pressure_bias,
                       c.secondary_asset_preference_pressure_bias,
                       c.secondary_volatility_pressure_bias,
                       c.secondary_liquidity_pressure_bias,
                       c.secondary_execution_aggression_pressure_bias,
                       c.max_order_quantity,
                       c.order_ttl_seconds,
                       i.tradable_shares,
                       i.tick_size,
                       i.price_limit_rate,
                       p.current_price,
                       p.previous_close,
                       r.score as report_score,
                       r.created_at as report_created_at
                  from stock_auto_market_config c
                  join stock_order_book_instrument i on i.symbol = c.symbol and i.enabled = true
                  join stock_order_book_market_config m on m.symbol = c.symbol and m.enabled = true
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
                """
        )
                .query((rs, rowNum) -> AutoMarketReaderMapper.toConfig(rs))
                .list();
    }

    public List<AutoMarketDailyRegimePreparationConfig> findDailyRegimePreparationConfigs() {
        return jdbcClient.sql(
                """
                select c.symbol,
                       c.primary_price_pressure_bias,
                       c.primary_asset_preference_pressure_bias,
                       c.primary_volatility_pressure_bias,
                       c.primary_liquidity_pressure_bias,
                       c.primary_execution_aggression_pressure_bias,
                       c.primary_regime_count_1_weight,
                       c.primary_regime_count_2_weight,
                       c.primary_regime_count_3_weight,
                       c.primary_regime_count_4_weight
                from stock_auto_market_config c
                join stock_order_book_instrument i on i.symbol = c.symbol and i.enabled = true
                join stock_order_book_market_config m on m.symbol = c.symbol and m.enabled = true
                where c.enabled = true
                order by c.symbol asc
                """
        )
                .query((rs, rowNum) -> new AutoMarketDailyRegimePreparationConfig(
                        AutoMarketReaderMapper.normalizeSymbol(rs.getString("symbol")),
                        new AutoMarketDistributionBias(
                                rs.getInt("primary_price_pressure_bias"),
                                rs.getInt("primary_asset_preference_pressure_bias"),
                                rs.getInt("primary_volatility_pressure_bias"),
                                rs.getInt("primary_liquidity_pressure_bias"),
                                rs.getInt("primary_execution_aggression_pressure_bias")
                        ),
                        new AutoMarketRegimeCountWeights(
                                rs.getInt("primary_regime_count_1_weight"),
                                rs.getInt("primary_regime_count_2_weight"),
                                rs.getInt("primary_regime_count_3_weight"),
                                rs.getInt("primary_regime_count_4_weight")
                        )
                ))
                .list();
    }

    public List<AutoParticipantProfileConfig> findParticipantProfileConfigs() {
        return jdbcClient.sql(
                """
                select profile_type,
                       behavior_model_version,
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
                       decision_frequency_multiplier,
                       orders_per_decision_multiplier,
                       aggression_multiplier,
                       price_pressure_sensitivity,
                       order_ttl_multiplier,
                       quantity_multiplier,
                       holding_patience_weight,
                       deep_loss_hold_weight,
                       profit_taking_weight,
                       pricing_mode,
                       exit_mode,
                       inventory_mode,
                       recurring_deposit_amount,
                       recurring_deposit_interval_value,
                       recurring_deposit_interval_unit,
                       recurring_deposit_interval_days
                from stock_auto_participant_profile_config
                order by profile_type asc
                """
        )
                .query((rs, rowNum) -> AutoMarketReaderMapper.toProfileConfig(rs))
                .list();
    }

    public Map<String, List<AutoParticipantStrategy>> findEnabledParticipantStrategiesBySymbol(List<AutoMarketConfig> configs) {
        if (configs.isEmpty()) {
            return Map.of();
        }
        List<ActiveParticipantStrategy> activeParticipants = findActiveParticipantStrategies();
        List<ParticipantSymbolStrategyConfig> symbolConfigs = findParticipantSymbolStrategyConfigs(
                configs.stream()
                        .map(AutoMarketConfig::symbol)
                        .distinct()
                        .toList()
        );
        return AutoMarketStrategyAssembler.bySymbol(configs, activeParticipants, symbolConfigs);
    }

    public List<AutoParticipantSymbolStrategy> findDueParticipantSymbolStrategies(
            List<AutoMarketConfig> configs,
            LocalDateTime now,
            int participantLimit
    ) {
        return findDueParticipantSymbolStrategies(configs, null, now, participantLimit);
    }

    public List<AutoParticipantSymbolStrategy> findDueParticipantSymbolStrategies(
            List<AutoMarketConfig> configs,
            AutoParticipantProfileType profileType,
            LocalDateTime now,
            int participantLimit
    ) {
        if (configs.isEmpty()) {
            return List.of();
        }
        List<String> symbols = configs.stream().map(AutoMarketConfig::symbol).distinct().toList();
        int normalizedLimit = Math.max(1, participantLimit);
        String profileFilter = profileType == null ? "" : "and p.profile_type = :profileType";
        JdbcClient.StatementSpec statement = jdbcClient.sql(
                """
                with due_participants as (
                    select p.user_key,
                           a.id as account_id,
                           p.profile_type,
                           p.recurring_cash_amount,
                           p.recurring_cash_interval_value,
                           p.recurring_cash_interval_unit,
                           coalesce(pc.behavior_model_version, 'V2') as behavior_model_version,
                           p.behavior_seed,
                           s.next_run_at,
                           s.priority
                    from stock_auto_participant_order_schedule s
                    join stock_auto_participant p
                      on p.user_key = s.user_key
                     and p.enabled = true
                     and p.withdrawn_at is null
                    left join stock_auto_participant_profile_config pc
                      on pc.profile_type = p.profile_type
                    join stock_account a
                      on a.user_key = p.user_key
                     and a.status = 'ACTIVE'
                    where s.next_run_at <= :now
                      and (s.lease_until is null or s.lease_until <= :now)
                      %s
                    order by s.next_run_at asc, s.priority desc, s.user_key asc
                    limit :limit
                )
                select c.symbol,
                       d.user_key,
                       d.account_id,
                       d.profile_type,
                       d.recurring_cash_amount,
                       d.recurring_cash_interval_value,
                       d.recurring_cash_interval_unit,
                       d.behavior_model_version,
                       d.behavior_seed,
                       sc.intensity as symbol_intensity,
                       d.next_run_at,
                       d.priority
                from due_participants d
                join stock_auto_market_config c
                  on c.symbol in (:symbols)
                 and c.enabled = true
                left join stock_auto_participant_symbol_config sc
                  on sc.user_key = d.user_key
                 and sc.symbol = c.symbol
                where (sc.enabled is null or sc.enabled = true)
                order by d.user_key asc, d.next_run_at asc, d.priority desc, c.symbol asc
                """.formatted(profileFilter)
        )
                .param("symbols", symbols)
                .param("now", now)
                .param("limit", normalizedLimit);
        if (profileType != null) {
            statement = statement.param("profileType", profileType.name());
        }
        return statement.query((rs, rowNum) -> {
                    String symbol = AutoMarketReaderMapper.normalizeSymbol(rs.getString("symbol"));
                    int symbolIntensity = rs.getInt("symbol_intensity");
                    if (rs.wasNull()) {
                        symbolIntensity = 5;
                    }
                    AutoParticipantStrategy strategy = new AutoParticipantStrategy(
                            rs.getString("user_key"),
                            rs.getLong("account_id"),
                            Math.clamp(symbolIntensity, 1, 10),
                            AutoParticipantProfileType.parseOrDefault(rs.getString("profile_type")),
                            rs.getBigDecimal("recurring_cash_amount"),
                            rs.getBigDecimal("recurring_cash_interval_value"),
                            RecurringCashIntervalUnit.parseOrNull(rs.getString("recurring_cash_interval_unit")),
                            stock.batch.service.batch.automarket.model.AutoParticipantBehaviorModelVersion.parseOrDefault(rs.getString("behavior_model_version")),
                            rs.getObject("behavior_seed") == null
                                    ? DeterministicSeed.fromUtf8(rs.getString("user_key"))
                                    : rs.getLong("behavior_seed"),
                            rs.getTimestamp("next_run_at").toLocalDateTime()
                    );
                    return new AutoParticipantSymbolStrategy(
                            symbol,
                            strategy,
                            rs.getTimestamp("next_run_at").toLocalDateTime(),
                            rs.getInt("priority")
                    );
                })
                .list();
    }

    public boolean hasMissingParticipantSchedules(List<AutoMarketConfig> configs) {
        if (configs.isEmpty()) {
            return false;
        }
        Boolean missing = jdbcClient.sql(
                """
                select exists(
                    select 1
                    from stock_auto_participant p
                    join stock_account a
                      on a.user_key = p.user_key
                     and a.status = 'ACTIVE'
                    where p.enabled = true
                      and p.withdrawn_at is null
                      and not exists (
                          select 1
                          from stock_auto_participant_order_schedule s
                          where s.user_key = p.user_key
                      )
                    limit 1
                )
                """
        )
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(missing);
    }

    private List<ActiveParticipantStrategy> findActiveParticipantStrategies() {
        return jdbcClient.sql(
                """
	                select p.user_key,
	                       a.id as account_id,
	                       p.profile_type,
	                       p.recurring_cash_amount,
	                       p.recurring_cash_interval_value,
	                       p.recurring_cash_interval_unit
	                       , coalesce(pc.behavior_model_version, 'V2') as behavior_model_version
	                       , p.behavior_seed
	                from stock_auto_participant p
                left join stock_auto_participant_profile_config pc
                  on pc.profile_type = p.profile_type
                join stock_account a on a.user_key = p.user_key and a.status = 'ACTIVE'
                where p.enabled = true
                  and p.withdrawn_at is null
                order by p.user_key asc
                """
        )
                .query((rs, rowNum) -> AutoMarketReaderMapper.toActiveParticipantStrategy(rs))
                .list();
    }

    private List<ParticipantSymbolStrategyConfig> findParticipantSymbolStrategyConfigs(List<String> symbols) {
        if (symbols.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql(
                """
                select user_key,
                       symbol,
                       enabled,
                       intensity
                from stock_auto_participant_symbol_config
                where symbol in (:symbols)
                """
        )
                .param("symbols", symbols)
                .query((rs, rowNum) -> AutoMarketReaderMapper.toParticipantSymbolStrategyConfig(rs))
                .list();
    }

    public List<AutoParticipantTradingSnapshot> findTradingSnapshots(
            List<Long> accountIds,
            String symbol,
            LocalDateTime recentDividendSince
    ) {
        return findTradingSnapshots(accountIds, symbol, recentDividendSince, recentDividendSince.toLocalDate());
    }

    public List<AutoParticipantTradingSnapshot> findLegacyTradingSnapshots(
            List<Long> accountIds,
            String symbol,
            LocalDateTime recentDividendSince
    ) {
        if (accountIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql(
                """
                with scoped_accounts as (
                    select id,
                           cash_balance
                    from stock_account
                    where id in (:accountIds)
                ),
                recent_dividend_cash_flows as (
                    select f.account_id,
                           sum(f.amount) as recent_dividend_cash_amount
                    from stock_account_cash_flow f
                    join scoped_accounts a on a.id = f.account_id
                    where f.flow_type = 'DEPOSIT'
                      and f.reason = 'DIVIDEND_PAYMENT'
                      and f.created_at >= :recentDividendSince
                    group by f.account_id
                ),
                open_order_summary as (
                    select o.account_id,
                           sum(case when o.side = 'BUY' then o.quantity - o.filled_quantity else 0 end) as open_buy_quantity,
                           sum(case when o.side = 'SELL' then o.quantity - o.filled_quantity else 0 end) as open_sell_quantity
                      from stock_order o
                      join scoped_accounts a on a.id = o.account_id
                     where o.symbol = :symbol
                       and o.market_type = 'ORDER_BOOK'
                       and o.status in ('PENDING', 'PARTIALLY_FILLED')
                       and o.quantity > o.filled_quantity
                     group by o.account_id
                )
                select a.id as account_id,
                       a.cash_balance,
                       coalesce(max(h.quantity - h.reserved_quantity), 0) as available_quantity,
                       coalesce(max(case when h.quantity > 0 then h.average_price else 0 end), 0) as average_price,
                       coalesce(max(f.recent_dividend_cash_amount), 0) as recent_dividend_cash_amount,
                       coalesce(max(o.open_buy_quantity), 0) as open_buy_quantity,
                       coalesce(max(o.open_sell_quantity), 0) as open_sell_quantity
                  from scoped_accounts a
                  left join stock_holding h
                    on h.account_id = a.id
                   and h.symbol = :symbol
                  left join recent_dividend_cash_flows f on f.account_id = a.id
                  left join open_order_summary o on o.account_id = a.id
                 group by a.id, a.cash_balance
                """
        )
                .param("symbol", symbol)
                .param("recentDividendSince", recentDividendSince)
                .param("accountIds", accountIds)
                .query((rs, rowNum) -> AutoMarketReaderMapper.toLegacyTradingSnapshot(rs))
                .list();
    }

    public List<AutoParticipantTradingSnapshot> findTradingSnapshots(
            List<Long> accountIds,
            String symbol,
            LocalDateTime recentDividendSince,
            LocalDate businessDate
    ) {
        if (accountIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql(
                """
                with scoped_accounts as (
                    select id,
                           cash_balance
                    from stock_account
                    where id in (:accountIds)
                ),
                recent_dividend_cash_flows as (
                    select f.account_id,
                           sum(f.amount) as recent_dividend_cash_amount
                    from stock_account_cash_flow f
                    join scoped_accounts a on a.id = f.account_id
                    where f.flow_type = 'DEPOSIT'
                      and f.reason = 'DIVIDEND_PAYMENT'
                      and f.created_at >= :recentDividendSince
                    group by f.account_id
                ),
                open_order_summary as (
                    select o.account_id,
                           sum(case when o.symbol = :symbol and o.side = 'BUY' then o.quantity - o.filled_quantity else 0 end) as open_buy_quantity,
                           sum(case when o.symbol = :symbol and o.side = 'SELL' then o.quantity - o.filled_quantity else 0 end) as open_sell_quantity,
                           sum(case when o.side = 'BUY' then o.reserved_cash else 0 end) as open_buy_reserved_cash
                      from stock_order o
                      join scoped_accounts a on a.id = o.account_id
                     where o.market_type = 'ORDER_BOOK'
                       and o.status in ('PENDING', 'PARTIALLY_FILLED')
                       and o.quantity > o.filled_quantity
                     group by o.account_id
                ),
                portfolio_holding_summary as (
                    select h.account_id,
                           sum(h.quantity * p.current_price) as portfolio_holding_market_value,
                           count(*) as portfolio_position_count
                      from stock_holding h
                      join scoped_accounts a on a.id = h.account_id
                      join stock_price p on p.symbol = h.symbol
                     where h.quantity > 0
                     group by h.account_id
                ),
                funding_budget_summary as (
                    select b.account_id,
                           sum(case when b.budget_type = 'PAYDAY' then b.available_amount else 0 end) as payday_available_budget,
                           sum(case when b.budget_type = 'DIVIDEND' and b.source_symbol = :symbol then b.available_amount else 0 end) as dividend_available_budget
                      from stock_auto_participant_funding_budget b
                      join scoped_accounts a on a.id = b.account_id
                     where b.status = 'ACTIVE'
                       and b.available_amount > 0
                       and (b.expires_business_date is null or b.expires_business_date >= :businessDate)
                     group by b.account_id
                )
                select a.id as account_id,
                       a.cash_balance,
                       coalesce(max(h.quantity - h.reserved_quantity), 0) as available_quantity,
                       coalesce(max(h.quantity), 0) as holding_quantity,
                       coalesce(max(h.reserved_quantity), 0) as reserved_quantity,
                       coalesce(max(case when h.quantity > 0 then h.average_price else 0 end), 0) as average_price,
                       coalesce(max(f.recent_dividend_cash_amount), 0) as recent_dividend_cash_amount,
                       coalesce(max(o.open_buy_quantity), 0) as open_buy_quantity,
                       coalesce(max(o.open_sell_quantity), 0) as open_sell_quantity,
                       coalesce(max(o.open_buy_reserved_cash), 0) as open_buy_reserved_cash,
                       coalesce(max(ph.portfolio_holding_market_value), 0) as portfolio_holding_market_value,
                       a.cash_balance
                         + coalesce(max(o.open_buy_reserved_cash), 0)
                         + coalesce(max(ph.portfolio_holding_market_value), 0) as liquid_portfolio_asset,
                       coalesce(max(ph.portfolio_position_count), 0) as portfolio_position_count,
                       coalesce(max(fb.payday_available_budget), 0) as payday_available_budget,
                       coalesce(max(fb.dividend_available_budget), 0) as dividend_available_budget,
                       max(bs.position_opened_business_date) as position_opened_business_date,
                       coalesce(max(bs.holding_trading_days), 0) as holding_trading_days,
                       coalesce(max(bs.average_down_rounds), 0) as average_down_rounds,
                       max(bs.last_average_down_business_date) as last_average_down_business_date,
                       coalesce(max(bs.peak_close_price), 0) as peak_close_price,
                       coalesce(max(ps.recent_profitable_trading_days), 0) as recent_profitable_trading_days,
                       coalesce(max(ps.recent_closed_trading_days), 0) as recent_closed_trading_days
                from scoped_accounts a
                left join stock_holding h
                  on h.account_id = a.id
                 and h.symbol = :symbol
                left join recent_dividend_cash_flows f on f.account_id = a.id
                left join open_order_summary o on o.account_id = a.id
                left join portfolio_holding_summary ph on ph.account_id = a.id
                left join funding_budget_summary fb on fb.account_id = a.id
                left join stock_auto_participant_position_state bs
                  on bs.account_id = a.id
                 and bs.symbol = :symbol
                left join stock_auto_participant_performance_state ps
                  on ps.account_id = a.id
                group by a.id, a.cash_balance
                """
        )
                .param("symbol", symbol)
                .param("recentDividendSince", recentDividendSince)
                .param("businessDate", businessDate)
                .param("accountIds", accountIds)
                .query((rs, rowNum) -> AutoMarketReaderMapper.toTradingSnapshot(rs))
                .list();
    }

    public Map<Long, Map<String, Long>> findProjectedSymbolQuantities(
            List<Long> accountIds,
            List<String> symbols
    ) {
        List<Long> normalizedAccountIds = accountIds == null
                ? List.of()
                : accountIds.stream().filter(java.util.Objects::nonNull).distinct().sorted().toList();
        List<String> normalizedSymbols = symbols == null
                ? List.of()
                : symbols.stream()
                        .map(AutoMarketReaderMapper::normalizeSymbol)
                        .filter(symbol -> symbol != null && !symbol.isBlank())
                        .distinct()
                        .sorted()
                        .toList();
        if (normalizedAccountIds.isEmpty() || normalizedSymbols.isEmpty()) {
            return Map.of();
        }
        List<ProjectedSymbolQuantity> rows = jdbcClient.sql(
                """
                select exposure.account_id,
                       exposure.symbol,
                       sum(exposure.quantity) as projected_quantity
                  from (
                       select h.account_id,
                              h.symbol,
                              h.quantity
                         from stock_holding h
                        where h.account_id in (:accountIds)
                          and h.symbol in (:symbols)
                          and h.quantity > 0
                       union all
                       select o.account_id,
                              o.symbol,
                              o.quantity - o.filled_quantity as quantity
                         from stock_order o
                        where o.account_id in (:accountIds)
                          and o.symbol in (:symbols)
                          and o.market_type = 'ORDER_BOOK'
                          and o.side = 'BUY'
                          and o.status in ('PENDING', 'PARTIALLY_FILLED')
                          and o.quantity > o.filled_quantity
                  ) exposure
                 group by exposure.account_id, exposure.symbol
                """
        )
                .param("accountIds", normalizedAccountIds)
                .param("symbols", normalizedSymbols)
                .query((rs, rowNum) -> new ProjectedSymbolQuantity(
                        rs.getLong("account_id"),
                        rs.getString("symbol"),
                        rs.getLong("projected_quantity")
                ))
                .list();
        Map<Long, Map<String, Long>> mutable = new LinkedHashMap<>();
        for (ProjectedSymbolQuantity row : rows) {
            mutable.computeIfAbsent(row.accountId(), ignored -> new LinkedHashMap<>())
                    .put(row.symbol(), Math.max(0L, row.quantity()));
        }
        Map<Long, Map<String, Long>> result = new LinkedHashMap<>();
        mutable.forEach((accountId, quantities) -> result.put(accountId, Map.copyOf(quantities)));
        return Map.copyOf(result);
    }

    public Optional<BigDecimal> findLatestPriceAtOrBefore(String symbol, LocalDateTime priceTime) {
        return jdbcClient.sql(
                """
                select price
                from stock_price_tick
                where symbol = :symbol
                  and price_time <= :priceTime
                order by price_time desc, id desc
                limit 1
                """
        )
                .param("symbol", AutoMarketReaderMapper.normalizeSymbol(symbol))
                .param("priceTime", priceTime)
                .query(BigDecimal.class)
                .optional()
                .filter(price -> price != null && price.compareTo(BigDecimal.ZERO) > 0);
    }

    private record ProjectedSymbolQuantity(long accountId, String symbol, long quantity) {
    }

    public Map<String, BigDecimal> findLatestPricesAtOrBefore(List<String> symbols, LocalDateTime priceTime) {
        List<String> normalizedSymbols = symbols.stream()
                .map(AutoMarketReaderMapper::normalizeSymbol)
                .filter(symbol -> !symbol.isBlank())
                .distinct()
                .toList();
        if (normalizedSymbols.isEmpty()) {
            return Map.of();
        }
        Map<String, BigDecimal> pricesBySymbol = new LinkedHashMap<>();
        for (int start = 0; start < normalizedSymbols.size(); start += LATEST_PRICE_SYMBOL_QUERY_CHUNK_SIZE) {
            int end = Math.min(normalizedSymbols.size(), start + LATEST_PRICE_SYMBOL_QUERY_CHUNK_SIZE);
            pricesBySymbol.putAll(findLatestPricesAtOrBeforeChunk(normalizedSymbols.subList(start, end), priceTime));
        }
        return Map.copyOf(pricesBySymbol);
    }

    public Map<String, AutoMarketHistoricalSignal> findHistoricalMarketSignals(
            List<String> symbols,
            LocalDate businessDate
    ) {
        List<String> normalizedSymbols = symbols.stream()
                .map(AutoMarketReaderMapper::normalizeSymbol)
                .filter(symbol -> !symbol.isBlank())
                .distinct()
                .toList();
        if (normalizedSymbols.isEmpty()) {
            return Map.of();
        }
        List<DailyMarketPoint> points = jdbcClient.sql(
                """
                select snapshot.symbol,
                       snapshot.simulation_trade_date,
                       snapshot.close_price,
                       snapshot.execution_quantity
                  from stock_order_book_daily_snapshot snapshot
                  join stock_post_close_cycle cycle
                    on cycle.close_run_id = snapshot.close_run_id
                   and cycle.scope_type = 'FULL_MARKET'
                   and cycle.scope_key = 'ALL'
                   and cycle.cycle_kind = 'TRADING'
                   and cycle.status = 'COMPLETED'
                 where snapshot.symbol in (:symbols)
                   and snapshot.simulation_trade_date < :businessDate
                   and snapshot.simulation_trade_date >= :historyStart
                 order by snapshot.symbol asc, snapshot.simulation_trade_date desc
                """
        )
                .param("symbols", normalizedSymbols)
                .param("businessDate", businessDate)
                .param("historyStart", businessDate.minusDays(45))
                .query((rs, rowNum) -> new DailyMarketPoint(
                        AutoMarketReaderMapper.normalizeSymbol(rs.getString("symbol")),
                        rs.getObject("simulation_trade_date", LocalDate.class),
                        rs.getBigDecimal("close_price"),
                        Math.max(0L, rs.getLong("execution_quantity"))
                ))
                .list();
        Map<String, List<DailyMarketPoint>> pointsBySymbol = points.stream()
                .collect(Collectors.groupingBy(
                        DailyMarketPoint::symbol,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        Map<String, AutoMarketHistoricalSignal> result = new LinkedHashMap<>();
        for (String symbol : normalizedSymbols) {
            result.put(symbol, historicalSignal(pointsBySymbol.getOrDefault(symbol, List.of())));
        }
        return Map.copyOf(result);
    }

    private AutoMarketHistoricalSignal historicalSignal(List<DailyMarketPoint> descendingPoints) {
        if (descendingPoints.isEmpty()) {
            return AutoMarketHistoricalSignal.EMPTY;
        }
        BigDecimal latestClose = descendingPoints.getFirst().closePrice();
        return new AutoMarketHistoricalSignal(
                trailingReturn(latestClose, descendingPoints, 1),
                trailingReturn(latestClose, descendingPoints, 3),
                trailingReturn(latestClose, descendingPoints, 5),
                trailingReturn(latestClose, descendingPoints, 10),
                trailingReturn(latestClose, descendingPoints, 20),
                averageVolume(descendingPoints, 5),
                averageVolume(descendingPoints, 20),
                descendingPoints.size()
        );
    }

    private double trailingReturn(
            BigDecimal latestClose,
            List<DailyMarketPoint> descendingPoints,
            int tradingDays
    ) {
        if (latestClose == null || latestClose.signum() <= 0 || descendingPoints.size() <= tradingDays) {
            return 0.0;
        }
        BigDecimal referenceClose = descendingPoints.get(tradingDays).closePrice();
        if (referenceClose == null || referenceClose.signum() <= 0) {
            return 0.0;
        }
        return latestClose.subtract(referenceClose)
                .divide(referenceClose, 8, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private long averageVolume(List<DailyMarketPoint> descendingPoints, int tradingDays) {
        int count = Math.min(tradingDays, descendingPoints.size());
        if (count <= 0) {
            return 0L;
        }
        return Math.round(descendingPoints.subList(0, count)
                .stream()
                .mapToLong(DailyMarketPoint::executionQuantity)
                .average()
                .orElse(0.0));
    }

    private record DailyMarketPoint(
            String symbol,
            LocalDate businessDate,
            BigDecimal closePrice,
            long executionQuantity
    ) {
    }

    private Map<String, BigDecimal> findLatestPricesAtOrBeforeChunk(
            List<String> normalizedSymbols,
            LocalDateTime priceTime
    ) {
        String sql = IntStream.range(0, normalizedSymbols.size())
                .mapToObj(index -> """
                        select %d as symbol_index,
                               (
                                   select price
                                   from stock_price_tick
                                   where symbol = :symbol%d
                                     and price_time <= :priceTime
                                   order by price_time desc, id desc
                                   limit 1
                               ) as price
                        """.formatted(index, index))
                .collect(Collectors.joining("\nunion all\n"));
        JdbcClient.StatementSpec statement = jdbcClient.sql(sql)
                .param("priceTime", priceTime);
        for (int index = 0; index < normalizedSymbols.size(); index++) {
            statement = statement.param("symbol" + index, normalizedSymbols.get(index));
        }
        return statement
                .query(rs -> {
                    Map<String, BigDecimal> pricesBySymbol = new LinkedHashMap<>();
                    while (rs.next()) {
                        BigDecimal price = rs.getBigDecimal("price");
                        if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                            pricesBySymbol.put(normalizedSymbols.get(rs.getInt("symbol_index")), price);
                        }
                    }
                    return pricesBySymbol;
                });
    }

}
