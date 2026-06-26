USE STOCK_SERVICE;

CREATE TABLE IF NOT EXISTS stock_market_close_run (
  id BIGINT NOT NULL AUTO_INCREMENT,
  business_date DATE NOT NULL,
  closed_at DATETIME NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
  cancelled_order_count INT NOT NULL DEFAULT 0,
  holding_snapshot_count INT NOT NULL DEFAULT 0,
  price_rollover_count INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  completed_at DATETIME NULL,
  PRIMARY KEY (id),
  KEY idx_stock_market_close_run_date_time (business_date, closed_at, id),
  CONSTRAINT chk_stock_market_close_run_status CHECK (CASE `status` WHEN 'STARTED' THEN 1 WHEN 'COMPLETED' THEN 1 WHEN 'FAILED' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_market_close_run_cancelled_non_negative CHECK (cancelled_order_count >= 0),
  CONSTRAINT chk_stock_market_close_run_snapshot_non_negative CHECK (holding_snapshot_count >= 0),
  CONSTRAINT chk_stock_market_close_run_price_non_negative CHECK (price_rollover_count >= 0)
);

ALTER TABLE stock_market_close_run
  ADD COLUMN symbol VARCHAR(20) NULL AFTER id,
  ADD KEY idx_stock_market_close_run_symbol_time (symbol, closed_at, id);

CREATE TABLE IF NOT EXISTS stock_holding_snapshot (
  id BIGINT NOT NULL AUTO_INCREMENT,
  close_run_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  quantity BIGINT NOT NULL,
  reserved_quantity BIGINT NOT NULL DEFAULT 0,
  average_price DECIMAL(19,2) NOT NULL,
  snapshot_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_holding_snapshot_run_account_symbol (close_run_id, account_id, symbol),
  KEY idx_stock_holding_snapshot_symbol_run (symbol, close_run_id, account_id),
  KEY idx_stock_holding_snapshot_account_time (account_id, snapshot_at, id),
  CONSTRAINT chk_stock_holding_snapshot_quantity_non_negative CHECK (quantity >= 0),
  CONSTRAINT chk_stock_holding_snapshot_reserved_valid CHECK (reserved_quantity >= 0 AND reserved_quantity <= quantity),
  CONSTRAINT chk_stock_holding_snapshot_average_price_positive CHECK (average_price > 0)
);

ALTER TABLE stock_corporate_action_entitlement
  ADD COLUMN holding_snapshot_run_id BIGINT NULL AFTER status,
  ADD KEY idx_stock_corporate_action_entitlement_snapshot_run (holding_snapshot_run_id);
