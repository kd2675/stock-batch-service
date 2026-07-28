package stock.batch.service.common.config;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.config.BatchRepositoryDataSourceConfig;

@Component
@ConditionalOnProperty(
        name = "stock.batch.schema-readiness.enabled",
        havingValue = "true",
        matchIfMissing = true
)
@Slf4j
public class StockSchemaReadinessValidator implements SmartInitializingSingleton {

    private static final Pattern CHECK_LITERAL_PATTERN =
            Pattern.compile("'((?:''|[^'])*)'");
    private static final Set<String> AUTO_PARTICIPANT_PROFILE_CHECK_TOKENS =
            Arrays.stream(AutoParticipantProfileType.values())
                    .map(profileType -> profileType.name().toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
    private static final Map<String, Set<String>> FORBIDDEN_LEGACY_COLUMNS = Map.of(
            "stock_auto_participant", Set.of("behavior_evaluation_mode", "behavior_model_version"),
            "stock_auto_participant_share_return", Set.of("underwriter_account_id")
    );
    private static final Set<String> FORBIDDEN_LEGACY_TABLES = Set.of(
            "stock_auto_profile_decision_day_summary",
            "stock_listing_auto_account_config"
    );
    private static final Set<String> FORBIDDEN_LEGACY_CHECKS = Set.of(
            "chk_stock_auto_participant_behavior_evaluation",
            "chk_stock_auto_participant_behavior_rollout_pair",
            "chk_stock_auto_participant_funding_shadow",
            "chk_stock_auto_participant_behavior_model"
    );
    private static final Map<String, Set<String>> FORBIDDEN_CHECK_TOKENS = Map.ofEntries(
            Map.entry("chk_stock_institution_portfolio_mode", Set.of("shadow", "pilot")),
            Map.entry("chk_stock_institution_decision_run_mode", Set.of("shadow", "pilot")),
            Map.entry("chk_stock_liquidity_mandate_mode", Set.of("shadow", "pilot")),
            Map.entry("chk_stock_liquidity_daily_state_status", Set.of("shadow")),
            Map.entry("chk_stock_liquidity_transition_stage", Set.of("shadow_ready")),
            Map.entry("chk_stock_liquidity_transition_activation", Set.of("shadow_ready")),
            Map.entry("chk_stock_auto_profile_inventory_mode", Set.of("target_allocation"))
    );
    private static final Map<String, Set<String>> REQUIRED_COLUMNS = requiredColumns();
    private static final Map<String, Set<String>> REQUIRED_NOT_NULL_COLUMNS = Map.ofEntries(
            Map.entry("stock_account", Set.of("participant_category")),
            Map.entry("portfolio_snapshot", Set.of("pending_subscription_asset", "return_rate_status")),
            Map.entry("stock_corporate_action_entitlement", Set.of("forfeited_share_quantity")),
            Map.entry("stock_close_open_order_snapshot", Set.of("source_order_status")),
            Map.entry("stock_auto_participant_profile_config", Set.of(
                    "behavior_model_version",
                    "decision_frequency_multiplier", "orders_per_decision_multiplier",
                    "pricing_mode", "exit_mode", "inventory_mode"
            )),
            Map.entry("stock_auto_participant_position_state", Set.of(
                    "position_opened_business_date", "holding_trading_days", "average_down_rounds",
                    "peak_close_price", "last_seen_business_date"
            )),
            Map.entry("stock_auto_participant_performance_state", Set.of(
                    "recent_profitable_trading_days", "recent_closed_trading_days", "last_seen_business_date"
            )),
            Map.entry("stock_auto_participant_funding_budget", Set.of(
                    "account_id", "budget_type", "source_key", "granted_amount", "available_amount",
                    "reserved_amount", "spent_amount", "status", "created_at", "updated_at"
            )),
            Map.entry("stock_auto_participant_order_budget", Set.of(
                    "order_id", "budget_id", "allocated_amount", "remaining_reserved_amount",
                    "spent_amount", "released_amount", "created_at", "updated_at"
            )),
            Map.entry("stock_order_strategy_origin", Set.of(
                    "order_id", "origin_type", "participant_id",
                    "policy_version", "created_at"
            )),
            Map.entry("stock_institution_portfolio", Set.of(
                    "participant_id", "account_id", "portfolio_code", "display_name",
                    "investment_style", "execution_mode", "status",
                    "base_stock_allocation_rate", "min_stock_allocation_rate",
                    "max_stock_allocation_rate", "primary_regime_weight",
                    "asset_preference_sensitivity", "volatility_sensitivity",
                    "entry_threshold_rate", "exit_threshold_rate",
                    "daily_turnover_limit_rate", "max_decision_turnover_rate",
                    "decision_interval_minutes", "policy_version", "created_at", "updated_at"
            )),
            Map.entry("stock_institution_symbol_mandate", Set.of(
                    "portfolio_id", "symbol", "base_symbol_weight",
                    "min_portfolio_allocation_rate", "max_portfolio_allocation_rate",
                    "price_pressure_sensitivity", "momentum_sensitivity",
                    "value_sensitivity", "report_sensitivity", "reference_daily_volume",
                    "daily_participation_rate", "enabled", "created_at", "updated_at"
            )),
            Map.entry("stock_institution_decision_run", Set.of(
                    "decision_slot", "simulation_trade_date", "portfolio_id",
                    "execution_mode", "policy_version", "deterministic_seed",
                    "status", "created_at"
            )),
            Map.entry("stock_institution_decision_item", Set.of(
                    "decision_run_id", "symbol",
                    "primary_price_pressure", "primary_asset_preference_pressure",
                    "primary_volatility_pressure", "primary_liquidity_pressure",
                    "primary_execution_aggression_pressure", "secondary_price_pressure",
                    "secondary_asset_preference_pressure", "secondary_volatility_pressure",
                    "secondary_liquidity_pressure", "secondary_execution_aggression_pressure",
                    "blended_price_pressure", "blended_asset_preference_pressure",
                    "blended_volatility_pressure", "blended_liquidity_pressure",
                    "blended_execution_aggression_pressure", "return_5_day",
                    "return_20_day", "report_pressure", "current_price",
                    "liquid_asset_amount", "actual_quantity", "open_buy_quantity",
                    "open_sell_quantity", "projected_quantity", "actual_allocation_rate",
                    "projected_allocation_rate", "base_allocation_rate",
                    "target_stock_allocation_rate", "target_allocation_rate",
                    "target_amount", "raw_trade_amount", "gated_trade_amount",
                    "gated_quantity", "action", "decision_reason", "gate_reason",
                    "reference_daily_volume", "remaining_daily_quantity_budget",
                    "remaining_daily_notional_budget", "created_at"
            )),
            Map.entry("stock_institution_daily_budget", Set.of(
                    "simulation_trade_date", "portfolio_id", "symbol",
                    "reference_daily_volume", "gross_quantity_limit", "gross_notional_limit",
                    "planned_buy_quantity", "planned_sell_quantity", "planned_buy_amount",
                    "planned_sell_amount", "submitted_buy_amount", "submitted_sell_amount",
                    "executed_buy_amount", "executed_sell_amount", "policy_version",
                    "version", "created_at", "updated_at"
            )),
            Map.entry("stock_institution_order_intent", Set.of(
                    "decision_run_id", "symbol", "portfolio_id", "participant_id",
                    "account_id", "side", "requested_quantity", "planned_amount",
                    "reference_daily_volume",
                    "execution_aggression_pressure", "policy_version", "status",
                    "attempt_count", "submitted_quantity", "created_at", "updated_at"
            )),
            Map.entry("stock_liquidity_mandate", Set.of(
                    "participant_id", "account_id", "symbol", "mandate_code",
                    "execution_mode", "status", "contract_start_date",
                    "target_spread_ticks", "max_spread_ticks", "max_order_quantity",
                    "reference_daily_volume", "target_open_participation_rate",
                    "max_open_participation_rate", "max_single_order_participation_rate",
                    "external_depth_levels", "max_external_depth_participation_rate",
                    "daily_execution_participation_rate", "daily_submission_multiplier",
                    "target_inventory_quantity", "inventory_band_quantity",
                    "inventory_skew_ticks", "primary_regime_weight",
                    "liquidity_size_sensitivity", "volatility_spread_max_ticks",
                    "price_regime_max_skew_ticks", "passive_only",
                    "minimum_quote_lifetime_seconds", "reprice_threshold_ticks",
                    "order_ttl_seconds",
                    "quote_interval_seconds", "daily_loss_limit_amount",
                    "policy_version", "created_at", "updated_at"
            )),
            Map.entry("stock_liquidity_daily_state", Set.of(
                    "simulation_trade_date", "mandate_id", "reference_daily_volume",
                    "execution_quantity_limit", "submission_quantity_limit",
                    "submitted_buy_quantity", "submitted_sell_quantity",
                    "submitted_buy_amount", "submitted_sell_amount",
                    "cancelled_buy_quantity", "cancelled_sell_quantity",
                    "executed_buy_quantity", "executed_sell_quantity",
                    "executed_buy_amount", "executed_sell_amount", "realized_profit",
                    "unrealized_profit", "opening_net_asset_value",
                    "current_net_asset_value", "risk_profit",
                    "target_buy_open_quantity", "target_sell_open_quantity",
                    "last_open_buy_quantity", "last_open_sell_quantity",
                    "external_buy_depth_quantity", "external_sell_depth_quantity",
                    "last_inventory_quantity", "last_projected_inventory_quantity",
                    "blended_price_pressure", "blended_volatility_pressure",
                    "blended_liquidity_pressure", "state_status", "gate_reason",
                    "quote_run_count", "limit_breached", "policy_version",
                    "version", "created_at", "updated_at"
            )),
            Map.entry("stock_liquidity_transition", Set.of(
                    "transition_key", "symbol", "mandate_id", "participant_id",
                    "liquidity_account_id", "source_account_id",
                    "stage", "reference_daily_volume", "seed_inventory_quantity",
                    "seed_cash_amount", "transferred_inventory_quantity",
                    "transferred_cash_amount", "effective_business_date",
                    "requested_by", "change_reason", "policy_version",
                    "created_at", "updated_at"
            )),
            Map.entry("stock_underwriting_contract", Set.of(
                    "contract_code", "symbol", "participant_id", "account_id",
                    "total_issue_quantity", "tradable_allocation_quantity",
                    "locked_allocation_quantity", "external_allocation_quantity",
                    "underwritten_quantity", "issue_price", "underwriting_type",
                    "stabilization_quantity_limit", "stabilization_amount_limit",
                    "status", "policy_version", "created_at", "updated_at"
            )),
            Map.entry("stock_underwriting_daily_supply_state", Set.of(
                    "simulation_trade_date", "underwriting_contract_id",
                    "reference_daily_volume", "submission_quantity_limit",
                    "submission_amount_limit", "submitted_quantity", "submitted_amount",
                    "generated_order_count", "cancelled_order_count",
                    "state_status", "gate_reason",
                    "policy_version", "version", "created_at", "updated_at"
            )),
            Map.entry("stock_security_allocation_ledger", Set.of(
                    "idempotency_key", "event_type", "destination_account_id",
                    "symbol", "quantity", "unit_price", "allocation_reason",
                    "tradability_status", "effective_business_date", "created_at"
            )),
            Map.entry("stock_post_close_cycle", Set.of("eod_contract_version")),
            Map.entry("stock_post_close_phase_attempt", Set.of("eod_contract_version"))
    );
    private static final Map<String, Set<String>> REQUIRED_CHECK_TOKENS = Map.ofEntries(
            Map.entry("chk_stock_account_participant_category", Set.of(
                    "manual_participant", "auto_participant",
                    "institutional_investor", "liquidity_provider", "issue_underwriter", "system_custody"
            )),
            Map.entry("chk_stock_account_self_trade_group", Set.of("self_trade_group_id")),
            Map.entry("chk_stock_corporate_action_paid_date_order", Set.of("record_date")),
            Map.entry("chk_stock_corporate_action_paid_schedule_required", Set.of("record_date")),
            Map.entry("chk_stock_corporate_action_entitlement_status", Set.of("partially_subscribed")),
            Map.entry("chk_stock_corporate_action_entitlement_finalized_share_limit", Set.of("forfeited_share_quantity")),
            Map.entry("chk_portfolio_snapshot_return_contract", Set.of(
                    "defined", "undefined_zero_contribution", "undefined_negative_contribution", "legacy_unverified"
            )),
            Map.entry("chk_stock_auto_profile_behavior_model", Set.of("v3")),
            Map.entry("chk_stock_auto_participant_profile_type", AUTO_PARTICIPANT_PROFILE_CHECK_TOKENS),
            Map.entry("chk_stock_auto_profile_config_type", AUTO_PARTICIPANT_PROFILE_CHECK_TOKENS),
            Map.entry("chk_stock_auto_profile_decision_frequency", Set.of("decision_frequency_multiplier")),
            Map.entry("chk_stock_auto_profile_orders_per_decision", Set.of("orders_per_decision_multiplier")),
            Map.entry("chk_stock_auto_profile_pricing_mode", Set.of("directional")),
            Map.entry("chk_stock_auto_profile_exit_mode", Set.of("signal_driven", "take_profit_first", "hold_losses")),
            Map.entry("chk_stock_auto_profile_inventory_mode", Set.of("signal_driven")),
            Map.entry("chk_stock_order_funding_budget_type", Set.of("payday", "dividend")),
            Map.entry("chk_stock_order_auto_behavior_model", Set.of("v3")),
            Map.entry("chk_stock_order_auto_profile_type", AUTO_PARTICIPANT_PROFILE_CHECK_TOKENS),
            Map.entry("chk_stock_order_strategy_origin_type", Set.of(
                    "institutional_investor", "liquidity_provider", "issue_underwriter"
            )),
            Map.entry("chk_stock_order_strategy_owner", Set.of(
                    "portfolio_id", "decision_run_id", "liquidity_mandate_id",
                    "underwriting_contract_id"
            )),
            Map.entry("chk_stock_market_participant_type", Set.of(
                    "institutional_investor", "liquidity_provider", "issue_underwriter", "system_custody"
            )),
            Map.entry("chk_stock_market_participant_account_role", Set.of(
                    "institutional_investor", "liquidity_provider", "issue_underwriter", "system_custody"
            )),
            Map.entry("chk_stock_market_policy_scope", Set.of(
                    "global_risk", "auto_participant", "institutional_portfolio",
                    "liquidity_mandate", "underwriting_contract"
            )),
            Map.entry("chk_stock_auto_position_holding_days", Set.of("holding_trading_days")),
            Map.entry("chk_stock_auto_position_average_down_rounds", Set.of("average_down_rounds")),
            Map.entry("chk_stock_auto_performance_recent_days", Set.of(
                    "recent_profitable_trading_days", "recent_closed_trading_days"
            )),
            Map.entry("chk_stock_auto_funding_budget_type", Set.of("payday", "dividend")),
            Map.entry("chk_stock_auto_funding_budget_status", Set.of("active", "exhausted", "expired")),
            Map.entry("chk_stock_auto_funding_budget_amounts", Set.of("available_amount", "reserved_amount", "spent_amount")),
            Map.entry("chk_stock_auto_funding_budget_source", Set.of("corporate_action_id", "corporate_action_entitlement_id")),
            Map.entry("chk_stock_auto_order_budget_amounts", Set.of("remaining_reserved_amount", "spent_amount", "released_amount")),
            Map.entry("chk_stock_institution_portfolio_style", Set.of(
                    "balanced_long_term", "value_contrarian", "momentum", "active_short_term"
            )),
            Map.entry("chk_stock_institution_portfolio_mode", Set.of("live")),
            Map.entry("chk_stock_institution_portfolio_threshold", Set.of(
                    "exit_threshold_rate", "entry_threshold_rate"
            )),
            Map.entry("chk_stock_institution_mandate_sensitivity", Set.of(
                    "price_pressure_sensitivity", "momentum_sensitivity",
                    "value_sensitivity", "report_sensitivity"
            )),
            Map.entry("chk_stock_institution_decision_run_mode", Set.of("live")),
            Map.entry("chk_stock_institution_decision_run_status", Set.of(
                    "claimed", "completed", "failed"
            )),
            Map.entry("chk_stock_institution_decision_item_action", Set.of(
                    "buy", "sell", "hold"
            )),
            Map.entry("chk_stock_institution_daily_budget_usage", Set.of(
                    "planned_buy_quantity", "planned_sell_quantity",
                    "gross_quantity_limit", "gross_notional_limit"
            )),
            Map.entry("chk_stock_institution_order_intent_status", Set.of(
                    "pending", "submitted", "rejected", "failed"
            )),
            Map.entry("chk_stock_institution_order_intent_submission", Set.of(
                    "submitted_order_id", "submitted_price", "submitted_quantity", "submitted_at"
            )),
            Map.entry("chk_stock_liquidity_mandate_mode", Set.of("live")),
            Map.entry("chk_stock_liquidity_mandate_status", Set.of(
                    "pending", "active", "suspended", "expired"
            )),
            Map.entry("chk_stock_liquidity_mandate_spread", Set.of(
                    "target_spread_ticks", "max_spread_ticks"
            )),
            Map.entry("chk_stock_liquidity_mandate_volume", Set.of(
                    "reference_daily_volume", "target_open_participation_rate",
                    "max_open_participation_rate", "max_single_order_participation_rate",
                    "external_depth_levels", "max_external_depth_participation_rate",
                    "daily_execution_participation_rate", "daily_submission_multiplier"
            )),
            Map.entry("chk_stock_liquidity_mandate_inventory", Set.of(
                    "target_inventory_quantity", "inventory_band_quantity", "inventory_skew_ticks"
            )),
            Map.entry("chk_stock_liquidity_mandate_regime", Set.of(
                    "primary_regime_weight", "liquidity_size_sensitivity",
                    "volatility_spread_max_ticks", "price_regime_max_skew_ticks"
            )),
            Map.entry("chk_stock_liquidity_mandate_timing", Set.of(
                    "minimum_quote_lifetime_seconds", "reprice_threshold_ticks",
                    "order_ttl_seconds", "quote_interval_seconds"
            )),
            Map.entry("chk_stock_liquidity_mandate_loss", Set.of("daily_loss_limit_amount")),
            Map.entry("chk_stock_liquidity_mandate_version", Set.of("policy_version")),
            Map.entry("chk_stock_liquidity_daily_state_limit", Set.of(
                    "reference_daily_volume", "execution_quantity_limit", "submission_quantity_limit"
            )),
            Map.entry("chk_stock_liquidity_daily_state_quantity", Set.of(
                    "submitted_buy_quantity", "submitted_sell_quantity",
                    "submitted_buy_amount", "submitted_sell_amount",
                    "executed_buy_quantity", "executed_sell_quantity",
                    "executed_buy_amount", "executed_sell_amount"
            )),
            Map.entry("chk_stock_liquidity_daily_state_price", Set.of(
                    "last_bid_price", "last_ask_price"
            )),
            Map.entry("chk_stock_liquidity_daily_state_pressure", Set.of(
                    "blended_price_pressure", "blended_volatility_pressure",
                    "blended_liquidity_pressure"
            )),
            Map.entry("chk_stock_liquidity_daily_state_status", Set.of(
                    "quoting", "exempt", "halted", "error"
            )),
            Map.entry("chk_stock_liquidity_daily_state_version", Set.of(
                    "quote_run_count", "policy_version", "version"
            )),
            Map.entry("chk_stock_liquidity_transition_stage", Set.of(
                    "pending_activation", "live_active", "suspended"
            )),
            Map.entry("chk_stock_liquidity_transition_seed", Set.of(
                    "reference_daily_volume", "seed_inventory_quantity", "seed_cash_amount"
            )),
            Map.entry("chk_stock_liquidity_transition_activation", Set.of(
                    "stage", "activated_at"
            )),
            Map.entry("chk_stock_liquidity_transition_audit", Set.of(
                    "transition_key", "requested_by", "change_reason", "policy_version"
            )),
            Map.entry("chk_stock_underwriting_contract_quantity", Set.of(
                    "total_issue_quantity", "tradable_allocation_quantity",
                    "locked_allocation_quantity", "external_allocation_quantity",
                    "underwritten_quantity"
            )),
            Map.entry("chk_stock_underwriting_contract_type", Set.of(
                    "firm_commitment", "best_efforts"
            )),
            Map.entry("chk_stock_underwriting_contract_status", Set.of(
                    "allocated", "stabilizing", "completed", "cancelled"
            )),
            Map.entry("chk_stock_underwriting_supply_limits", Set.of(
                    "reference_daily_volume", "submission_quantity_limit",
                    "submission_amount_limit"
            )),
            Map.entry("chk_stock_underwriting_supply_usage", Set.of(
                    "submitted_quantity", "submitted_amount",
                    "submission_quantity_limit", "submission_amount_limit",
                    "generated_order_count", "cancelled_order_count"
            )),
            Map.entry("chk_stock_underwriting_supply_status", Set.of(
                    "active", "gated", "completed", "suspended"
            )),
            Map.entry("chk_stock_security_allocation_event", Set.of(
                    "initial_issue", "capital_increase", "lock_release", "manual_reallocation"
            )),
            Map.entry("chk_stock_security_allocation_reason", Set.of(
                    "initial_float_custody", "initial_float_underwriter",
                    "initial_locked_custody",
                    "public_allocation", "unsold_underwriting",
                    "corporate_action_allocation", "lock_release",
                    "liquidity_seed_transfer"
            )),
            Map.entry("chk_stock_security_allocation_tradability", Set.of(
                    "tradable", "locked"
            )),
            Map.entry("chk_stock_post_close_cycle_eod_contract", Set.of("eod_contract_version")),
            Map.entry("chk_stock_post_close_phase_attempt_eod_contract", Set.of("eod_contract_version"))
    );
    private static final Map<String, Set<String>> REQUIRED_EXACT_CHECK_LITERALS = Map.ofEntries(
            Map.entry("chk_stock_account_participant_category", Set.of(
                    "manual_participant", "auto_participant", "institutional_investor",
                    "liquidity_provider", "issue_underwriter", "system_custody"
            )),
            Map.entry("chk_stock_close_account_snapshot_participant_category", Set.of(
                    "manual_participant", "auto_participant", "institutional_investor",
                    "liquidity_provider", "issue_underwriter", "system_custody"
            )),
            Map.entry("chk_stock_execution_daily_account_category", Set.of(
                    "manual_participant", "auto_participant", "institutional_investor",
                    "liquidity_provider", "issue_underwriter", "system_custody"
            )),
            Map.entry("chk_stock_order_origin_type", Set.of(
                    "manual_participant", "auto_participant", "institutional_investor",
                    "liquidity_provider", "issue_underwriter"
            )),
            Map.entry("chk_stock_auto_share_return_receiver_role", Set.of(
                    "issue_underwriter", "system_custody"
            )),
            Map.entry("chk_stock_auto_share_return_reason", Set.of(
                    "issue_underwriter_return", "auto_participant_withdrawal_custody"
            )),
            Map.entry("chk_stock_auto_profile_behavior_model", Set.of("v3")),
            Map.entry("chk_stock_auto_profile_pricing_mode", Set.of("directional")),
            Map.entry("chk_stock_auto_profile_inventory_mode", Set.of("signal_driven"))
    );
    private static final Map<String, Set<String>> REQUIRED_INDEXES = Map.ofEntries(
            Map.entry("stock_account_cash_flow", Set.of(
                    "idx_stock_account_cash_flow_account_id",
                    "idx_stock_account_cash_flow_corporate_action"
            )),
            Map.entry("stock_auto_participant_cash_flow_run", Set.of("idx_stock_auto_participant_cash_flow_run_completed")),
            Map.entry("stock_corporate_action_entitlement", Set.of(
                    "idx_stock_corporate_action_entitlement_action_status_id",
                    "idx_stock_corporate_action_entitlement_account_status"
            )),
            Map.entry("stock_corporate_action", Set.of("idx_stock_corporate_action_entitlement_close")),
            Map.entry("stock_close_account_snapshot", Set.of(
                    "idx_stock_close_account_snapshot_cycle_target",
                    "idx_stock_close_account_snapshot_cycle_reconciliation"
            )),
            Map.entry("stock_close_open_order_snapshot", Set.of(
                    "idx_stock_close_open_order_snapshot_cycle_release_order",
                    "idx_stock_close_open_order_snapshot_cycle_stream"
            )),
            Map.entry("stock_batch_job_signal", Set.of("idx_stock_batch_job_signal_cycle_id")),
            Map.entry("stock_order", Set.of(
                    "idx_stock_order_market_status_symbol",
                    "idx_stock_order_account_status_created"
            )),
            Map.entry("stock_market_participant", Set.of(
                    "uk_stock_market_participant_code",
                    "uk_stock_market_participant_self_trade_group",
                    "idx_stock_market_participant_type_status"
            )),
            Map.entry("stock_market_participant_account", Set.of(
                    "uk_stock_market_participant_account",
                    "uk_stock_market_participant_role_desk",
                    "idx_stock_market_participant_account_lookup"
            )),
            Map.entry("stock_market_policy_version", Set.of(
                    "uk_stock_market_policy_version",
                    "idx_stock_market_policy_effective"
            )),
            Map.entry("stock_order_strategy_origin", Set.of(
                    "idx_stock_order_strategy_participant",
                    "idx_stock_order_strategy_decision",
                    "idx_stock_order_strategy_liquidity",
                    "idx_stock_order_strategy_underwriting"
            )),
            Map.entry("stock_post_close_cycle", Set.of(
                    "idx_stock_post_close_cycle_scope_date_status",
                    "idx_stock_post_close_cycle_scope_status_date"
            )),
            Map.entry("stock_post_close_phase_attempt", Set.of("idx_stock_post_close_phase_attempt_cycle_id")),
            Map.entry("stock_auto_participant_position_state", Set.of("idx_stock_auto_position_last_seen")),
            Map.entry("stock_auto_participant_performance_state", Set.of(
                    "idx_stock_auto_performance_last_seen"
            )),
            Map.entry("stock_auto_participant_funding_budget", Set.of(
                    "uk_stock_auto_funding_budget_source",
                    "idx_stock_auto_funding_budget_eligible",
                    "idx_stock_auto_funding_budget_symbol",
                    "idx_stock_auto_funding_budget_action"
            )),
            Map.entry("stock_auto_participant_order_budget", Set.of("idx_stock_auto_order_budget_budget")),
            Map.entry("stock_institution_portfolio", Set.of(
                    "uk_stock_institution_portfolio_code",
                    "uk_stock_institution_portfolio_account",
                    "idx_stock_institution_portfolio_due"
            )),
            Map.entry("stock_institution_symbol_mandate", Set.of(
                    "uk_stock_institution_symbol_mandate",
                    "idx_stock_institution_symbol_mandate_symbol"
            )),
            Map.entry("stock_institution_decision_run", Set.of(
                    "uk_stock_institution_decision_slot",
                    "idx_stock_institution_decision_run_date"
            )),
            Map.entry("stock_institution_decision_item", Set.of(
                    "idx_stock_institution_decision_item_symbol"
            )),
            Map.entry("stock_institution_daily_budget", Set.of(
                    "idx_stock_institution_daily_budget_portfolio",
                    "idx_stock_institution_daily_budget_symbol"
            )),
            Map.entry("stock_institution_order_intent", Set.of(
                    "uk_stock_institution_order_intent_order",
                    "idx_stock_institution_order_intent_pending",
                    "idx_stock_institution_order_intent_portfolio"
            )),
            Map.entry("stock_liquidity_mandate", Set.of(
                    "uk_stock_liquidity_mandate_code",
                    "uk_stock_liquidity_mandate_account",
                    "uk_stock_liquidity_mandate_symbol",
                    "idx_stock_liquidity_mandate_due",
                    "idx_stock_liquidity_mandate_participant"
            )),
            Map.entry("stock_liquidity_daily_state", Set.of(
                    "idx_stock_liquidity_daily_state_mandate",
                    "idx_stock_liquidity_daily_state_status"
            )),
            Map.entry("stock_liquidity_transition", Set.of(
                    "uk_stock_liquidity_transition_key",
                    "uk_stock_liquidity_transition_symbol",
                    "uk_stock_liquidity_transition_mandate",
                    "uk_stock_liquidity_transition_account",
                    "idx_stock_liquidity_transition_stage",
                    "idx_stock_liquidity_transition_source"
            )),
            Map.entry("stock_underwriting_contract", Set.of(
                    "uk_stock_underwriting_contract_code",
                    "uk_stock_underwriting_contract_action",
                    "idx_stock_underwriting_contract_symbol",
                    "idx_stock_underwriting_contract_participant",
                    "idx_stock_underwriting_contract_account"
            )),
            Map.entry("stock_underwriting_daily_supply_state", Set.of(
                    "idx_stock_underwriting_supply_contract",
                    "idx_stock_underwriting_supply_status"
            )),
            Map.entry("stock_security_allocation_ledger", Set.of(
                    "uk_stock_security_allocation_idempotency",
                    "idx_stock_security_allocation_symbol",
                    "idx_stock_security_allocation_destination",
                    "idx_stock_security_allocation_contract"
            ))
    );

    private final DataSource dataSource;
    private final StockRuntimeIdentity runtimeIdentity;

    public StockSchemaReadinessValidator(
            @Qualifier(BatchRepositoryDataSourceConfig.BUSINESS_DATA_SOURCE) DataSource dataSource,
            StockRuntimeIdentity runtimeIdentity
    ) {
        this.dataSource = dataSource;
        this.runtimeIdentity = runtimeIdentity;
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            run(null);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Stock schema readiness validation failed", ex);
        }
    }

