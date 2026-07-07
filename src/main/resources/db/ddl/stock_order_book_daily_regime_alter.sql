USE STOCK_SERVICE;

CREATE TABLE IF NOT EXISTS stock_order_book_daily_regime (
  symbol VARCHAR(20) NOT NULL,
  simulation_trade_date DATE NOT NULL,
  regime_phase VARCHAR(20) NOT NULL,
  price_direction VARCHAR(10) NOT NULL,
  asset_preference VARCHAR(10) NOT NULL,
  direction_intensity INT NOT NULL,
  volatility_level INT NOT NULL,
  liquidity_level INT NOT NULL,
  execution_aggression_level INT NOT NULL DEFAULT 5,
  seed BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (symbol, simulation_trade_date, regime_phase),
  KEY idx_stock_order_book_daily_regime_date (simulation_trade_date, regime_phase, symbol),
  CONSTRAINT chk_stock_order_book_daily_regime_phase CHECK (CASE `regime_phase` WHEN 'OPENING' THEN 1 WHEN 'MIDDAY' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_order_book_daily_regime_price_direction CHECK (CASE `price_direction` WHEN 'UP' THEN 1 WHEN 'DOWN' THEN 1 WHEN 'NEUTRAL' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_order_book_daily_regime_asset_preference CHECK (CASE `asset_preference` WHEN 'STOCK' THEN 1 WHEN 'CASH' THEN 1 WHEN 'BALANCED' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_order_book_daily_regime_intensity CHECK (direction_intensity between 1 and 10),
  CONSTRAINT chk_stock_order_book_daily_regime_volatility CHECK (volatility_level between 1 and 10),
  CONSTRAINT chk_stock_order_book_daily_regime_liquidity CHECK (liquidity_level between 1 and 10),
  CONSTRAINT chk_stock_order_book_daily_regime_execution_aggression CHECK (execution_aggression_level between 1 and 10)
);

SET @add_regime_phase_sql := (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE stock_order_book_daily_regime ADD COLUMN regime_phase VARCHAR(20) NOT NULL DEFAULT ''OPENING'' AFTER simulation_trade_date',
    'SELECT 1'
  )
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'stock_order_book_daily_regime'
    AND column_name = 'regime_phase'
);
PREPARE add_regime_phase_stmt FROM @add_regime_phase_sql;
EXECUTE add_regime_phase_stmt;
DEALLOCATE PREPARE add_regime_phase_stmt;

UPDATE stock_order_book_daily_regime
   SET regime_phase = 'OPENING'
 WHERE regime_phase IS NULL
    OR regime_phase = '';

SET @rebuild_regime_pk_sql := (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE stock_order_book_daily_regime DROP PRIMARY KEY, ADD PRIMARY KEY (symbol, simulation_trade_date, regime_phase)',
    'SELECT 1'
  )
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'stock_order_book_daily_regime'
    AND index_name = 'PRIMARY'
    AND column_name = 'regime_phase'
);
PREPARE rebuild_regime_pk_stmt FROM @rebuild_regime_pk_sql;
EXECUTE rebuild_regime_pk_stmt;
DEALLOCATE PREPARE rebuild_regime_pk_stmt;

SET @drop_regime_date_index_sql := (
  SELECT IF(
    COUNT(*) > 0,
    'ALTER TABLE stock_order_book_daily_regime DROP INDEX idx_stock_order_book_daily_regime_date',
    'SELECT 1'
  )
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'stock_order_book_daily_regime'
    AND index_name = 'idx_stock_order_book_daily_regime_date'
);
PREPARE drop_regime_date_index_stmt FROM @drop_regime_date_index_sql;
EXECUTE drop_regime_date_index_stmt;
DEALLOCATE PREPARE drop_regime_date_index_stmt;

ALTER TABLE stock_order_book_daily_regime
  ADD INDEX idx_stock_order_book_daily_regime_date (simulation_trade_date, regime_phase, symbol);

SET @add_execution_aggression_sql := (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE stock_order_book_daily_regime ADD COLUMN execution_aggression_level INT NOT NULL DEFAULT 5 AFTER liquidity_level',
    'SELECT 1'
  )
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'stock_order_book_daily_regime'
    AND column_name = 'execution_aggression_level'
);
PREPARE add_execution_aggression_stmt FROM @add_execution_aggression_sql;
EXECUTE add_execution_aggression_stmt;
DEALLOCATE PREPARE add_execution_aggression_stmt;

