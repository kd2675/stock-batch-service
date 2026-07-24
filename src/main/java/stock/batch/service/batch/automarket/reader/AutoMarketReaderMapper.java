package stock.batch.service.batch.automarket.reader;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoMarketDistributionBias;
import stock.batch.service.batch.automarket.model.AutoOrder;
import stock.batch.service.batch.automarket.model.AutoParticipant;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantBehaviorModelVersion;
import stock.batch.service.batch.automarket.model.AutoParticipantRecentCashFlow;
import stock.batch.service.batch.automarket.model.AutoParticipantRecurringCashTarget;
import stock.batch.service.batch.automarket.model.AutoParticipantTradingSnapshot;
import stock.batch.service.batch.automarket.model.ListingAutoAccountConfig;
import stock.batch.service.batch.automarket.model.RecurringCashIntervalUnit;
import web.common.core.utils.DeterministicSeed;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

final class AutoMarketReaderMapper {

    private static final BigDecimal DEFAULT_TICK_SIZE = BigDecimal.valueOf(100);
    private static final BigDecimal DEFAULT_PRICE_LIMIT_RATE = BigDecimal.valueOf(30);

    private AutoMarketReaderMapper() {
    }

    static AutoParticipant toParticipant(ResultSet rs) throws SQLException {
        return new AutoParticipant(
                rs.getString("user_key"),
                rs.getString("display_name"),
                AutoParticipantProfileType.parseOrDefault(rs.getString("profile_type"))
        );
    }

    static AutoMarketConfig toConfig(ResultSet rs) throws SQLException {
        return new AutoMarketConfig(
                normalizeSymbol(rs.getString("symbol")),
                rs.getString("market"),
                Math.max(1, rs.getInt("max_order_quantity")),
                Math.max(1, rs.getInt("order_ttl_seconds")),
                Math.max(0L, rs.getLong("tradable_shares")),
                positiveOrDefault(rs.getBigDecimal("tick_size"), DEFAULT_TICK_SIZE),
                rs.getBigDecimal("current_price"),
                positiveOrDefault(rs.getBigDecimal("previous_close"), rs.getBigDecimal("current_price")),
                positiveOrDefault(rs.getBigDecimal("price_limit_rate"), DEFAULT_PRICE_LIMIT_RATE),
                nullableInt(rs, "report_score"),
                new AutoMarketDistributionBias(
                        rs.getInt("primary_price_pressure_bias"),
                        rs.getInt("primary_asset_preference_pressure_bias"),
                        rs.getInt("primary_volatility_pressure_bias"),
                        rs.getInt("primary_liquidity_pressure_bias"),
                        rs.getInt("primary_execution_aggression_pressure_bias")
                ),
                new AutoMarketDistributionBias(
                        rs.getInt("secondary_price_pressure_bias"),
                        rs.getInt("secondary_asset_preference_pressure_bias"),
                        rs.getInt("secondary_volatility_pressure_bias"),
                        rs.getInt("secondary_liquidity_pressure_bias"),
                        rs.getInt("secondary_execution_aggression_pressure_bias")
                ),
                rs.getTimestamp("report_created_at") == null
                        ? null
                        : rs.getTimestamp("report_created_at").toLocalDateTime()
        );
    }

    static AutoParticipantProfileConfig toProfileConfig(ResultSet rs) throws SQLException {
        return new AutoParticipantProfileConfig(
                AutoParticipantProfileType.parseOrDefault(rs.getString("profile_type")),
                AutoParticipantBehaviorModelVersion.parseOrDefault(rs.getString("behavior_model_version")),
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
                rs.getBigDecimal("decision_frequency_multiplier"),
                rs.getBigDecimal("orders_per_decision_multiplier"),
                rs.getBigDecimal("aggression_multiplier"),
                rs.getBigDecimal("price_pressure_sensitivity"),
                rs.getBigDecimal("order_ttl_multiplier"),
                rs.getBigDecimal("quantity_multiplier"),
                rs.getBigDecimal("holding_patience_weight"),
                rs.getBigDecimal("deep_loss_hold_weight"),
                rs.getBigDecimal("profit_taking_weight"),
                rs.getString("pricing_mode"),
                rs.getString("exit_mode"),
                rs.getString("inventory_mode"),
                rs.getBigDecimal("recurring_deposit_amount"),
                rs.getBigDecimal("recurring_deposit_interval_value"),
                RecurringCashIntervalUnit.parseOrNull(rs.getString("recurring_deposit_interval_unit")),
                rs.getInt("recurring_deposit_interval_days")
        );
    }