    public void run(ApplicationArguments args) throws Exception {
        List<String> missing = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String catalog = connection.getCatalog();
            Map<String, Map<String, Boolean>> columnsByTable =
                    readColumnMetadata(metadata, catalog);
            Map<String, String> checkClauses = readCheckClauses(connection);
            for (Map.Entry<String, Set<String>> requirement : REQUIRED_COLUMNS.entrySet()) {
                Set<String> actualColumns = columnsByTable
                        .getOrDefault(normalize(requirement.getKey()), Map.of())
                        .keySet();
                if (actualColumns.isEmpty()) {
                    missing.add(requirement.getKey() + " table");
                    continue;
                }
                for (String requiredColumn : requirement.getValue()) {
                    if (!actualColumns.contains(requiredColumn)) {
                        missing.add(requirement.getKey() + "." + requiredColumn + " column");
                    }
                }
            }
            for (Map.Entry<String, Set<String>> legacyColumns : FORBIDDEN_LEGACY_COLUMNS.entrySet()) {
                Set<String> actualColumns = columnsByTable
                        .getOrDefault(normalize(legacyColumns.getKey()), Map.of())
                        .keySet();
                for (String forbiddenColumn : legacyColumns.getValue()) {
                    if (actualColumns.contains(forbiddenColumn)) {
                        missing.add(legacyColumns.getKey() + "." + forbiddenColumn + " legacy column removal");
                    }
                }
            }
            for (String forbiddenTable : FORBIDDEN_LEGACY_TABLES) {
                if (!columnsByTable
                        .getOrDefault(normalize(forbiddenTable), Map.of())
                        .isEmpty()) {
                    missing.add(forbiddenTable + " legacy table removal");
                }
            }
            for (Map.Entry<String, Set<String>> requirement : REQUIRED_INDEXES.entrySet()) {
                Set<String> actualIndexes = readIndexes(metadata, catalog, requirement.getKey());
                for (String requiredIndex : requirement.getValue()) {
                    if (!actualIndexes.contains(requiredIndex)) {
                        missing.add(requirement.getKey() + "." + requiredIndex + " index");
                    }
                }
            }
            for (Map.Entry<String, Set<String>> requirement : REQUIRED_NOT_NULL_COLUMNS.entrySet()) {
                Map<String, Boolean> actualColumns = columnsByTable.getOrDefault(
                        normalize(requirement.getKey()),
                        Map.of()
                );
                for (String requiredColumn : requirement.getValue()) {
                    if (Boolean.TRUE.equals(actualColumns.get(normalize(requiredColumn)))) {
                        missing.add(
                                requirement.getKey() + "." + requiredColumn + " NOT NULL constraint"
                        );
                    }
                }
            }
            for (Map.Entry<String, Set<String>> requirement : REQUIRED_CHECK_TOKENS.entrySet()) {
                String checkClause = checkClauses.get(normalize(requirement.getKey()));
                if (checkClause == null) {
                    missing.add(requirement.getKey() + " CHECK constraint");
                    continue;
                }
                String normalizedClause = normalize(checkClause);
                for (String requiredToken : requirement.getValue()) {
                    if (!normalizedClause.contains(normalize(requiredToken))) {
                        missing.add(requirement.getKey() + " CHECK token " + requiredToken);
                    }
                }
            }
            for (Map.Entry<String, Set<String>> requirement : REQUIRED_EXACT_CHECK_LITERALS.entrySet()) {
                String checkClause = checkClauses.get(normalize(requirement.getKey()));
                if (checkClause == null) {
                    missing.add(requirement.getKey() + " CHECK constraint");
                    continue;
                }
                Set<String> actualLiterals = extractCheckLiterals(checkClause);
                if (!actualLiterals.equals(requirement.getValue())) {
                    missing.add(requirement.getKey() + " exact allowed values");
                }
            }
            for (Map.Entry<String, Set<String>> requirement : FORBIDDEN_CHECK_TOKENS.entrySet()) {
                String checkClause = checkClauses.get(normalize(requirement.getKey()));
                if (checkClause == null) {
                    continue;
                }
                String normalizedClause = normalize(checkClause);
                for (String forbiddenToken : requirement.getValue()) {
                    if (normalizedClause.contains(normalize(forbiddenToken))) {
                        missing.add(
                                requirement.getKey() + " CHECK forbidden token " + forbiddenToken
                        );
                    }
                }
            }
            for (String forbiddenCheck : FORBIDDEN_LEGACY_CHECKS) {
                if (checkClauses.containsKey(normalize(forbiddenCheck))) {
                    missing.add(forbiddenCheck + " legacy CHECK removal");
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Stock EOD schema is not ready; apply the canonical stock-back-service DDL migrations before restart: "
                            + String.join(", ", missing)
            );
        }
        log.info(
                "Stock EOD schema readiness passed: buildVersion={}, schemaVersion={}, eodContractVersion={}",
                runtimeIdentity.buildVersion(),
                runtimeIdentity.schemaVersion(),
                runtimeIdentity.eodContractVersion()
        );
    }

