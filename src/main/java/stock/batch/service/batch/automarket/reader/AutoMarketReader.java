package stock.batch.service.batch.automarket.reader;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;
import stock.batch.service.batch.automarket.model.AutoParticipantTradingSnapshot;

@Component
public class AutoMarketReader {

    private final JdbcClient jdbcClient;

    public AutoMarketReader(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate));
    }

    public List<AutoMarketConfig> findEnabledConfigs() {
        return jdbcClient.sql(
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
                """
        )
                .query((rs, rowNum) -> AutoMarketReaderMapper.toConfig(rs))
                .list();
    }

    public List<AutoParticipantProfileConfig> findParticipantProfileConfigs() {
        return jdbcClient.sql(
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

    private List<ActiveParticipantStrategy> findActiveParticipantStrategies() {
        return jdbcClient.sql(
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
                )
                select a.id as account_id,
                       a.cash_balance,
                       coalesce(max(h.quantity - h.reserved_quantity), 0) as available_quantity,
                       coalesce(max(case when h.quantity > 0 then h.average_price else 0 end), 0) as average_price,
                       coalesce(max(f.recent_dividend_cash_amount), 0) as recent_dividend_cash_amount
                from scoped_accounts a
                left join stock_holding h
                  on h.account_id = a.id
                 and h.symbol = :symbol
                left join recent_dividend_cash_flows f on f.account_id = a.id
                group by a.id, a.cash_balance
                """
        )
                .param("symbol", symbol)
                .param("recentDividendSince", recentDividendSince)
                .param("accountIds", accountIds)
                .query((rs, rowNum) -> AutoMarketReaderMapper.toTradingSnapshot(rs))
                .list();
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

    public Map<String, BigDecimal> findLatestPricesAtOrBefore(List<String> symbols, LocalDateTime priceTime) {
        List<String> normalizedSymbols = symbols.stream()
                .map(AutoMarketReaderMapper::normalizeSymbol)
                .filter(symbol -> !symbol.isBlank())
                .distinct()
                .toList();
        if (normalizedSymbols.isEmpty()) {
            return Map.of();
        }
        return jdbcClient.sql(
                """
                select symbol,
                       price
                from (
                    select symbol,
                           price,
                           row_number() over (partition by symbol order by price_time desc, id desc) as latest_rank
                    from stock_price_tick
                    where symbol in (:symbols)
                      and price_time <= :priceTime
                ) latest
                where latest_rank = 1
                """
        )
                .param("symbols", normalizedSymbols)
                .param("priceTime", priceTime)
                .query(rs -> {
                    Map<String, BigDecimal> pricesBySymbol = new LinkedHashMap<>();
                    while (rs.next()) {
                        BigDecimal price = rs.getBigDecimal("price");
                        if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                            pricesBySymbol.put(
                                    AutoMarketReaderMapper.normalizeSymbol(rs.getString("symbol")),
                                    price
                            );
                        }
                    }
                    return pricesBySymbol;
                });
    }

}
