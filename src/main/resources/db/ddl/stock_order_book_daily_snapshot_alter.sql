USE STOCK_SERVICE;

CREATE TABLE IF NOT EXISTS stock_order_book_daily_snapshot (
  id BIGINT NOT NULL AUTO_INCREMENT,
  close_run_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  simulation_trade_date DATE NOT NULL,
  snapshot_at DATETIME NOT NULL,
  name VARCHAR(120) NOT NULL,
  market VARCHAR(20) NOT NULL,
  enabled BIT NOT NULL,
  market_enabled BIT NOT NULL,
  market_status VARCHAR(20) NOT NULL,
  issued_shares BIGINT NOT NULL,
  tradable_shares BIGINT NOT NULL,
  initial_price DECIMAL(19,2) NOT NULL,
  tick_size DECIMAL(19,2) NOT NULL,
  price_limit_rate DECIMAL(5,2) NOT NULL,
  close_price DECIMAL(19,2) NOT NULL,
  previous_close DECIMAL(19,2) NOT NULL,
  change_rate DECIMAL(9,4) NOT NULL DEFAULT 0.0000,
  price_time DATETIME NULL,
  price_provider VARCHAR(40) NULL,
  execution_count BIGINT NOT NULL DEFAULT 0,
  execution_quantity BIGINT NOT NULL DEFAULT 0,
  turnover_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  buy_quantity BIGINT NOT NULL DEFAULT 0,
  sell_quantity BIGINT NOT NULL DEFAULT 0,
  buy_net_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  sell_net_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  open_order_count BIGINT NOT NULL DEFAULT 0,
  open_buy_order_count BIGINT NOT NULL DEFAULT 0,
  open_sell_order_count BIGINT NOT NULL DEFAULT 0,
  reserved_buy_cash DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  holder_count BIGINT NOT NULL DEFAULT 0,
  holding_quantity BIGINT NOT NULL DEFAULT 0,
  pending_corporate_action_count BIGINT NOT NULL DEFAULT 0,
  last_executed_at DATETIME NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_order_book_daily_snapshot_run_symbol (close_run_id, symbol),
  KEY idx_stock_order_book_daily_snapshot_date_symbol (simulation_trade_date, symbol, close_run_id),
  KEY idx_stock_order_book_daily_snapshot_symbol_date (symbol, simulation_trade_date, close_run_id),
  CONSTRAINT chk_stock_order_book_daily_snapshot_issued CHECK (issued_shares > 0),
  CONSTRAINT chk_stock_order_book_daily_snapshot_tradable CHECK (tradable_shares >= 0 AND tradable_shares <= issued_shares),
  CONSTRAINT chk_stock_order_book_daily_snapshot_price CHECK (close_price >= 0 AND previous_close >= 0 AND initial_price > 0),
  CONSTRAINT chk_stock_order_book_daily_snapshot_flow CHECK (execution_count >= 0 AND execution_quantity >= 0 AND turnover_amount >= 0 AND buy_quantity >= 0 AND sell_quantity >= 0 AND buy_net_amount >= 0 AND sell_net_amount >= 0),
  CONSTRAINT chk_stock_order_book_daily_snapshot_open_order CHECK (open_order_count >= 0 AND open_buy_order_count >= 0 AND open_sell_order_count >= 0 AND reserved_buy_cash >= 0),
  CONSTRAINT chk_stock_order_book_daily_snapshot_holding CHECK (holder_count >= 0 AND holding_quantity >= 0 AND pending_corporate_action_count >= 0),
  CONSTRAINT chk_stock_order_book_daily_snapshot_tick CHECK (tick_size > 0),
  CONSTRAINT chk_stock_order_book_daily_snapshot_limit CHECK (price_limit_rate > 0 AND price_limit_rate <= 100)
);

DROP PROCEDURE IF EXISTS add_stock_daily_snapshot_column;