    private Map<String, Map<String, Boolean>> readColumnMetadata(
            DatabaseMetaData metadata,
            String catalog
    ) throws SQLException {
        Map<String, Map<String, Boolean>> columnsByTable = new LinkedHashMap<>();
        try (ResultSet rows = metadata.getColumns(catalog, null, "%", "%")) {
            while (rows.next()) {
                String tableName = normalize(rows.getString("TABLE_NAME"));
                String columnName = normalize(rows.getString("COLUMN_NAME"));
                boolean nullable =
                        rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
                columnsByTable
                        .computeIfAbsent(tableName, ignored -> new LinkedHashMap<>())
                        .put(columnName, nullable);
            }
        }
        return columnsByTable;
    }

    private Set<String> readIndexes(
            DatabaseMetaData metadata,
            String catalog,
            String tableName
    ) throws SQLException {
        Set<String> indexes = new LinkedHashSet<>();
        readIndexes(metadata, catalog, tableName, indexes);
        if (indexes.isEmpty()) {
            readIndexes(metadata, catalog, tableName.toUpperCase(Locale.ROOT), indexes);
        }
        return indexes;
    }

    private void readIndexes(
            DatabaseMetaData metadata,
            String catalog,
            String tableName,
            Set<String> indexes
    ) throws SQLException {
        try (ResultSet rows = metadata.getIndexInfo(catalog, null, tableName, false, false)) {
            while (rows.next()) {
                String indexName = rows.getString("INDEX_NAME");
                if (indexName != null) {
                    indexes.add(normalize(indexName));
                }
            }
        }
    }

