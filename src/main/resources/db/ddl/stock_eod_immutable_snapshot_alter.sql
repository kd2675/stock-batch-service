USE STOCK_SERVICE;

SET @stock_eod_holding_column_clauses = CONCAT_WS(', ',
  IF((SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'stock_holding_snapshot'
         AND column_name = 'close_cycle_id') = 0,
     'ADD COLUMN close_cycle_id BIGINT NULL AFTER id', NULL),
  IF((SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'stock_holding_snapshot'
         AND column_name = 'evaluation_price') = 0,
     'ADD COLUMN evaluation_price DECIMAL(19,2) NULL AFTER average_price', NULL)
);
SET @stock_eod_immutable_sql = IF(
  @stock_eod_holding_column_clauses = '',
  'SELECT 1',
  CONCAT('ALTER TABLE stock_holding_snapshot ', @stock_eod_holding_column_clauses)
);
PREPARE stock_eod_immutable_statement FROM @stock_eod_immutable_sql;
EXECUTE stock_eod_immutable_statement;
DEALLOCATE PREPARE stock_eod_immutable_statement;

SET @stock_eod_immutable_sql = (
  SELECT CASE WHEN COUNT(*) = 0 THEN
    'ALTER TABLE stock_holding_snapshot ADD KEY idx_stock_holding_snapshot_cycle_account (close_cycle_id, account_id, symbol)'
  ELSE 'SELECT 1' END
    FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_holding_snapshot'
     AND index_name = 'idx_stock_holding_snapshot_cycle_account'
);
PREPARE stock_eod_immutable_statement FROM @stock_eod_immutable_sql;
EXECUTE stock_eod_immutable_statement;
DEALLOCATE PREPARE stock_eod_immutable_statement;

SET @stock_eod_immutable_sql = (
  SELECT CASE WHEN COUNT(*) = 0 THEN
    'ALTER TABLE stock_holding_snapshot ADD CONSTRAINT chk_stock_holding_snapshot_evaluation_price CHECK (evaluation_price IS NULL OR evaluation_price >= 0)'
  ELSE 'SELECT 1' END
    FROM information_schema.table_constraints
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_holding_snapshot'
     AND constraint_name = 'chk_stock_holding_snapshot_evaluation_price'
);
PREPARE stock_eod_immutable_statement FROM @stock_eod_immutable_sql;
EXECUTE stock_eod_immutable_statement;
DEALLOCATE PREPARE stock_eod_immutable_statement;

UPDATE stock_holding_snapshot holding_snapshot
JOIN stock_market_close_run close_run ON close_run.id = holding_snapshot.close_run_id
LEFT JOIN stock_post_close_cycle cycle
  ON cycle.business_date = close_run.business_date
 AND cycle.scope_type = CASE WHEN close_run.symbol IS NULL THEN 'FULL_MARKET' ELSE 'SYMBOL' END
 AND cycle.scope_key = CASE WHEN close_run.symbol IS NULL THEN 'ALL' ELSE close_run.symbol END
SET holding_snapshot.close_cycle_id = cycle.id
WHERE holding_snapshot.close_cycle_id IS NULL;

CREATE TABLE IF NOT EXISTS stock_post_close_cycle_metric (
  close_cycle_id BIGINT NOT NULL,
  close_run_id BIGINT NULL,
  captured_open_order_count BIGINT NOT NULL DEFAULT 0,
  cancelled_order_count BIGINT NOT NULL DEFAULT 0,
  released_buy_cash DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  released_sell_quantity BIGINT NOT NULL DEFAULT 0,
  settlement_target_account_count BIGINT NOT NULL DEFAULT 0,
  account_snapshot_count BIGINT NOT NULL DEFAULT 0,
  holding_snapshot_count BIGINT NOT NULL DEFAULT 0,
  price_snapshot_count BIGINT NOT NULL DEFAULT 0,
  open_order_summary_count BIGINT NOT NULL DEFAULT 0,
  reconciliation_mismatch_count BIGINT NOT NULL DEFAULT 0,
  settled_account_count BIGINT NOT NULL DEFAULT 0,
  settlement_missing_account_count BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (close_cycle_id),
  KEY idx_stock_post_close_cycle_metric_run (close_run_id),
  CONSTRAINT chk_stock_post_close_cycle_metric_counts CHECK (
    captured_open_order_count >= 0
    AND cancelled_order_count >= 0
    AND settlement_target_account_count >= 0
    AND account_snapshot_count >= 0
    AND holding_snapshot_count >= 0
    AND price_snapshot_count >= 0
    AND open_order_summary_count >= 0
    AND reconciliation_mismatch_count >= 0
    AND settled_account_count >= 0
    AND settlement_missing_account_count >= 0
  ),
  CONSTRAINT chk_stock_post_close_cycle_metric_releases CHECK (
    released_buy_cash >= 0
    AND released_sell_quantity >= 0
  )
);