    static AutoParticipantRecurringCashTarget toRecurringCashTarget(ResultSet rs) throws SQLException {
        return new AutoParticipantRecurringCashTarget(
                rs.getLong("account_id"),
                AutoParticipantProfileType.parseOrDefault(rs.getString("profile_type")),
                rs.getBigDecimal("recurring_cash_amount"),
                rs.getBigDecimal("recurring_cash_interval_value"),
                RecurringCashIntervalUnit.parseOrNull(rs.getString("recurring_cash_interval_unit"))
        );
    }

    private static long behaviorSeed(ResultSet rs) throws SQLException {
        long value = rs.getLong("behavior_seed");
        if (!rs.wasNull()) {
            return value;
        }
        return DeterministicSeed.fromUtf8(rs.getString("user_key"));
    }

    static ActiveParticipantStrategy toActiveParticipantStrategy(ResultSet rs) throws SQLException {
        return new ActiveParticipantStrategy(
                rs.getString("user_key"),
                rs.getLong("account_id"),
                AutoParticipantProfileType.parseOrDefault(rs.getString("profile_type")),
                rs.getBigDecimal("recurring_cash_amount"),
                rs.getBigDecimal("recurring_cash_interval_value"),
                RecurringCashIntervalUnit.parseOrNull(rs.getString("recurring_cash_interval_unit")),
                AutoParticipantBehaviorModelVersion.parseOrDefault(rs.getString("behavior_model_version")),
                behaviorSeed(rs)
        );
    }

    static ParticipantSymbolStrategyConfig toParticipantSymbolStrategyConfig(ResultSet rs) throws SQLException {
        return new ParticipantSymbolStrategyConfig(
                rs.getString("user_key"),
                rs.getString("symbol"),
                rs.getBoolean("enabled"),
                rs.getInt("intensity")
        );
    }

