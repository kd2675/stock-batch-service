USE STOCK_SERVICE;

CREATE TABLE IF NOT EXISTS stock_institution_portfolio (
  id BIGINT NOT NULL AUTO_INCREMENT,
  participant_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  portfolio_code VARCHAR(64) NOT NULL,
  display_name VARCHAR(120) NOT NULL,
  investment_style VARCHAR(40) NOT NULL,
  execution_mode VARCHAR(20) NOT NULL DEFAULT 'LIVE',
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  base_stock_allocation_rate DECIMAL(8,6) NOT NULL,
  min_stock_allocation_rate DECIMAL(8,6) NOT NULL,
  max_stock_allocation_rate DECIMAL(8,6) NOT NULL,
  primary_regime_weight DECIMAL(8,6) NOT NULL DEFAULT 0.700000,
  asset_preference_sensitivity DECIMAL(8,6) NOT NULL DEFAULT 0.020000,
  volatility_sensitivity DECIMAL(8,6) NOT NULL DEFAULT 0.020000,
  entry_threshold_rate DECIMAL(8,6) NOT NULL DEFAULT 0.005000,
  exit_threshold_rate DECIMAL(8,6) NOT NULL DEFAULT 0.002000,
  daily_turnover_limit_rate DECIMAL(8,6) NOT NULL DEFAULT 0.010000,
  max_decision_turnover_rate DECIMAL(8,6) NOT NULL DEFAULT 0.002000,
  decision_interval_minutes INT NOT NULL DEFAULT 60,
  next_decision_at DATETIME NULL,
  policy_version BIGINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_institution_portfolio_code (portfolio_code),
  UNIQUE KEY uk_stock_institution_portfolio_account (account_id),
  KEY idx_stock_institution_portfolio_due (status, execution_mode, next_decision_at, id),
  KEY idx_stock_institution_portfolio_participant (participant_id, status, id),
  CONSTRAINT chk_stock_institution_portfolio_style CHECK (
    CASE `investment_style`
      WHEN 'BALANCED_LONG_TERM' THEN 1
      WHEN 'VALUE_CONTRARIAN' THEN 1
      WHEN 'MOMENTUM' THEN 1
      WHEN 'ACTIVE_SHORT_TERM' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_institution_portfolio_mode CHECK (`execution_mode` = 'LIVE'),
  CONSTRAINT chk_stock_institution_portfolio_status CHECK (
    CASE `status` WHEN 'ACTIVE' THEN 1 WHEN 'SUSPENDED' THEN 1 WHEN 'RETIRED' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_institution_portfolio_allocation CHECK (
    min_stock_allocation_rate >= 0
    AND min_stock_allocation_rate <= base_stock_allocation_rate
    AND base_stock_allocation_rate <= max_stock_allocation_rate
    AND max_stock_allocation_rate <= 1
  ),
  CONSTRAINT chk_stock_institution_portfolio_regime_weight CHECK (
    primary_regime_weight >= 0 AND primary_regime_weight <= 1
  ),
  CONSTRAINT chk_stock_institution_portfolio_sensitivity CHECK (
    asset_preference_sensitivity >= 0
    AND asset_preference_sensitivity <= 1
    AND volatility_sensitivity >= 0
    AND volatility_sensitivity <= 1
  ),
  CONSTRAINT chk_stock_institution_portfolio_threshold CHECK (
    exit_threshold_rate >= 0
    AND exit_threshold_rate <= entry_threshold_rate
    AND entry_threshold_rate <= 1
  ),
  CONSTRAINT chk_stock_institution_portfolio_turnover CHECK (
    daily_turnover_limit_rate > 0
    AND daily_turnover_limit_rate <= 1
    AND max_decision_turnover_rate > 0
    AND max_decision_turnover_rate <= daily_turnover_limit_rate
  ),
  CONSTRAINT chk_stock_institution_portfolio_interval CHECK (
    decision_interval_minutes BETWEEN 5 AND 1440
  ),
  CONSTRAINT chk_stock_institution_portfolio_version CHECK (policy_version > 0)
);

CREATE TABLE IF NOT EXISTS stock_institution_symbol_mandate (
  id BIGINT NOT NULL AUTO_INCREMENT,
  portfolio_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  base_symbol_weight DECIMAL(8,6) NOT NULL,
  min_portfolio_allocation_rate DECIMAL(8,6) NOT NULL DEFAULT 0.000000,
  max_portfolio_allocation_rate DECIMAL(8,6) NOT NULL,
  price_pressure_sensitivity DECIMAL(8,6) NOT NULL DEFAULT 0.000000,
  momentum_sensitivity DECIMAL(8,6) NOT NULL DEFAULT 0.000000,
  value_sensitivity DECIMAL(8,6) NOT NULL DEFAULT 0.000000,
  report_sensitivity DECIMAL(8,6) NOT NULL DEFAULT 0.000000,
  reference_daily_volume BIGINT NOT NULL,
  daily_participation_rate DECIMAL(8,6) NOT NULL DEFAULT 0.020000,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_institution_symbol_mandate (portfolio_id, symbol),
  KEY idx_stock_institution_symbol_mandate_symbol (symbol, enabled, portfolio_id),
  CONSTRAINT chk_stock_institution_mandate_base_weight CHECK (
    base_symbol_weight > 0 AND base_symbol_weight <= 1
  ),
  CONSTRAINT chk_stock_institution_mandate_allocation CHECK (
    min_portfolio_allocation_rate >= 0
    AND min_portfolio_allocation_rate <= max_portfolio_allocation_rate
    AND max_portfolio_allocation_rate <= 1
  ),
  CONSTRAINT chk_stock_institution_mandate_sensitivity CHECK (
    price_pressure_sensitivity BETWEEN -1 AND 1
    AND momentum_sensitivity BETWEEN -1 AND 1
    AND value_sensitivity BETWEEN -1 AND 1
    AND report_sensitivity BETWEEN -1 AND 1
  ),
  CONSTRAINT chk_stock_institution_mandate_reference CHECK (reference_daily_volume > 0),
  CONSTRAINT chk_stock_institution_mandate_participation CHECK (
    daily_participation_rate > 0 AND daily_participation_rate <= 0.200000
  )
);

CREATE TABLE IF NOT EXISTS stock_institution_decision_run (
  id BIGINT NOT NULL AUTO_INCREMENT,
  decision_slot DATETIME NOT NULL,
  simulation_trade_date DATE NOT NULL,
  portfolio_id BIGINT NOT NULL,
  execution_mode VARCHAR(20) NOT NULL,
  policy_version BIGINT NOT NULL,
  deterministic_seed BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'CLAIMED',
  error_message VARCHAR(1000) NULL,
  created_at DATETIME NOT NULL,
  completed_at DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_institution_decision_slot (portfolio_id, decision_slot),
  KEY idx_stock_institution_decision_run_date (simulation_trade_date, portfolio_id, decision_slot),
  KEY idx_stock_institution_decision_run_status (status, decision_slot, id),
  CONSTRAINT chk_stock_institution_decision_run_mode CHECK (`execution_mode` = 'LIVE'),
  CONSTRAINT chk_stock_institution_decision_run_status CHECK (
    CASE `status` WHEN 'CLAIMED' THEN 1 WHEN 'COMPLETED' THEN 1 WHEN 'FAILED' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_institution_decision_run_version CHECK (policy_version > 0)
);

CREATE TABLE IF NOT EXISTS stock_institution_decision_item (
  decision_run_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  primary_price_pressure INT NOT NULL,
  primary_asset_preference_pressure INT NOT NULL,
  primary_volatility_pressure INT NOT NULL,
  primary_liquidity_pressure INT NOT NULL,
  primary_execution_aggression_pressure INT NOT NULL,
  secondary_price_pressure INT NOT NULL,
  secondary_asset_preference_pressure INT NOT NULL,
  secondary_volatility_pressure INT NOT NULL,
  secondary_liquidity_pressure INT NOT NULL,
  secondary_execution_aggression_pressure INT NOT NULL,
  blended_price_pressure DECIMAL(8,6) NOT NULL,
  blended_asset_preference_pressure DECIMAL(8,6) NOT NULL,
  blended_volatility_pressure DECIMAL(8,6) NOT NULL,
  blended_liquidity_pressure DECIMAL(8,6) NOT NULL,
  blended_execution_aggression_pressure DECIMAL(8,6) NOT NULL,
  return_5_day DECIMAL(12,8) NOT NULL,
  return_20_day DECIMAL(12,8) NOT NULL,
  report_pressure DECIMAL(8,6) NOT NULL,
  current_price DECIMAL(19,2) NOT NULL,
  liquid_asset_amount DECIMAL(19,2) NOT NULL,
  actual_quantity BIGINT NOT NULL,
  open_buy_quantity BIGINT NOT NULL,
  open_sell_quantity BIGINT NOT NULL,
  projected_quantity BIGINT NOT NULL,
  actual_allocation_rate DECIMAL(12,8) NOT NULL,
  projected_allocation_rate DECIMAL(12,8) NOT NULL,
  base_allocation_rate DECIMAL(12,8) NOT NULL,
  target_stock_allocation_rate DECIMAL(12,8) NOT NULL,
  target_allocation_rate DECIMAL(12,8) NOT NULL,
  target_amount DECIMAL(19,2) NOT NULL,
  raw_trade_amount DECIMAL(19,2) NOT NULL,
  gated_trade_amount DECIMAL(19,2) NOT NULL,
  gated_quantity BIGINT NOT NULL,
  action VARCHAR(10) NOT NULL,
  decision_reason VARCHAR(50) NOT NULL,
  gate_reason VARCHAR(100) NOT NULL,
  reference_daily_volume BIGINT NOT NULL,
  remaining_daily_quantity_budget BIGINT NOT NULL,
  remaining_daily_notional_budget DECIMAL(19,2) NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (decision_run_id, symbol),
  KEY idx_stock_institution_decision_item_symbol (symbol, decision_run_id),
  KEY idx_stock_institution_decision_item_action (action, decision_run_id, symbol),
  CONSTRAINT chk_stock_institution_decision_item_pressure CHECK (
    primary_price_pressure BETWEEN -100 AND 100
    AND primary_asset_preference_pressure BETWEEN -100 AND 100
    AND primary_volatility_pressure BETWEEN -100 AND 100
    AND primary_liquidity_pressure BETWEEN -100 AND 100
    AND primary_execution_aggression_pressure BETWEEN -100 AND 100
    AND secondary_price_pressure BETWEEN -100 AND 100
    AND secondary_asset_preference_pressure BETWEEN -100 AND 100
    AND secondary_volatility_pressure BETWEEN -100 AND 100
    AND secondary_liquidity_pressure BETWEEN -100 AND 100
    AND secondary_execution_aggression_pressure BETWEEN -100 AND 100
  ),
  CONSTRAINT chk_stock_institution_decision_item_blended CHECK (
    blended_price_pressure BETWEEN -1 AND 1
    AND blended_asset_preference_pressure BETWEEN -1 AND 1
    AND blended_volatility_pressure BETWEEN -1 AND 1
    AND blended_liquidity_pressure BETWEEN -1 AND 1
    AND blended_execution_aggression_pressure BETWEEN -1 AND 1
    AND report_pressure BETWEEN -1 AND 1
  ),
  CONSTRAINT chk_stock_institution_decision_item_asset CHECK (
    current_price >= 0
    AND liquid_asset_amount >= 0
    AND actual_quantity >= 0
    AND open_buy_quantity >= 0
    AND open_sell_quantity >= 0
    AND projected_quantity >= 0
  ),
  CONSTRAINT chk_stock_institution_decision_item_rate CHECK (
    actual_allocation_rate >= 0
    AND projected_allocation_rate >= 0
    AND base_allocation_rate >= 0
    AND target_stock_allocation_rate BETWEEN 0 AND 1
    AND target_allocation_rate BETWEEN 0 AND 1
  ),
  CONSTRAINT chk_stock_institution_decision_item_trade CHECK (
    target_amount >= 0
    AND raw_trade_amount >= 0
    AND gated_trade_amount >= 0
    AND gated_trade_amount <= raw_trade_amount
    AND gated_quantity >= 0
    AND reference_daily_volume > 0
    AND remaining_daily_quantity_budget >= 0
    AND remaining_daily_notional_budget >= 0
  ),
  CONSTRAINT chk_stock_institution_decision_item_action CHECK (
    CASE `action` WHEN 'BUY' THEN 1 WHEN 'SELL' THEN 1 WHEN 'HOLD' THEN 1 ELSE 0 END = 1
  )
);

CREATE TABLE IF NOT EXISTS stock_institution_daily_budget (
  simulation_trade_date DATE NOT NULL,
  portfolio_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  reference_daily_volume BIGINT NOT NULL,
  gross_quantity_limit BIGINT NOT NULL,
  gross_notional_limit DECIMAL(19,2) NOT NULL,
  planned_buy_quantity BIGINT NOT NULL DEFAULT 0,
  planned_sell_quantity BIGINT NOT NULL DEFAULT 0,
  planned_buy_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  planned_sell_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  submitted_buy_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  submitted_sell_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  executed_buy_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  executed_sell_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  policy_version BIGINT NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (simulation_trade_date, portfolio_id, symbol),
  KEY idx_stock_institution_daily_budget_portfolio (
    portfolio_id, simulation_trade_date, symbol
  ),
  KEY idx_stock_institution_daily_budget_symbol (
    simulation_trade_date, symbol, portfolio_id
  ),
  CONSTRAINT chk_stock_institution_daily_budget_limit CHECK (
    reference_daily_volume > 0
    AND gross_quantity_limit > 0
    AND gross_notional_limit > 0
  ),
  CONSTRAINT chk_stock_institution_daily_budget_usage CHECK (
    planned_buy_quantity >= 0
    AND planned_sell_quantity >= 0
    AND planned_buy_quantity + planned_sell_quantity <= gross_quantity_limit
    AND planned_buy_amount >= 0
    AND planned_sell_amount >= 0
    AND planned_buy_amount + planned_sell_amount <= gross_notional_limit
    AND submitted_buy_amount >= 0
    AND submitted_sell_amount >= 0
    AND executed_buy_amount >= 0
    AND executed_sell_amount >= 0
  ),
  CONSTRAINT chk_stock_institution_daily_budget_version CHECK (
    policy_version > 0 AND version >= 0
  )
);

CREATE TABLE IF NOT EXISTS stock_institution_order_intent (
  decision_run_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  portfolio_id BIGINT NOT NULL,
  participant_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  side VARCHAR(10) NOT NULL,
  requested_quantity BIGINT NOT NULL,
  planned_amount DECIMAL(19,2) NOT NULL,
  reference_daily_volume BIGINT NOT NULL,
  execution_aggression_pressure DECIMAL(8,6) NOT NULL,
  policy_version BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  attempt_count INT NOT NULL DEFAULT 0,
  submitted_order_id BIGINT NULL,
  submitted_price DECIMAL(19,2) NULL,
  submitted_quantity BIGINT NOT NULL DEFAULT 0,
  submission_reason VARCHAR(200) NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  submitted_at DATETIME NULL,
  PRIMARY KEY (decision_run_id, symbol),
  UNIQUE KEY uk_stock_institution_order_intent_order (submitted_order_id),
  KEY idx_stock_institution_order_intent_pending (
    status, created_at, decision_run_id, symbol
  ),
  KEY idx_stock_institution_order_intent_portfolio (
    portfolio_id, status, decision_run_id, symbol
  ),
  CONSTRAINT chk_stock_institution_order_intent_side CHECK (
    CASE side WHEN 'BUY' THEN 1 WHEN 'SELL' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_institution_order_intent_quantity CHECK (
    requested_quantity > 0
    AND planned_amount > 0
    AND reference_daily_volume > 0
    AND submitted_quantity >= 0
    AND submitted_quantity <= requested_quantity
  ),
  CONSTRAINT chk_stock_institution_order_intent_pressure CHECK (
    execution_aggression_pressure BETWEEN -1 AND 1
  ),
  CONSTRAINT chk_stock_institution_order_intent_status CHECK (
    CASE status
      WHEN 'PENDING' THEN 1
      WHEN 'SUBMITTED' THEN 1
      WHEN 'REJECTED' THEN 1
      WHEN 'FAILED' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_institution_order_intent_submission CHECK (
    (
      status = 'SUBMITTED'
      AND submitted_order_id IS NOT NULL
      AND submitted_price > 0
      AND submitted_quantity > 0
      AND submitted_at IS NOT NULL
    )
    OR (
      status <> 'SUBMITTED'
      AND submitted_order_id IS NULL
      AND submitted_price IS NULL
      AND submitted_quantity = 0
      AND submitted_at IS NULL
    )
  ),
  CONSTRAINT chk_stock_institution_order_intent_version CHECK (policy_version > 0),
  CONSTRAINT chk_stock_institution_order_intent_attempt CHECK (
    attempt_count BETWEEN 0 AND 3
  )
);