SET @stock_eod_cycle_metric_column_clauses = CONCAT_WS(', ',
  IF((SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'stock_post_close_cycle_metric'
         AND column_name = 'released_buy_cash') = 0,
     'ADD COLUMN released_buy_cash DECIMAL(19,2) NOT NULL DEFAULT 0.00 AFTER cancelled_order_count', NULL),
  IF((SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'stock_post_close_cycle_metric'
         AND column_name = 'released_sell_quantity') = 0,
     'ADD COLUMN released_sell_quantity BIGINT NOT NULL DEFAULT 0 AFTER released_buy_cash', NULL)
);
SET @stock_eod_immutable_sql = IF(
  @stock_eod_cycle_metric_column_clauses = '',
  'SELECT 1',
  CONCAT('ALTER TABLE stock_post_close_cycle_metric ', @stock_eod_cycle_metric_column_clauses)
);
PREPARE stock_eod_immutable_statement FROM @stock_eod_immutable_sql;
EXECUTE stock_eod_immutable_statement;
DEALLOCATE PREPARE stock_eod_immutable_statement;

SET @stock_eod_immutable_sql = (
  SELECT CASE WHEN COUNT(*) = 0 THEN
    'ALTER TABLE stock_post_close_cycle_metric ADD CONSTRAINT chk_stock_post_close_cycle_metric_releases CHECK (released_buy_cash >= 0 AND released_sell_quantity >= 0)'
  ELSE 'SELECT 1' END
    FROM information_schema.table_constraints
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_post_close_cycle_metric'
     AND constraint_name = 'chk_stock_post_close_cycle_metric_releases'
);
PREPARE stock_eod_immutable_statement FROM @stock_eod_immutable_sql;
EXECUTE stock_eod_immutable_statement;
DEALLOCATE PREPARE stock_eod_immutable_statement;