DELIMITER //
CREATE PROCEDURE add_stock_daily_snapshot_column(IN column_name_value VARCHAR(64), IN alter_sql_value TEXT)
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_order_book_daily_snapshot'
       AND column_name = column_name_value
  ) THEN
    SET @add_column_sql = alter_sql_value;
    PREPARE add_column_stmt FROM @add_column_sql;
    EXECUTE add_column_stmt;
    DEALLOCATE PREPARE add_column_stmt;
  END IF;
END//
DELIMITER ;

CALL add_stock_daily_snapshot_column('market_enabled', 'ALTER TABLE stock_order_book_daily_snapshot ADD COLUMN market_enabled BIT NOT NULL DEFAULT 0 AFTER enabled');
CALL add_stock_daily_snapshot_column('execution_count', 'ALTER TABLE stock_order_book_daily_snapshot ADD COLUMN execution_count BIGINT NOT NULL DEFAULT 0 AFTER price_provider');
CALL add_stock_daily_snapshot_column('execution_quantity', 'ALTER TABLE stock_order_book_daily_snapshot ADD COLUMN execution_quantity BIGINT NOT NULL DEFAULT 0 AFTER execution_count');
CALL add_stock_daily_snapshot_column('turnover_amount', 'ALTER TABLE stock_order_book_daily_snapshot ADD COLUMN turnover_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00 AFTER execution_quantity');
CALL add_stock_daily_snapshot_column('buy_quantity', 'ALTER TABLE stock_order_book_daily_snapshot ADD COLUMN buy_quantity BIGINT NOT NULL DEFAULT 0 AFTER turnover_amount');
CALL add_stock_daily_snapshot_column('sell_quantity', 'ALTER TABLE stock_order_book_daily_snapshot ADD COLUMN sell_quantity BIGINT NOT NULL DEFAULT 0 AFTER buy_quantity');
CALL add_stock_daily_snapshot_column('buy_net_amount', 'ALTER TABLE stock_order_book_daily_snapshot ADD COLUMN buy_net_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00 AFTER sell_quantity');
CALL add_stock_daily_snapshot_column('sell_net_amount', 'ALTER TABLE stock_order_book_daily_snapshot ADD COLUMN sell_net_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00 AFTER buy_net_amount');
CALL add_stock_daily_snapshot_column('open_order_count', 'ALTER TABLE stock_order_book_daily_snapshot ADD COLUMN open_order_count BIGINT NOT NULL DEFAULT 0 AFTER sell_net_amount');
CALL add_stock_daily_snapshot_column('open_buy_order_count', 'ALTER TABLE stock_order_book_daily_snapshot ADD COLUMN open_buy_order_count BIGINT NOT NULL DEFAULT 0 AFTER open_order_count');
CALL add_stock_daily_snapshot_column('open_sell_order_count', 'ALTER TABLE stock_order_book_daily_snapshot ADD COLUMN open_sell_order_count BIGINT NOT NULL DEFAULT 0 AFTER open_buy_order_count');
CALL add_stock_daily_snapshot_column('reserved_buy_cash', 'ALTER TABLE stock_order_book_daily_snapshot ADD COLUMN reserved_buy_cash DECIMAL(19,2) NOT NULL DEFAULT 0.00 AFTER open_sell_order_count');
CALL add_stock_daily_snapshot_column('holder_count', 'ALTER TABLE stock_order_book_daily_snapshot ADD COLUMN holder_count BIGINT NOT NULL DEFAULT 0 AFTER reserved_buy_cash');
CALL add_stock_daily_snapshot_column('holding_quantity', 'ALTER TABLE stock_order_book_daily_snapshot ADD COLUMN holding_quantity BIGINT NOT NULL DEFAULT 0 AFTER holder_count');
CALL add_stock_daily_snapshot_column('pending_corporate_action_count', 'ALTER TABLE stock_order_book_daily_snapshot ADD COLUMN pending_corporate_action_count BIGINT NOT NULL DEFAULT 0 AFTER holding_quantity');
CALL add_stock_daily_snapshot_column('last_executed_at', 'ALTER TABLE stock_order_book_daily_snapshot ADD COLUMN last_executed_at DATETIME NULL AFTER pending_corporate_action_count');

DROP PROCEDURE IF EXISTS add_stock_daily_snapshot_column;
