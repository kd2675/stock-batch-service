USE STOCK_SERVICE;

CREATE TABLE IF NOT EXISTS stock_auto_participant_profile_config (
  profile_type VARCHAR(40) NOT NULL,
  order_multiplier DECIMAL(8,4) NOT NULL,
  aggression_multiplier DECIMAL(8,4) NOT NULL,
  order_ttl_multiplier DECIMAL(8,4) NOT NULL DEFAULT 1.0000,
  quantity_multiplier DECIMAL(8,4) NOT NULL,
  holding_patience_weight DECIMAL(8,4) NOT NULL,
  deep_loss_hold_weight DECIMAL(8,4) NOT NULL,
  profit_taking_weight DECIMAL(8,4) NOT NULL DEFAULT 0.0000,
  recurring_deposit_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  recurring_deposit_interval_days INT NOT NULL DEFAULT 30,
  recurring_deposit_interval_value DECIMAL(12,4) NOT NULL DEFAULT 30.0000,
  recurring_deposit_interval_unit VARCHAR(20) NOT NULL DEFAULT 'DAY',
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (profile_type),
  CONSTRAINT chk_stock_auto_profile_config_type CHECK (
    CASE `profile_type`
      WHEN 'NEWS_REACTIVE' THEN 1
      WHEN 'MOMENTUM_FOLLOWER' THEN 1
      WHEN 'CONTRARIAN' THEN 1
      WHEN 'LOSS_AVERSE' THEN 1
      WHEN 'OVERCONFIDENT' THEN 1
      WHEN 'HERD_FOLLOWER' THEN 1
      WHEN 'MARKET_MAKER' THEN 1
      WHEN 'NOISE_TRADER' THEN 1
      WHEN 'VALUE_ANCHOR' THEN 1
      WHEN 'SCALPER' THEN 1
      WHEN 'DAY_TRADER' THEN 1
      WHEN 'SWING_TRADER' THEN 1
      WHEN 'LONG_TERM_HOLDER' THEN 1
      WHEN 'PAYDAY_ACCUMULATOR' THEN 1
      WHEN 'DIVIDEND_REINVESTOR' THEN 1
      WHEN 'LIMIT_DOWN_TRAPPED' THEN 1
      WHEN 'AVERAGE_DOWN_BUYER' THEN 1
      WHEN 'STOP_LOSS_TRADER' THEN 1
      WHEN 'FOMO_BUYER' THEN 1
      WHEN 'PANIC_SELLER' THEN 1
      WHEN 'DIP_BUYER' THEN 1
      WHEN 'PROFIT_LOCKER' THEN 1
      WHEN 'LIQUIDITY_AVOIDANT' THEN 1
      WHEN 'CASH_DEFENSIVE' THEN 1
      WHEN 'WHALE' THEN 1
      WHEN 'SMALL_DIVERSIFIER' THEN 1
      WHEN 'OBSERVER' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_auto_profile_order_multiplier CHECK (order_multiplier >= 0 AND order_multiplier <= 5),
  CONSTRAINT chk_stock_auto_profile_aggression_multiplier CHECK (aggression_multiplier >= 0 AND aggression_multiplier <= 5),
  CONSTRAINT chk_stock_auto_profile_order_ttl_multiplier CHECK (order_ttl_multiplier >= 0.1 AND order_ttl_multiplier <= 10),
  CONSTRAINT chk_stock_auto_profile_quantity_multiplier CHECK (quantity_multiplier >= 0 AND quantity_multiplier <= 5),
  CONSTRAINT chk_stock_auto_profile_holding_patience CHECK (holding_patience_weight >= 0 AND holding_patience_weight <= 1),
  CONSTRAINT chk_stock_auto_profile_deep_loss_hold CHECK (deep_loss_hold_weight >= 0 AND deep_loss_hold_weight <= 1),
  CONSTRAINT chk_stock_auto_profile_profit_taking CHECK (profit_taking_weight >= 0 AND profit_taking_weight <= 1),
  CONSTRAINT chk_stock_auto_profile_recurring_deposit CHECK (recurring_deposit_amount >= 0),
  CONSTRAINT chk_stock_auto_profile_recurring_interval CHECK (recurring_deposit_interval_days >= 1),
  CONSTRAINT chk_stock_auto_profile_recurring_interval_value CHECK (recurring_deposit_interval_value >= 0 AND recurring_deposit_interval_value <= 1000),
  CONSTRAINT chk_stock_auto_profile_recurring_interval_unit CHECK (
    CASE `recurring_deposit_interval_unit`
      WHEN 'SECOND' THEN 1
      WHEN 'MINUTE' THEN 1
      WHEN 'HOUR' THEN 1
      WHEN 'DAY' THEN 1
      WHEN 'MONTH' THEN 1
      WHEN 'YEAR' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_auto_profile_recurring_interval_complete CHECK (
    recurring_deposit_amount = 0
    OR (recurring_deposit_interval_value > 0 AND recurring_deposit_interval_unit IS NOT NULL)
  )
);