CREATE TABLE IF NOT EXISTS stock_close_account_snapshot (
  id BIGINT NOT NULL AUTO_INCREMENT,
  close_cycle_id BIGINT NOT NULL,
  close_run_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  user_key VARCHAR(64) NULL,
  account_status VARCHAR(20) NOT NULL,
  participant_category VARCHAR(30) NOT NULL DEFAULT 'MANUAL_PARTICIPANT',
  settlement_target BOOLEAN NOT NULL,
  pre_cancel_cash DECIMAL(19,2) NOT NULL,
  pre_cancel_order_reserved_cash DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  subscription_reserved_cash DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  post_cancel_cash DECIMAL(19,2) NULL,
  external_net_cash_flow DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  cash_flow_watermark_id BIGINT NOT NULL DEFAULT 0,
  holding_market_value DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  holding_quantity BIGINT NOT NULL DEFAULT 0,
  reserved_sell_quantity BIGINT NOT NULL DEFAULT 0,
  holding_position_count BIGINT NOT NULL DEFAULT 0,
  reconciliation_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  snapshot_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_close_account_snapshot_cycle_account (close_cycle_id, account_id),
  KEY idx_stock_close_account_snapshot_cycle_target (close_cycle_id, settlement_target, account_id),
  KEY idx_stock_close_account_snapshot_cycle_reconciliation (close_cycle_id, reconciliation_status, account_id),
  KEY idx_stock_close_account_snapshot_run_target (close_run_id, settlement_target, account_id),
  KEY idx_stock_close_account_snapshot_account_cycle (account_id, close_cycle_id),
  CONSTRAINT chk_stock_close_account_snapshot_cash CHECK (
    pre_cancel_cash >= 0
    AND pre_cancel_order_reserved_cash >= 0
    AND subscription_reserved_cash >= 0
    AND (post_cancel_cash IS NULL OR post_cancel_cash >= 0)
  ),
  CONSTRAINT chk_stock_close_account_snapshot_watermark CHECK (cash_flow_watermark_id >= 0),
  CONSTRAINT chk_stock_close_account_snapshot_holding_summary CHECK (
    holding_market_value >= 0
    AND holding_quantity >= 0
    AND reserved_sell_quantity >= 0
    AND reserved_sell_quantity <= holding_quantity
    AND holding_position_count >= 0
  ),
  CONSTRAINT chk_stock_close_account_snapshot_reconciliation CHECK (
    CASE `reconciliation_status` WHEN 'PENDING' THEN 1 WHEN 'MATCHED' THEN 1 WHEN 'MISMATCHED' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_close_account_snapshot_participant_category CHECK (
    CASE `participant_category`
      WHEN 'MANUAL_PARTICIPANT' THEN 1
      WHEN 'AUTO_PARTICIPANT' THEN 1
      WHEN 'LISTING_UNDERWRITER' THEN 1
      ELSE 0
    END = 1
  )
);

CREATE TABLE IF NOT EXISTS stock_close_price_snapshot (
  id BIGINT NOT NULL AUTO_INCREMENT,
  close_cycle_id BIGINT NOT NULL,
  close_run_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  close_price DECIMAL(19,2) NOT NULL,
  previous_close DECIMAL(19,2) NOT NULL,
  price_time DATETIME NULL,
  price_provider VARCHAR(40) NULL,
  last_execution_id BIGINT NULL,
  order_book_symbol BOOLEAN NOT NULL,
  snapshot_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_close_price_snapshot_cycle_symbol (close_cycle_id, symbol),
  KEY idx_stock_close_price_snapshot_run_symbol (close_run_id, symbol),
  CONSTRAINT chk_stock_close_price_snapshot_price CHECK (close_price >= 0 AND previous_close >= 0),
  CONSTRAINT chk_stock_close_price_snapshot_execution CHECK (last_execution_id IS NULL OR last_execution_id > 0)
);

CREATE TABLE IF NOT EXISTS stock_close_open_order_summary (
  id BIGINT NOT NULL AUTO_INCREMENT,
  close_cycle_id BIGINT NOT NULL,
  close_run_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  pre_cancel_open_order_count BIGINT NOT NULL DEFAULT 0,
  pre_cancel_buy_order_count BIGINT NOT NULL DEFAULT 0,
  pre_cancel_sell_order_count BIGINT NOT NULL DEFAULT 0,
  pre_cancel_remaining_buy_quantity BIGINT NOT NULL DEFAULT 0,
  pre_cancel_remaining_sell_quantity BIGINT NOT NULL DEFAULT 0,
  pre_cancel_reserved_buy_cash DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  pre_cancel_reserved_sell_quantity BIGINT NOT NULL DEFAULT 0,
  post_cancel_open_order_count BIGINT NOT NULL DEFAULT 0,
  reconciliation_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  snapshot_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_close_open_order_summary_cycle_symbol (close_cycle_id, symbol),
  KEY idx_stock_close_open_order_summary_run (close_run_id, symbol),
  CONSTRAINT chk_stock_close_open_order_summary_counts CHECK (
    pre_cancel_open_order_count >= 0
    AND pre_cancel_buy_order_count >= 0
    AND pre_cancel_sell_order_count >= 0
    AND pre_cancel_remaining_buy_quantity >= 0
    AND pre_cancel_remaining_sell_quantity >= 0
    AND pre_cancel_reserved_buy_cash >= 0
    AND pre_cancel_reserved_sell_quantity >= 0
    AND post_cancel_open_order_count >= 0
  ),
  CONSTRAINT chk_stock_close_open_order_summary_reconciliation CHECK (
    CASE `reconciliation_status` WHEN 'PENDING' THEN 1 WHEN 'MATCHED' THEN 1 WHEN 'MISMATCHED' THEN 1 ELSE 0 END = 1
  )
);

CREATE TABLE IF NOT EXISTS stock_close_open_order_snapshot (
  id BIGINT NOT NULL AUTO_INCREMENT,
  close_cycle_id BIGINT NOT NULL,
  close_run_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  side VARCHAR(10) NOT NULL,
  source_order_status VARCHAR(20) NOT NULL,
  remaining_quantity BIGINT NOT NULL,
  reserved_cash DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  captured_at DATETIME NOT NULL,
  released_at DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_close_open_order_snapshot_cycle_order (close_cycle_id, order_id),
  KEY idx_stock_close_open_order_snapshot_run_order (close_run_id, order_id),
  KEY idx_stock_close_open_order_snapshot_cycle_release_order (close_cycle_id, released_at, order_id),
  KEY idx_stock_close_open_order_snapshot_cycle_account (close_cycle_id, account_id, side),
  KEY idx_stock_close_open_order_snapshot_cycle_holding (close_cycle_id, symbol, account_id, side),
  KEY idx_stock_close_open_order_snapshot_cycle_stream (close_cycle_id, symbol, source_order_status, order_id),
  CONSTRAINT chk_stock_close_open_order_snapshot_side CHECK (CASE `side` WHEN 'BUY' THEN 1 WHEN 'SELL' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_close_open_order_snapshot_status CHECK (
    CASE `source_order_status` WHEN 'PENDING' THEN 1 WHEN 'PARTIALLY_FILLED' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_close_open_order_snapshot_quantity CHECK (remaining_quantity > 0),
  CONSTRAINT chk_stock_close_open_order_snapshot_cash CHECK (reserved_cash >= 0)
);

SET @stock_eod_portfolio_column_clauses = CONCAT_WS(', ',
  IF((SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'portfolio_snapshot'
         AND column_name = 'close_cycle_id') = 0,
     'ADD COLUMN close_cycle_id BIGINT NULL AFTER id', NULL),
  IF((SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'portfolio_snapshot'
         AND column_name = 'close_run_id') = 0,
     'ADD COLUMN close_run_id BIGINT NULL AFTER close_cycle_id', NULL),
  IF((SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'portfolio_snapshot'
         AND column_name = 'input_hash') = 0,
     'ADD COLUMN input_hash VARCHAR(64) NULL AFTER return_rate', NULL),
  IF((SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'portfolio_snapshot'
         AND column_name = 'calculation_version') = 0,
     'ADD COLUMN calculation_version VARCHAR(40) NULL AFTER input_hash', NULL),
  IF((SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'portfolio_snapshot'
         AND column_name = 'data_quality_status') = 0,
     'ADD COLUMN data_quality_status VARCHAR(20) NULL AFTER calculation_version', NULL),
  IF((SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'portfolio_snapshot'
         AND column_name = 'source_build_version') = 0,
     'ADD COLUMN source_build_version VARCHAR(100) NULL AFTER data_quality_status', NULL)
);
SET @stock_eod_immutable_sql = IF(
  @stock_eod_portfolio_column_clauses = '',
  'SELECT 1',
  CONCAT('ALTER TABLE portfolio_snapshot ', @stock_eod_portfolio_column_clauses)
);
PREPARE stock_eod_immutable_statement FROM @stock_eod_immutable_sql;
EXECUTE stock_eod_immutable_statement;
DEALLOCATE PREPARE stock_eod_immutable_statement;

SET @stock_eod_portfolio_index_clauses = CONCAT_WS(', ',
  IF((SELECT COUNT(*) FROM information_schema.statistics
       WHERE table_schema = DATABASE() AND table_name = 'portfolio_snapshot'
         AND index_name = 'uk_portfolio_snapshot_cycle_account') = 0,
     'ADD UNIQUE KEY uk_portfolio_snapshot_cycle_account (close_cycle_id, account_id)', NULL),
  IF((SELECT COUNT(*) FROM information_schema.statistics
       WHERE table_schema = DATABASE() AND table_name = 'portfolio_snapshot'
         AND index_name = 'idx_portfolio_snapshot_close_run') = 0,
     'ADD KEY idx_portfolio_snapshot_close_run (close_run_id, account_id)', NULL)
);
SET @stock_eod_immutable_sql = IF(
  @stock_eod_portfolio_index_clauses = '',
  'SELECT 1',
  CONCAT('ALTER TABLE portfolio_snapshot ', @stock_eod_portfolio_index_clauses)
);
PREPARE stock_eod_immutable_statement FROM @stock_eod_immutable_sql;
EXECUTE stock_eod_immutable_statement;
DEALLOCATE PREPARE stock_eod_immutable_statement;

SET @stock_eod_portfolio_constraint_clauses = CONCAT_WS(', ',
  IF((SELECT COUNT(*) FROM information_schema.table_constraints
       WHERE table_schema = DATABASE() AND table_name = 'portfolio_snapshot'
         AND constraint_name = 'chk_portfolio_snapshot_input_hash') = 0,
     'ADD CONSTRAINT chk_portfolio_snapshot_input_hash CHECK (input_hash IS NULL OR char_length(input_hash) = 64)', NULL),
  IF((SELECT COUNT(*) FROM information_schema.table_constraints
       WHERE table_schema = DATABASE() AND table_name = 'portfolio_snapshot'
         AND constraint_name = 'chk_portfolio_snapshot_data_quality') = 0,
     'ADD CONSTRAINT chk_portfolio_snapshot_data_quality CHECK (data_quality_status IS NULL OR CASE `data_quality_status` WHEN ''VERIFIED'' THEN 1 WHEN ''WARNING'' THEN 1 WHEN ''INVALID'' THEN 1 ELSE 0 END = 1)', NULL)
);
SET @stock_eod_immutable_sql = IF(
  @stock_eod_portfolio_constraint_clauses = '',
  'SELECT 1',
  CONCAT('ALTER TABLE portfolio_snapshot ', @stock_eod_portfolio_constraint_clauses)
);
PREPARE stock_eod_immutable_statement FROM @stock_eod_immutable_sql;
EXECUTE stock_eod_immutable_statement;
DEALLOCATE PREPARE stock_eod_immutable_statement;
