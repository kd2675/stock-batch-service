USE STOCK_SERVICE;

CREATE TABLE IF NOT EXISTS stock_auto_participant_order_schedule (
  user_key VARCHAR(64) NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  profile_type VARCHAR(40) NOT NULL,
  next_run_at DATETIME NOT NULL,
  last_run_at DATETIME NULL,
  lease_until DATETIME NULL,
  lease_owner VARCHAR(80) NULL,
  run_interval_seconds INT NOT NULL,
  priority INT NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (user_key, symbol),
  KEY idx_stock_auto_order_schedule_due (symbol, next_run_at, lease_until, priority, user_key),
  KEY idx_stock_auto_order_schedule_profile_due (profile_type, next_run_at, symbol),
  CONSTRAINT chk_stock_auto_order_schedule_interval CHECK (run_interval_seconds > 0),
  CONSTRAINT chk_stock_auto_order_schedule_priority CHECK (priority between 1 and 100)
);

ALTER TABLE stock_order
  ADD INDEX idx_stock_order_order_book_expiry (market_type, symbol, created_at, id, status, account_id);
