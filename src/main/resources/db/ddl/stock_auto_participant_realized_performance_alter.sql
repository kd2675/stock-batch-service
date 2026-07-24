USE STOCK_SERVICE;

CREATE TABLE IF NOT EXISTS stock_auto_participant_performance_state (
  account_id BIGINT NOT NULL,
  recent_profitable_trading_days INT NOT NULL DEFAULT 0,
  recent_closed_trading_days INT NOT NULL DEFAULT 0,
  last_seen_business_date DATE NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (account_id),
  KEY idx_stock_auto_performance_last_seen (last_seen_business_date, account_id),
  CONSTRAINT chk_stock_auto_performance_recent_days CHECK (
    recent_profitable_trading_days >= 0
    AND recent_closed_trading_days >= 0
    AND recent_closed_trading_days <= 20
    AND recent_profitable_trading_days <= recent_closed_trading_days
  )
);