CREATE TABLE IF NOT EXISTS stock_order_book_regime_modifier (
  symbol VARCHAR(20) NOT NULL,
  simulation_trade_date DATE NOT NULL,
  regime_phase VARCHAR(20) NOT NULL,
  modifier_window_start_at DATETIME NOT NULL,
  price_direction_modifier INT NOT NULL,
  asset_preference_modifier INT NOT NULL,
  direction_intensity_modifier INT NOT NULL,
  volatility_modifier INT NOT NULL,
  liquidity_modifier INT NOT NULL,
  execution_aggression_modifier INT NOT NULL,
  seed BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (symbol, simulation_trade_date, regime_phase, modifier_window_start_at),
  KEY idx_stock_order_book_regime_modifier_window (simulation_trade_date, regime_phase, modifier_window_start_at, symbol),
  CONSTRAINT chk_stock_order_book_regime_modifier_phase CHECK (CASE `regime_phase` WHEN 'OPENING' THEN 1 WHEN 'MIDDAY' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_order_book_regime_modifier_price_direction CHECK (price_direction_modifier between -10 and 10),
  CONSTRAINT chk_stock_order_book_regime_modifier_asset_preference CHECK (asset_preference_modifier between -10 and 10),
  CONSTRAINT chk_stock_order_book_regime_modifier_intensity CHECK (direction_intensity_modifier between -10 and 10),
  CONSTRAINT chk_stock_order_book_regime_modifier_volatility CHECK (volatility_modifier between -10 and 10),
  CONSTRAINT chk_stock_order_book_regime_modifier_liquidity CHECK (liquidity_modifier between -10 and 10),
  CONSTRAINT chk_stock_order_book_regime_modifier_execution_aggression CHECK (execution_aggression_modifier between -10 and 10)
);

ALTER TABLE stock_order_book_regime_modifier DROP CHECK chk_stock_order_book_regime_modifier_price_direction;
ALTER TABLE stock_order_book_regime_modifier ADD CONSTRAINT chk_stock_order_book_regime_modifier_price_direction CHECK (price_direction_modifier between -10 and 10);

ALTER TABLE stock_order_book_regime_modifier DROP CHECK chk_stock_order_book_regime_modifier_asset_preference;
ALTER TABLE stock_order_book_regime_modifier ADD CONSTRAINT chk_stock_order_book_regime_modifier_asset_preference CHECK (asset_preference_modifier between -10 and 10);

ALTER TABLE stock_order_book_regime_modifier DROP CHECK chk_stock_order_book_regime_modifier_intensity;
ALTER TABLE stock_order_book_regime_modifier ADD CONSTRAINT chk_stock_order_book_regime_modifier_intensity CHECK (direction_intensity_modifier between -10 and 10);

ALTER TABLE stock_order_book_regime_modifier DROP CHECK chk_stock_order_book_regime_modifier_volatility;
ALTER TABLE stock_order_book_regime_modifier ADD CONSTRAINT chk_stock_order_book_regime_modifier_volatility CHECK (volatility_modifier between -10 and 10);

ALTER TABLE stock_order_book_regime_modifier DROP CHECK chk_stock_order_book_regime_modifier_liquidity;
ALTER TABLE stock_order_book_regime_modifier ADD CONSTRAINT chk_stock_order_book_regime_modifier_liquidity CHECK (liquidity_modifier between -10 and 10);

ALTER TABLE stock_order_book_regime_modifier DROP CHECK chk_stock_order_book_regime_modifier_execution_aggression;
ALTER TABLE stock_order_book_regime_modifier ADD CONSTRAINT chk_stock_order_book_regime_modifier_execution_aggression CHECK (execution_aggression_modifier between -10 and 10);
