CREATE DATABASE IF NOT EXISTS STOCK_SERVICE
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE STOCK_SERVICE;

CREATE TABLE IF NOT EXISTS stock_account (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_key VARCHAR(64) NOT NULL,
  cash_balance DECIMAL(19,2) NOT NULL,
  initial_cash DECIMAL(19,2) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_account_user_key (user_key),
  CONSTRAINT chk_stock_account_cash_non_negative CHECK (cash_balance >= 0),
  CONSTRAINT chk_stock_account_initial_cash_positive CHECK (initial_cash > 0)
);

CREATE TABLE IF NOT EXISTS stock_instrument (
  symbol VARCHAR(20) NOT NULL,
  name VARCHAR(120) NOT NULL,
  market VARCHAR(20) NOT NULL,
  enabled BIT NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (symbol)
);

CREATE TABLE IF NOT EXISTS stock_price (
  symbol VARCHAR(20) NOT NULL,
  current_price DECIMAL(19,2) NOT NULL,
  previous_close DECIMAL(19,2) NOT NULL,
  price_time DATETIME NOT NULL,
  provider VARCHAR(40) NOT NULL,
  PRIMARY KEY (symbol),
  CONSTRAINT chk_stock_price_current_positive CHECK (current_price > 0),
  CONSTRAINT chk_stock_price_previous_close_positive CHECK (previous_close > 0)
);

CREATE TABLE IF NOT EXISTS stock_price_tick (
  id BIGINT NOT NULL AUTO_INCREMENT,
  symbol VARCHAR(20) NOT NULL,
  price DECIMAL(19,2) NOT NULL,
  provider VARCHAR(40) NOT NULL,
  price_time DATETIME NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_stock_price_tick_symbol_time (symbol, price_time),
  CONSTRAINT chk_stock_price_tick_price_positive CHECK (price > 0)
);

