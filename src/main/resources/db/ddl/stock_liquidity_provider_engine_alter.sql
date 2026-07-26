USE STOCK_SERVICE;

CREATE TABLE IF NOT EXISTS stock_liquidity_mandate (
  id BIGINT NOT NULL AUTO_INCREMENT,
  participant_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  mandate_code VARCHAR(80) NOT NULL,
  execution_mode VARCHAR(20) NOT NULL DEFAULT 'LIVE',
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  contract_start_date DATE NOT NULL,
  contract_end_date DATE NULL,
  target_spread_ticks INT NOT NULL DEFAULT 4,
  max_spread_ticks INT NOT NULL DEFAULT 12,
  max_order_quantity BIGINT NOT NULL,
  reference_daily_volume BIGINT NOT NULL,
  target_open_participation_rate DECIMAL(8,6) NOT NULL DEFAULT 0.050000,
  max_open_participation_rate DECIMAL(8,6) NOT NULL DEFAULT 0.080000,
  max_single_order_participation_rate DECIMAL(8,6) NOT NULL DEFAULT 0.010000,
  external_depth_levels INT NOT NULL DEFAULT 5,
  max_external_depth_participation_rate DECIMAL(8,6) NOT NULL DEFAULT 0.100000,
  daily_execution_participation_rate DECIMAL(8,6) NOT NULL DEFAULT 0.100000,
  daily_submission_multiplier DECIMAL(8,4) NOT NULL DEFAULT 2.0000,
  target_inventory_quantity BIGINT NOT NULL,
  inventory_band_quantity BIGINT NOT NULL,
  inventory_skew_ticks INT NOT NULL DEFAULT 3,
  primary_regime_weight DECIMAL(8,6) NOT NULL DEFAULT 0.700000,
  liquidity_size_sensitivity DECIMAL(8,6) NOT NULL DEFAULT 0.250000,
  volatility_spread_max_ticks INT NOT NULL DEFAULT 4,
  price_regime_max_skew_ticks INT NOT NULL DEFAULT 1,
  passive_only BOOLEAN NOT NULL DEFAULT TRUE,
  minimum_quote_lifetime_seconds INT NOT NULL DEFAULT 30,
  reprice_threshold_ticks INT NOT NULL DEFAULT 2,
  order_ttl_seconds INT NOT NULL DEFAULT 300,
  quote_interval_seconds INT NOT NULL DEFAULT 30,
  daily_loss_limit_amount DECIMAL(19,2) NOT NULL,
  next_quote_at DATETIME NULL,
  policy_version BIGINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_liquidity_mandate_code (mandate_code),
  UNIQUE KEY uk_stock_liquidity_mandate_account (account_id),
  UNIQUE KEY uk_stock_liquidity_mandate_symbol (symbol),
  KEY idx_stock_liquidity_mandate_due (
    status, execution_mode, next_quote_at, id
  ),
  KEY idx_stock_liquidity_mandate_participant (
    participant_id, status, id
  ),
  CONSTRAINT chk_stock_liquidity_mandate_mode CHECK (
    `execution_mode` = 'LIVE'
  ),
  CONSTRAINT chk_stock_liquidity_mandate_status CHECK (
    CASE `status`
      WHEN 'ACTIVE' THEN 1
      WHEN 'SUSPENDED' THEN 1
      WHEN 'EXPIRED' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_liquidity_mandate_contract CHECK (
    contract_end_date IS NULL OR contract_end_date >= contract_start_date
  ),
  CONSTRAINT chk_stock_liquidity_mandate_spread CHECK (
    target_spread_ticks BETWEEN 1 AND 50
    AND max_spread_ticks BETWEEN target_spread_ticks AND 100
  ),
  CONSTRAINT chk_stock_liquidity_mandate_volume CHECK (
    max_order_quantity > 0
    AND reference_daily_volume > 0
    AND target_open_participation_rate > 0
    AND target_open_participation_rate <= 0.100000
    AND max_open_participation_rate >= target_open_participation_rate
    AND max_open_participation_rate <= 0.200000
    AND max_single_order_participation_rate > 0
    AND max_single_order_participation_rate <= target_open_participation_rate
    AND external_depth_levels BETWEEN 1 AND 10
    AND max_external_depth_participation_rate > 0
    AND max_external_depth_participation_rate <= 0.250000
    AND daily_execution_participation_rate > 0
    AND daily_execution_participation_rate <= 0.300000
    AND daily_submission_multiplier BETWEEN 1 AND 10
  ),
  CONSTRAINT chk_stock_liquidity_mandate_inventory CHECK (
    target_inventory_quantity >= 0
    AND inventory_band_quantity > 0
    AND inventory_skew_ticks BETWEEN 0 AND 50
  ),
  CONSTRAINT chk_stock_liquidity_mandate_regime CHECK (
    primary_regime_weight BETWEEN 0 AND 1
    AND liquidity_size_sensitivity BETWEEN 0 AND 1
    AND volatility_spread_max_ticks BETWEEN 0 AND 50
    AND price_regime_max_skew_ticks BETWEEN 0 AND 5
  ),
  CONSTRAINT chk_stock_liquidity_mandate_timing CHECK (
    minimum_quote_lifetime_seconds BETWEEN 10 AND 1800
    AND reprice_threshold_ticks BETWEEN 1 AND 20
    AND order_ttl_seconds >= minimum_quote_lifetime_seconds
    AND order_ttl_seconds <= 7200
    AND quote_interval_seconds BETWEEN 10 AND 600
  ),
  CONSTRAINT chk_stock_liquidity_mandate_loss CHECK (
    daily_loss_limit_amount > 0
  ),
  CONSTRAINT chk_stock_liquidity_mandate_version CHECK (
    policy_version > 0
  )
);

CREATE TABLE IF NOT EXISTS stock_liquidity_daily_state (
  simulation_trade_date DATE NOT NULL,
  mandate_id BIGINT NOT NULL,
  reference_daily_volume BIGINT NOT NULL,
  execution_quantity_limit BIGINT NOT NULL,
  submission_quantity_limit BIGINT NOT NULL,
  submitted_buy_quantity BIGINT NOT NULL DEFAULT 0,
  submitted_sell_quantity BIGINT NOT NULL DEFAULT 0,
  submitted_buy_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  submitted_sell_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  cancelled_buy_quantity BIGINT NOT NULL DEFAULT 0,
  cancelled_sell_quantity BIGINT NOT NULL DEFAULT 0,
  executed_buy_quantity BIGINT NOT NULL DEFAULT 0,
  executed_sell_quantity BIGINT NOT NULL DEFAULT 0,
  executed_buy_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  executed_sell_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  realized_profit DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  unrealized_profit DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  opening_net_asset_value DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  current_net_asset_value DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  risk_profit DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  target_buy_open_quantity BIGINT NOT NULL DEFAULT 0,
  target_sell_open_quantity BIGINT NOT NULL DEFAULT 0,
  last_open_buy_quantity BIGINT NOT NULL DEFAULT 0,
  last_open_sell_quantity BIGINT NOT NULL DEFAULT 0,
  external_buy_depth_quantity BIGINT NOT NULL DEFAULT 0,
  external_sell_depth_quantity BIGINT NOT NULL DEFAULT 0,
  last_bid_price DECIMAL(19,2) NULL,
  last_ask_price DECIMAL(19,2) NULL,
  last_inventory_quantity BIGINT NOT NULL DEFAULT 0,
  last_projected_inventory_quantity BIGINT NOT NULL DEFAULT 0,
  blended_price_pressure DECIMAL(8,6) NOT NULL DEFAULT 0.000000,
  blended_volatility_pressure DECIMAL(8,6) NOT NULL DEFAULT 0.000000,
  blended_liquidity_pressure DECIMAL(8,6) NOT NULL DEFAULT 0.000000,
  state_status VARCHAR(20) NOT NULL DEFAULT 'QUOTING',
  gate_reason VARCHAR(120) NOT NULL DEFAULT 'NOT_RUN',
  quote_run_count BIGINT NOT NULL DEFAULT 0,
  limit_breached BOOLEAN NOT NULL DEFAULT FALSE,
  policy_version BIGINT NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (simulation_trade_date, mandate_id),
  KEY idx_stock_liquidity_daily_state_mandate (
    mandate_id, simulation_trade_date
  ),
  KEY idx_stock_liquidity_daily_state_status (
    simulation_trade_date, state_status, mandate_id
  ),
  CONSTRAINT chk_stock_liquidity_daily_state_limit CHECK (
    reference_daily_volume > 0
    AND execution_quantity_limit > 0
    AND submission_quantity_limit >= execution_quantity_limit
  ),
  CONSTRAINT chk_stock_liquidity_daily_state_quantity CHECK (
    submitted_buy_quantity >= 0
    AND submitted_sell_quantity >= 0
    AND submitted_buy_amount >= 0
    AND submitted_sell_amount >= 0
    AND cancelled_buy_quantity >= 0
    AND cancelled_sell_quantity >= 0
    AND executed_buy_quantity >= 0
    AND executed_sell_quantity >= 0
    AND executed_buy_amount >= 0
    AND executed_sell_amount >= 0
    AND opening_net_asset_value >= 0
    AND current_net_asset_value >= 0
    AND target_buy_open_quantity >= 0
    AND target_sell_open_quantity >= 0
    AND last_open_buy_quantity >= 0
    AND last_open_sell_quantity >= 0
    AND external_buy_depth_quantity >= 0
    AND external_sell_depth_quantity >= 0
    AND last_inventory_quantity >= 0
    AND last_projected_inventory_quantity >= 0
  ),
  CONSTRAINT chk_stock_liquidity_daily_state_price CHECK (
    (last_bid_price IS NULL OR last_bid_price > 0)
    AND (last_ask_price IS NULL OR last_ask_price > 0)
    AND (
      last_bid_price IS NULL
      OR last_ask_price IS NULL
      OR last_bid_price < last_ask_price
    )
  ),
  CONSTRAINT chk_stock_liquidity_daily_state_pressure CHECK (
    blended_price_pressure BETWEEN -1 AND 1
    AND blended_volatility_pressure BETWEEN -1 AND 1
    AND blended_liquidity_pressure BETWEEN -1 AND 1
  ),
  CONSTRAINT chk_stock_liquidity_daily_state_status CHECK (
    CASE `state_status`
      WHEN 'QUOTING' THEN 1
      WHEN 'EXEMPT' THEN 1
      WHEN 'HALTED' THEN 1
      WHEN 'ERROR' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_liquidity_daily_state_version CHECK (
    quote_run_count >= 0 AND policy_version > 0 AND version >= 0
  )
);