    private Map<String, String> readCheckClauses(Connection connection) throws SQLException {
        String productName = connection.getMetaData().getDatabaseProductName();
        String normalizedProductName = normalize(productName);
        boolean mysql = normalizedProductName.contains("mysql");
        boolean h2 = normalizedProductName.contains("h2");
        String sql = mysql
                ? """
                  select constraint_name, check_clause
                   from information_schema.check_constraints
                   where constraint_schema = database()
                  """
                : h2
                ? """
                  select "CONSTRAINT_NAME", "CHECK_CLAUSE"
                    from "INFORMATION_SCHEMA"."CHECK_CONSTRAINTS"
                   where lower("CONSTRAINT_SCHEMA") = lower(current_schema)
                  """
                : """
                  select constraint_name, check_clause
                   from information_schema.check_constraints
                   where constraint_schema = current_schema
                  """;
        Map<String, String> clauses = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    clauses.put(
                            normalize(rows.getString(1)),
                            rows.getString(2)
                    );
                }
            }
        }
        return clauses;
    }

    private Set<String> extractCheckLiterals(String checkClause) {
        Set<String> literals = new LinkedHashSet<>();
        Matcher matcher = CHECK_LITERAL_PATTERN.matcher(checkClause);
        while (matcher.find()) {
            literals.add(normalize(matcher.group(1).replace("''", "'")));
        }
        return literals;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static Map<String, Set<String>> requiredColumns() {
        Map<String, Set<String>> requirements = new LinkedHashMap<>();
        requirements.put("stock_account", Set.of("participant_category", "self_trade_group_id"));
        requirements.put("stock_market_participant", Set.of(
                "id", "participant_code", "display_name", "participant_type", "status",
                "self_trade_group_id", "created_at", "updated_at"
        ));
        requirements.put("stock_market_participant_account", Set.of(
                "id", "participant_id", "account_id", "account_role", "desk_code",
                "effective_from", "effective_to", "status", "created_at", "updated_at"
        ));
        requirements.put("stock_market_policy_version", Set.of(
                "id", "policy_scope", "scope_key", "version_no", "effective_business_date",
                "status", "config_json", "change_reason", "changed_by", "created_at", "updated_at"
        ));
        requirements.put("stock_order_strategy_origin", Set.of(
                "order_id", "origin_type", "participant_id", "portfolio_id",
                "decision_run_id", "liquidity_mandate_id", "underwriting_contract_id",
                "policy_version", "created_at"
        ));
        requirements.put("stock_market_business_state", Set.of(
                "state_id", "active_business_date", "preparing_business_date", "raw_simulation_date", "version"
        ));
        requirements.put("stock_market_session_fence", Set.of(
                "market_type", "symbol", "business_date", "session_epoch", "session_state", "version"
        ));
        requirements.put("stock_auto_participant_cash_flow_run", Set.of(
                "run_key", "operation", "last_account_id", "processed_count",
                "completed_at", "created_at", "updated_at"
        ));
        requirements.put("stock_auto_participant_profile_config", Set.of(
                "behavior_model_version",
                "decision_frequency_multiplier", "orders_per_decision_multiplier",
                "pricing_mode", "exit_mode", "inventory_mode"
        ));
        requirements.put("stock_auto_participant", Set.of(
                "behavior_seed"
        ));
        requirements.put("stock_order", Set.of(
                "origin_type", "self_trade_group_id",
                "funding_budget_type", "expires_at", "auto_profile_type", "auto_behavior_model_version",
                "auto_policy_version", "auto_behavior_event_sequence", "decision_urgency", "cancel_reason"
        ));
        requirements.put("stock_auto_participant_policy_revision", Set.of(
                "policy_version", "status", "effective_trade_date", "runtime_enabled",
                "runtime_change_reason", "runtime_changed_by", "runtime_changed_at",
                "policy_json", "created_by", "created_at", "activated_at", "retired_at"
        ));
        requirements.put("stock_auto_participant_daily_behavior_state", Set.of(
                "simulation_trade_date", "account_id", "user_key", "profile_type",
                "policy_version", "participant_config_version", "activity_state", "activity_session",
                "daily_seed", "event_sequence", "fatigue_score", "fatigue_updated_at",
                "submitted_order_count", "submitted_notional", "observed_execution_count",
                "observed_execution_notional", "observed_cancel_count", "last_attention_at",
                "last_decision_at", "last_order_at", "last_result_reason", "last_hold_reason",
                "recovery_factor", "optimistic_version", "created_at", "updated_at"
        ));
        requirements.put("stock_auto_participant_order_schedule", Set.of(
                "account_id", "user_key", "profile_type", "behavior_model_version",
                "simulation_trade_date", "next_attention_at", "next_guard_at", "next_run_at",
                "last_run_at", "lease_until", "lease_owner", "priority", "created_at", "updated_at"
        ));
        requirements.put("stock_auto_participant_liquidation_plan", Set.of(
                "simulation_trade_date", "account_id", "symbol", "urgency", "trigger_reason",
                "status", "target_quantity", "submitted_quantity", "remaining_quantity",
                "attempt_count", "next_retry_at", "last_error", "created_at", "updated_at"
        ));
        requirements.put("stock_institution_portfolio", Set.of(
                "id", "participant_id", "account_id", "portfolio_code", "display_name",
                "investment_style", "execution_mode", "status",
                "base_stock_allocation_rate", "min_stock_allocation_rate",
                "max_stock_allocation_rate", "primary_regime_weight",
                "asset_preference_sensitivity", "volatility_sensitivity",
                "entry_threshold_rate", "exit_threshold_rate",
                "daily_turnover_limit_rate", "max_decision_turnover_rate",
                "decision_interval_minutes", "next_decision_at", "policy_version",
                "created_at", "updated_at"
        ));
        requirements.put("stock_institution_symbol_mandate", Set.of(
                "id", "portfolio_id", "symbol", "base_symbol_weight",
                "min_portfolio_allocation_rate", "max_portfolio_allocation_rate",
                "price_pressure_sensitivity", "momentum_sensitivity",
                "value_sensitivity", "report_sensitivity", "reference_daily_volume",
                "daily_participation_rate", "enabled", "created_at", "updated_at"
        ));
        requirements.put("stock_institution_decision_run", Set.of(
                "id", "decision_slot", "simulation_trade_date", "portfolio_id",
                "execution_mode", "policy_version", "deterministic_seed",
                "status", "error_message", "created_at", "completed_at"
        ));
        requirements.put("stock_institution_decision_item", Set.of(
                "decision_run_id", "symbol",
                "primary_price_pressure", "primary_asset_preference_pressure",
                "primary_volatility_pressure", "primary_liquidity_pressure",
                "primary_execution_aggression_pressure", "secondary_price_pressure",
                "secondary_asset_preference_pressure", "secondary_volatility_pressure",
                "secondary_liquidity_pressure", "secondary_execution_aggression_pressure",
                "blended_price_pressure", "blended_asset_preference_pressure",
                "blended_volatility_pressure", "blended_liquidity_pressure",
                "blended_execution_aggression_pressure", "return_5_day",
                "return_20_day", "report_pressure", "current_price",
                "liquid_asset_amount", "actual_quantity", "open_buy_quantity",
                "open_sell_quantity", "projected_quantity", "actual_allocation_rate",
                "projected_allocation_rate", "base_allocation_rate",
                "target_stock_allocation_rate", "target_allocation_rate",
                "target_amount", "raw_trade_amount", "gated_trade_amount",
                "gated_quantity", "action", "decision_reason", "gate_reason",
                "reference_daily_volume", "remaining_daily_quantity_budget",
                "remaining_daily_notional_budget", "created_at"
        ));
        requirements.put("stock_institution_daily_budget", Set.of(
                "simulation_trade_date", "portfolio_id", "symbol",
                "reference_daily_volume", "gross_quantity_limit", "gross_notional_limit",
                "planned_buy_quantity", "planned_sell_quantity", "planned_buy_amount",
                "planned_sell_amount", "submitted_buy_amount", "submitted_sell_amount",
                "executed_buy_amount", "executed_sell_amount", "policy_version",
                "version", "created_at", "updated_at"
        ));
        requirements.put("stock_institution_order_intent", Set.of(
                "decision_run_id", "symbol", "portfolio_id", "participant_id",
                "account_id", "side", "requested_quantity", "planned_amount",
                "reference_daily_volume",
                "execution_aggression_pressure", "policy_version", "status",
                "attempt_count", "submitted_order_id", "submitted_price", "submitted_quantity",
                "submission_reason", "created_at", "updated_at", "submitted_at"
        ));
        requirements.put("stock_liquidity_mandate", Set.of(
                "id", "participant_id", "account_id", "symbol", "mandate_code",
                "execution_mode", "status", "contract_start_date", "contract_end_date",
                "target_spread_ticks", "max_spread_ticks", "max_order_quantity",
                "reference_daily_volume", "target_open_participation_rate",
                "max_open_participation_rate", "max_single_order_participation_rate",
                "external_depth_levels", "max_external_depth_participation_rate",
                "daily_execution_participation_rate", "daily_submission_multiplier",
                "target_inventory_quantity", "inventory_band_quantity",
                "inventory_skew_ticks", "primary_regime_weight",
                "liquidity_size_sensitivity", "volatility_spread_max_ticks",
                "price_regime_max_skew_ticks", "passive_only",
                "minimum_quote_lifetime_seconds", "reprice_threshold_ticks",
                "order_ttl_seconds",
                "quote_interval_seconds", "daily_loss_limit_amount",
                "next_quote_at", "policy_version", "created_at", "updated_at"
        ));
        requirements.put("stock_liquidity_daily_state", Set.of(
                "simulation_trade_date", "mandate_id", "reference_daily_volume",
                "execution_quantity_limit", "submission_quantity_limit",
                "submitted_buy_quantity", "submitted_sell_quantity",
                "submitted_buy_amount", "submitted_sell_amount",
                "cancelled_buy_quantity", "cancelled_sell_quantity",
                "executed_buy_quantity", "executed_sell_quantity",
                "executed_buy_amount", "executed_sell_amount", "realized_profit",
                "unrealized_profit", "opening_net_asset_value",
                "current_net_asset_value", "risk_profit",
                "target_buy_open_quantity", "target_sell_open_quantity",
                "last_open_buy_quantity", "last_open_sell_quantity",
                "external_buy_depth_quantity", "external_sell_depth_quantity",
                "last_bid_price", "last_ask_price", "last_inventory_quantity",
                "last_projected_inventory_quantity", "blended_price_pressure",
                "blended_volatility_pressure", "blended_liquidity_pressure",
                "state_status", "gate_reason", "quote_run_count", "limit_breached",
                "policy_version", "version", "created_at", "updated_at"
        ));
        requirements.put("stock_liquidity_transition", Set.of(
                "id", "transition_key", "symbol", "mandate_id", "participant_id",
                "liquidity_account_id", "source_account_id", "legacy_account_id",
                "stage", "reference_daily_volume", "seed_inventory_quantity",
                "seed_cash_amount", "transferred_inventory_quantity",
                "transferred_cash_amount", "effective_business_date",
                "legacy_disabled_at", "legacy_retired_at", "activated_at", "requested_by",
                "change_reason", "policy_version", "created_at", "updated_at"
        ));
        requirements.put("stock_underwriting_contract", Set.of(
                "id", "contract_code", "corporate_action_id", "symbol",
                "participant_id", "account_id", "total_issue_quantity",
                "tradable_allocation_quantity", "locked_allocation_quantity",
                "external_allocation_quantity", "underwritten_quantity", "issue_price",
                "underwriting_type", "stabilization_start_date", "stabilization_end_date",
                "stabilization_quantity_limit", "stabilization_amount_limit",
                "status", "policy_version", "created_at", "updated_at"
        ));
        requirements.put("stock_underwriting_daily_supply_state", Set.of(
                "simulation_trade_date", "underwriting_contract_id",
                "reference_daily_volume", "submission_quantity_limit",
                "submission_amount_limit", "submitted_quantity", "submitted_amount",
                "generated_order_count", "cancelled_order_count",
                "last_order_price", "state_status", "gate_reason",
                "policy_version", "version", "created_at", "updated_at"
        ));
        requirements.put("stock_security_allocation_ledger", Set.of(
                "id", "idempotency_key", "event_type", "corporate_action_id",
                "underwriting_contract_id", "source_account_id",
                "destination_account_id", "symbol", "quantity", "unit_price",
                "allocation_reason", "tradability_status",
                "effective_business_date", "unlock_business_date", "created_at"
        ));
        requirements.put("stock_auto_participant_position_state", Set.of(
                "account_id", "symbol", "position_opened_business_date", "holding_trading_days",
                "average_down_rounds", "last_average_down_business_date", "peak_close_price",
                "last_seen_business_date", "updated_at"
        ));
        requirements.put("stock_auto_participant_performance_state", Set.of(
                "account_id", "recent_profitable_trading_days", "recent_closed_trading_days",
                "last_seen_business_date", "updated_at"
        ));
        requirements.put("stock_auto_participant_funding_budget", Set.of(
                "id", "account_id", "budget_type", "source_key", "source_symbol",
                "corporate_action_id", "corporate_action_entitlement_id", "granted_amount",
                "available_amount", "reserved_amount", "spent_amount", "expires_business_date",
                "status", "created_at", "updated_at"
        ));
        requirements.put("stock_auto_participant_order_budget", Set.of(
                "order_id", "budget_id", "allocated_amount", "remaining_reserved_amount",
                "spent_amount", "released_amount", "created_at", "updated_at"
        ));
        requirements.put("stock_post_close_cycle", Set.of(
                "id", "business_date", "scope_type", "scope_key", "cycle_kind", "phase", "status",
                "phase_revision", "version", "owner_id", "lease_until", "next_retry_at", "close_run_id",
                "settlement_eligible_at", "attempt_count", "build_version", "schema_version",
                "eod_contract_version"
        ));
        requirements.put("stock_post_close_phase_attempt", Set.of(
                "cycle_id", "phase", "attempt_no", "batch_job_execution_id", "owner_id", "status",
                "error_code", "error_message", "build_version", "schema_version", "eod_contract_version"
        ));
        requirements.put("stock_post_close_readiness_check", Set.of(
                "close_cycle_id", "check_code", "display_order", "check_status",
                "failure_count", "message", "checked_at"
        ));
        requirements.put("stock_post_close_cycle_metric", Set.of(
                "close_cycle_id", "close_run_id", "captured_open_order_count", "cancelled_order_count",
                "released_buy_cash", "released_sell_quantity",
                "settlement_target_account_count", "account_snapshot_count", "holding_snapshot_count",
                "price_snapshot_count", "open_order_summary_count", "reconciliation_mismatch_count",
                "settled_account_count", "settlement_missing_account_count"
        ));
        requirements.put("stock_holding_snapshot", Set.of(
                "close_cycle_id", "close_run_id", "account_id", "symbol", "quantity", "reserved_quantity",
                "average_price", "evaluation_price", "snapshot_at"
        ));
        requirements.put("stock_close_account_snapshot", Set.of(
                "close_cycle_id", "close_run_id", "account_id", "user_key", "account_status",
                "participant_category", "participant_profile_type",
                "settlement_target", "pre_cancel_cash", "pre_cancel_order_reserved_cash",
                "subscription_reserved_cash", "post_cancel_cash", "external_net_cash_flow",
                "cash_flow_watermark_id", "holding_market_value", "holding_quantity",
                "reserved_sell_quantity", "holding_position_count", "reconciliation_status", "snapshot_at"
        ));
        requirements.put("stock_close_price_snapshot", Set.of(
                "close_cycle_id", "close_run_id", "symbol", "close_price", "previous_close", "price_time",
                "price_provider", "last_execution_id", "order_book_symbol", "snapshot_at"
        ));
        requirements.put("stock_close_open_order_summary", Set.of(
                "close_cycle_id", "close_run_id", "symbol", "pre_cancel_open_order_count",
                "pre_cancel_buy_order_count", "pre_cancel_sell_order_count",
                "pre_cancel_remaining_buy_quantity", "pre_cancel_remaining_sell_quantity",
                "pre_cancel_reserved_buy_cash", "pre_cancel_reserved_sell_quantity",
                "post_cancel_open_order_count", "reconciliation_status", "snapshot_at"
        ));
        requirements.put("stock_close_open_order_snapshot", Set.of(
                "close_cycle_id", "close_run_id", "order_id", "account_id", "symbol", "side",
                "source_order_status", "remaining_quantity", "reserved_cash", "captured_at", "released_at"
        ));
        requirements.put("stock_corporate_action_processing", Set.of(
                "action_id", "account_scope_key", "action_phase", "effective_business_date",
                "status", "attempt_count", "processed_count", "amount", "quantity"
        ));
        requirements.put("stock_corporate_action", Set.of(
                "record_date", "entitlement_close_cycle_id", "entitlement_close_run_id"
        ));
        requirements.put("stock_corporate_action_entitlement", Set.of(
                "subscribed_share_quantity", "subscribed_cash_amount", "forfeited_share_quantity", "status"
        ));
        requirements.put("stock_account_cash_flow", Set.of(
                "corporate_action_id", "corporate_action_entitlement_id", "effective_business_date"
        ));
        requirements.put("stock_batch_job_signal", Set.of(
                "status", "requested_business_date", "requested_session_epoch", "expected_cycle_id",
                "eligible_at", "next_attempt_at", "attempt_count", "max_attempts", "claim_token",
                "lease_until", "failure_class"
        ));
        requirements.put("portfolio_snapshot", Set.of(
                "close_cycle_id", "close_run_id", "pending_subscription_asset",
                "holding_quantity", "reserved_sell_quantity",
                "holding_position_count", "net_contribution", "total_profit", "return_rate_status",
                "input_hash", "calculation_version", "data_quality_status",
                "source_build_version"
        ));
        requirements.put("stock_execution_daily_account_snapshot", Set.of(
                "close_run_id", "account_id", "execution_amount", "last_executed_at"
        ));
        requirements.put("stock_execution_account_day_summary", Set.of(
                "simulation_trade_date", "account_id", "execution_count", "buy_quantity", "sell_quantity",
                "gross_amount", "buy_gross_amount", "sell_gross_amount", "buy_net_amount", "sell_net_amount",
                "fee_amount", "tax_amount", "realized_profit", "last_executed_at", "updated_at"
        ));
        return Map.copyOf(requirements);
    }
}
