USE STOCK_SERVICE;

CREATE TABLE IF NOT EXISTS stock_auto_participant_event_profile_config (
  profile_type VARCHAR(40) NOT NULL,
  shareholder_subscription_rate DECIMAL(8,4) NOT NULL DEFAULT 0.4500,
  public_offering_subscription_rate DECIMAL(8,4) NOT NULL DEFAULT 0.2000,
  max_cash_allocation_rate DECIMAL(8,4) NOT NULL DEFAULT 0.2000,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (profile_type),
  CONSTRAINT chk_stock_auto_event_profile_type CHECK (
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
  CONSTRAINT chk_stock_auto_event_profile_shareholder_rate CHECK (shareholder_subscription_rate >= 0 AND shareholder_subscription_rate <= 1),
  CONSTRAINT chk_stock_auto_event_profile_public_rate CHECK (public_offering_subscription_rate >= 0 AND public_offering_subscription_rate <= 1),
  CONSTRAINT chk_stock_auto_event_profile_cash_rate CHECK (max_cash_allocation_rate >= 0 AND max_cash_allocation_rate <= 1)
);