CREATE TABLE IF NOT EXISTS stock_order (
  id BIGINT NOT NULL AUTO_INCREMENT,
  client_order_id VARCHAR(64) NOT NULL,
  user_key VARCHAR(64) NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  side VARCHAR(10) NOT NULL,
  order_type VARCHAR(10) NOT NULL,
  status VARCHAR(20) NOT NULL,
  limit_price DECIMAL(19,2) NULL,
  quantity BIGINT NOT NULL,
  filled_quantity BIGINT NOT NULL,
  average_fill_price DECIMAL(19,2) NULL,
  reserved_cash DECIMAL(19,2) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_order_client_order_id (client_order_id),
  KEY idx_stock_order_user_created (user_key, created_at),
  KEY idx_stock_order_user_status_created (user_key, status, created_at),
  KEY idx_stock_order_status_symbol (status, symbol),
  KEY idx_stock_order_execution_scan (status, order_type, created_at, symbol),
  KEY idx_stock_order_order_book_match (symbol, side, order_type, status, limit_price, created_at),
  CONSTRAINT chk_stock_order_side_valid CHECK (CASE `side` WHEN 'BUY' THEN 1 WHEN 'SELL' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_order_type_valid CHECK (CASE `order_type` WHEN 'LIMIT' THEN 1 WHEN 'MARKET' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_order_status_valid CHECK (CASE `status` WHEN 'PENDING' THEN 1 WHEN 'PARTIALLY_FILLED' THEN 1 WHEN 'FILLED' THEN 1 WHEN 'CANCELLED' THEN 1 WHEN 'REJECTED' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_order_limit_price_positive CHECK (limit_price IS NULL OR limit_price > 0),
  CONSTRAINT chk_stock_order_quantity_positive CHECK (quantity > 0),
  CONSTRAINT chk_stock_order_filled_quantity_valid CHECK (filled_quantity >= 0 AND filled_quantity <= quantity),
  CONSTRAINT chk_stock_order_average_fill_price_positive CHECK (average_fill_price IS NULL OR average_fill_price > 0),
  CONSTRAINT chk_stock_order_reserved_cash_non_negative CHECK (reserved_cash >= 0),
  CONSTRAINT chk_stock_order_terminal_reserved_cash_zero CHECK ((status <> 'FILLED' AND status <> 'CANCELLED' AND status <> 'REJECTED') OR reserved_cash = 0)
);

CREATE TABLE IF NOT EXISTS stock_execution (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  user_key VARCHAR(64) NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  side VARCHAR(10) NOT NULL,
  quantity BIGINT NOT NULL,
  price DECIMAL(19,2) NOT NULL,
  source VARCHAR(30) NOT NULL,
  executed_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_stock_execution_user_time (user_key, executed_at),
  KEY idx_stock_execution_order (order_id),
  CONSTRAINT chk_stock_execution_side_valid CHECK (CASE `side` WHEN 'BUY' THEN 1 WHEN 'SELL' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_execution_source_valid CHECK (CASE `source` WHEN 'VIRTUAL_MARKET_PRICE' THEN 1 WHEN 'INTERNAL_ORDER_BOOK' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_execution_quantity_positive CHECK (quantity > 0),
  CONSTRAINT chk_stock_execution_price_positive CHECK (price > 0)
);

CREATE TABLE IF NOT EXISTS stock_holding (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_key VARCHAR(64) NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  quantity BIGINT NOT NULL,
  reserved_quantity BIGINT NOT NULL DEFAULT 0,
  average_price DECIMAL(19,2) NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_holding_user_symbol (user_key, symbol),
  CONSTRAINT chk_stock_holding_quantity_non_negative CHECK (quantity >= 0),
  CONSTRAINT chk_stock_holding_reserved_quantity_valid CHECK (reserved_quantity >= 0 AND reserved_quantity <= quantity),
  CONSTRAINT chk_stock_holding_average_price_positive CHECK (average_price > 0)
);

CREATE TABLE IF NOT EXISTS portfolio_snapshot (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_key VARCHAR(64) NOT NULL,
  snapshot_date DATE NOT NULL,
  total_asset DECIMAL(19,2) NOT NULL,
  cash_balance DECIMAL(19,2) NOT NULL,
  market_value DECIMAL(19,2) NOT NULL,
  return_rate DECIMAL(9,4) NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_portfolio_snapshot_user_date (user_key, snapshot_date),
  KEY idx_portfolio_snapshot_date_return (snapshot_date, return_rate),
  CONSTRAINT chk_portfolio_snapshot_total_asset_non_negative CHECK (total_asset >= 0),
  CONSTRAINT chk_portfolio_snapshot_cash_balance_non_negative CHECK (cash_balance >= 0),
  CONSTRAINT chk_portfolio_snapshot_market_value_non_negative CHECK (market_value >= 0)
);

INSERT INTO stock_instrument(symbol, name, market, enabled, created_at)
VALUES
  ('005930', '삼성전자', 'KOSPI', TRUE, CURRENT_TIMESTAMP),
  ('000660', 'SK하이닉스', 'KOSPI', TRUE, CURRENT_TIMESTAMP),
  ('035420', 'NAVER', 'KOSPI', TRUE, CURRENT_TIMESTAMP),
  ('035720', '카카오', 'KOSPI', TRUE, CURRENT_TIMESTAMP),
  ('051910', 'LG화학', 'KOSPI', TRUE, CURRENT_TIMESTAMP)
ON DUPLICATE KEY UPDATE symbol = symbol;

INSERT INTO stock_price(symbol, current_price, previous_close, price_time, provider)
VALUES
  ('005930', 72400.00, 72400.00, CURRENT_TIMESTAMP, 'seed'),
  ('000660', 241500.00, 241500.00, CURRENT_TIMESTAMP, 'seed'),
  ('035420', 193800.00, 193800.00, CURRENT_TIMESTAMP, 'seed'),
  ('035720', 52100.00, 52100.00, CURRENT_TIMESTAMP, 'seed'),
  ('051910', 312000.00, 312000.00, CURRENT_TIMESTAMP, 'seed')
ON DUPLICATE KEY UPDATE symbol = symbol;