    static AutoParticipantRecentCashFlow toRecentCashFlow(ResultSet rs) throws SQLException {
        return new AutoParticipantRecentCashFlow(
                rs.getLong("account_id"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toLocalDateTime()
        );
    }

    static ListingAutoAccountConfig toListingAutoAccountConfig(ResultSet rs) throws SQLException {
        return new ListingAutoAccountConfig(
                normalizeSymbol(rs.getString("symbol")),
                rs.getString("market"),
                rs.getLong("account_id"),
                rs.getString("user_key"),
                rs.getString("position_side"),
                rs.getString("operation_mode"),
                rs.getString("strategy_profile"),
                Math.max(0L, rs.getLong("initial_inventory_quantity")),
                positiveOrDefault(rs.getBigDecimal("initial_issue_price"), BigDecimal.ONE),
                Math.max(1, rs.getInt("max_order_quantity")),
                Math.max(1, rs.getInt("order_ttl_seconds")),
                Math.max(0, rs.getInt("price_offset_ticks")),
                Math.clamp(rs.getInt("target_spread_ticks"), 1, 50),
                Math.clamp(rs.getInt("inventory_skew_ticks"), 0, 50),
                rs.getBigDecimal("minimum_profit_rate"),
                rs.getBigDecimal("aggressive_unwind_threshold"),
                rs.getBigDecimal("aggressive_order_ratio"),
                Math.max(0L, rs.getLong("target_buy_quantity")),
                Math.max(0L, rs.getLong("target_sell_quantity")),
                Math.max(0L, rs.getLong("target_holding_quantity")),
                Math.max(0L, rs.getLong("inventory_band_quantity")),
                positiveOrDefault(rs.getBigDecimal("tick_size"), DEFAULT_TICK_SIZE),
                rs.getBigDecimal("current_price"),
                positiveOrDefault(rs.getBigDecimal("previous_close"), rs.getBigDecimal("current_price")),
                positiveOrDefault(rs.getBigDecimal("price_limit_rate"), DEFAULT_PRICE_LIMIT_RATE)
        );
    }

    static AutoOrder toAutoParticipantOrder(ResultSet rs) throws SQLException {
        return new AutoOrder(
                rs.getLong("id"),
                rs.getLong("account_id"),
                rs.getString("symbol"),
                rs.getString("side"),
                rs.getLong("quantity"),
                rs.getLong("filled_quantity"),
                rs.getBigDecimal("reserved_cash"),
                rs.getBigDecimal("limit_price"),
                AutoParticipantProfileType.parseOrDefault(rs.getString("profile_type")),
                stock.batch.service.batch.automarket.model.AutoParticipantBehaviorModelVersion.parseOrDefault(
                        rs.getString("behavior_model_version")
                ),
                rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toLocalDateTime(),
                rs.getTimestamp("created_at").toLocalDateTime()
        );
    }

    static AutoOrder toListingAutoAccountOrder(ResultSet rs) throws SQLException {
        return new AutoOrder(
                rs.getLong("id"),
                rs.getLong("account_id"),
                rs.getString("symbol"),
                rs.getString("side"),
                rs.getLong("quantity"),
                rs.getLong("filled_quantity"),
                rs.getBigDecimal("reserved_cash")
        );
    }

    static AutoParticipantTradingSnapshot toTradingSnapshot(ResultSet rs) throws SQLException {
        return new AutoParticipantTradingSnapshot(
                rs.getLong("account_id"),
                zeroIfNull(rs.getBigDecimal("cash_balance")),
                Math.max(0L, rs.getLong("available_quantity")),
                zeroIfNull(rs.getBigDecimal("average_price")),
                zeroIfNull(rs.getBigDecimal("recent_dividend_cash_amount")),
                Math.max(0L, rs.getLong("open_buy_quantity")),
                Math.max(0L, rs.getLong("open_sell_quantity")),
                Math.max(0L, rs.getLong("holding_quantity")),
                Math.max(0L, rs.getLong("reserved_quantity")),
                zeroIfNull(rs.getBigDecimal("open_buy_reserved_cash")),
                zeroIfNull(rs.getBigDecimal("portfolio_holding_market_value")),
                zeroIfNull(rs.getBigDecimal("liquid_portfolio_asset")),
                Math.max(0, rs.getInt("portfolio_position_count")),
                zeroIfNull(rs.getBigDecimal("payday_available_budget")),
                zeroIfNull(rs.getBigDecimal("dividend_available_budget")),
                rs.getObject("position_opened_business_date", java.time.LocalDate.class),
                Math.max(0, rs.getInt("holding_trading_days")),
                Math.max(0, rs.getInt("average_down_rounds")),
                rs.getObject("last_average_down_business_date", java.time.LocalDate.class),
                zeroIfNull(rs.getBigDecimal("peak_close_price")),
                Math.max(0, rs.getInt("recent_profitable_trading_days")),
                Math.max(0, rs.getInt("recent_closed_trading_days"))
        );
    }

    static AutoParticipantTradingSnapshot toLegacyTradingSnapshot(ResultSet rs) throws SQLException {
        return new AutoParticipantTradingSnapshot(
                rs.getLong("account_id"),
                zeroIfNull(rs.getBigDecimal("cash_balance")),
                Math.max(0L, rs.getLong("available_quantity")),
                zeroIfNull(rs.getBigDecimal("average_price")),
                zeroIfNull(rs.getBigDecimal("recent_dividend_cash_amount")),
                Math.max(0L, rs.getLong("open_buy_quantity")),
                Math.max(0L, rs.getLong("open_sell_quantity"))
        );
    }

    static String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private static Integer nullableInt(ResultSet rs, String columnName) throws SQLException {
        int value = rs.getInt(columnName);
        return rs.wasNull() ? null : value;
    }

    private static BigDecimal positiveOrDefault(BigDecimal value, BigDecimal defaultValue) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0 ? defaultValue : value;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

}
