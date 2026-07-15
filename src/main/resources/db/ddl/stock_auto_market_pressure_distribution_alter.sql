USE STOCK_SERVICE;

SET @stock_auto_market_pressure_legacy_column_count := (
  SELECT COUNT(*)
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND (
       (table_name = 'stock_auto_market_config' AND column_name = 'intensity')
       OR (table_name = 'stock_order_book_daily_regime' AND column_name IN (
         'price_direction', 'asset_preference', 'direction_intensity',
         'volatility_level', 'liquidity_level', 'execution_aggression_level'
       ))
       OR (table_name = 'stock_order_book_regime_modifier' AND column_name IN (
         'price_direction_modifier', 'asset_preference_modifier', 'direction_intensity_modifier',
         'volatility_modifier', 'liquidity_modifier', 'execution_aggression_modifier'
       ))
     )
);

SET @stock_auto_market_pressure_new_column_count := (
  SELECT COUNT(*)
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND (
       (table_name = 'stock_auto_market_config' AND column_name IN (
         'primary_price_pressure_bias', 'primary_asset_preference_pressure_bias',
         'primary_volatility_pressure_bias', 'primary_liquidity_pressure_bias',
         'primary_execution_aggression_pressure_bias', 'secondary_price_pressure_bias',
         'secondary_asset_preference_pressure_bias', 'secondary_volatility_pressure_bias',
         'secondary_liquidity_pressure_bias', 'secondary_execution_aggression_pressure_bias'
       ))
       OR (table_name IN ('stock_order_book_daily_regime', 'stock_order_book_regime_modifier') AND column_name IN (
         'price_pressure', 'asset_preference_pressure', 'volatility_pressure',
         'liquidity_pressure', 'execution_aggression_pressure'
       ))
     )
);

SET @stock_auto_market_pressure_legacy_check_count := (
  SELECT COUNT(*)
    FROM information_schema.check_constraints
   WHERE constraint_schema = DATABASE()
     AND constraint_name IN (
       'chk_stock_auto_market_intensity',
       'chk_stock_order_book_daily_regime_phase',
       'chk_stock_order_book_daily_regime_price_direction',
       'chk_stock_order_book_daily_regime_asset_preference',
       'chk_stock_order_book_daily_regime_intensity',
       'chk_stock_order_book_daily_regime_volatility',
       'chk_stock_order_book_daily_regime_liquidity',
       'chk_stock_order_book_daily_regime_execution_aggression',
       'chk_stock_order_book_regime_modifier_phase',
       'chk_stock_order_book_regime_modifier_price_direction',
       'chk_stock_order_book_regime_modifier_asset_preference',
       'chk_stock_order_book_regime_modifier_intensity',
       'chk_stock_order_book_regime_modifier_volatility',
       'chk_stock_order_book_regime_modifier_liquidity',
       'chk_stock_order_book_regime_modifier_execution_aggression'
     )
);

-- All three legacy tables must be ready before the first auto-committing ALTER runs.
SET @stock_auto_market_pressure_schema_guard_sql := IF(
  @stock_auto_market_pressure_legacy_column_count = 13
  AND @stock_auto_market_pressure_new_column_count = 0
  AND @stock_auto_market_pressure_legacy_check_count = 15,
  'SELECT 1',
  'SELECT 1 FROM stock_migration_required_auto_market_pressure_distribution_schema'
);

PREPARE stock_auto_market_pressure_schema_guard_stmt FROM @stock_auto_market_pressure_schema_guard_sql;
EXECUTE stock_auto_market_pressure_schema_guard_stmt;
DEALLOCATE PREPARE stock_auto_market_pressure_schema_guard_stmt;

ALTER TABLE stock_auto_market_config
  DROP CHECK chk_stock_auto_market_intensity,
  DROP COLUMN intensity,
  ADD COLUMN primary_price_pressure_bias INT NOT NULL DEFAULT 0 AFTER enabled,
  ADD COLUMN primary_asset_preference_pressure_bias INT NOT NULL DEFAULT 0 AFTER primary_price_pressure_bias,
  ADD COLUMN primary_volatility_pressure_bias INT NOT NULL DEFAULT 0 AFTER primary_asset_preference_pressure_bias,
  ADD COLUMN primary_liquidity_pressure_bias INT NOT NULL DEFAULT 0 AFTER primary_volatility_pressure_bias,
  ADD COLUMN primary_execution_aggression_pressure_bias INT NOT NULL DEFAULT 0 AFTER primary_liquidity_pressure_bias,
  ADD COLUMN secondary_price_pressure_bias INT NOT NULL DEFAULT 0 AFTER primary_execution_aggression_pressure_bias,
  ADD COLUMN secondary_asset_preference_pressure_bias INT NOT NULL DEFAULT 0 AFTER secondary_price_pressure_bias,
  ADD COLUMN secondary_volatility_pressure_bias INT NOT NULL DEFAULT 0 AFTER secondary_asset_preference_pressure_bias,
  ADD COLUMN secondary_liquidity_pressure_bias INT NOT NULL DEFAULT 0 AFTER secondary_volatility_pressure_bias,
  ADD COLUMN secondary_execution_aggression_pressure_bias INT NOT NULL DEFAULT 0 AFTER secondary_liquidity_pressure_bias,
  ADD CONSTRAINT chk_stock_auto_market_primary_price_bias CHECK (primary_price_pressure_bias BETWEEN -100 AND 100),
  ADD CONSTRAINT chk_stock_auto_market_primary_asset_bias CHECK (primary_asset_preference_pressure_bias BETWEEN -100 AND 100),
  ADD CONSTRAINT chk_stock_auto_market_primary_volatility_bias CHECK (primary_volatility_pressure_bias BETWEEN -100 AND 100),
  ADD CONSTRAINT chk_stock_auto_market_primary_liquidity_bias CHECK (primary_liquidity_pressure_bias BETWEEN -100 AND 100),
  ADD CONSTRAINT chk_stock_auto_market_primary_aggression_bias CHECK (primary_execution_aggression_pressure_bias BETWEEN -100 AND 100),
  ADD CONSTRAINT chk_stock_auto_market_secondary_price_bias CHECK (secondary_price_pressure_bias BETWEEN -100 AND 100),
  ADD CONSTRAINT chk_stock_auto_market_secondary_asset_bias CHECK (secondary_asset_preference_pressure_bias BETWEEN -100 AND 100),
  ADD CONSTRAINT chk_stock_auto_market_secondary_volatility_bias CHECK (secondary_volatility_pressure_bias BETWEEN -100 AND 100),
  ADD CONSTRAINT chk_stock_auto_market_secondary_liquidity_bias CHECK (secondary_liquidity_pressure_bias BETWEEN -100 AND 100),
  ADD CONSTRAINT chk_stock_auto_market_secondary_aggression_bias CHECK (secondary_execution_aggression_pressure_bias BETWEEN -100 AND 100);

ALTER TABLE stock_order_book_daily_regime
  DROP CHECK chk_stock_order_book_daily_regime_phase,
  DROP CHECK chk_stock_order_book_daily_regime_price_direction,
  DROP CHECK chk_stock_order_book_daily_regime_asset_preference,
  DROP CHECK chk_stock_order_book_daily_regime_intensity,
  DROP CHECK chk_stock_order_book_daily_regime_volatility,
  DROP CHECK chk_stock_order_book_daily_regime_liquidity,
  DROP CHECK chk_stock_order_book_daily_regime_execution_aggression,
  ADD COLUMN price_pressure INT NULL AFTER regime_phase,
  ADD COLUMN asset_preference_pressure INT NULL AFTER price_pressure,
  ADD COLUMN volatility_pressure INT NULL AFTER asset_preference_pressure,
  ADD COLUMN liquidity_pressure INT NULL AFTER volatility_pressure,
  ADD COLUMN execution_aggression_pressure INT NULL AFTER liquidity_pressure;

UPDATE stock_order_book_daily_regime
SET price_pressure = CASE price_direction
      WHEN 'UP' THEN direction_intensity * 10
      WHEN 'DOWN' THEN direction_intensity * -10
      ELSE 0
    END,
    asset_preference_pressure = CASE asset_preference
      WHEN 'STOCK' THEN direction_intensity * 10
      WHEN 'CASH' THEN direction_intensity * -10
      ELSE 0
    END,
    volatility_pressure = ROUND((volatility_level - 5.5) * 100 / 4.5),
    liquidity_pressure = ROUND((liquidity_level - 5.5) * 100 / 4.5),
    execution_aggression_pressure = ROUND((execution_aggression_level - 5.5) * 100 / 4.5),
    regime_phase = CASE regime_phase
      WHEN 'OPENING' THEN 'SLOT_0600'
      WHEN 'MIDDAY' THEN 'SLOT_1200'
      ELSE regime_phase
    END;

ALTER TABLE stock_order_book_daily_regime
  MODIFY COLUMN price_pressure INT NOT NULL,
  MODIFY COLUMN asset_preference_pressure INT NOT NULL,
  MODIFY COLUMN volatility_pressure INT NOT NULL,
  MODIFY COLUMN liquidity_pressure INT NOT NULL,
  MODIFY COLUMN execution_aggression_pressure INT NOT NULL,
  DROP COLUMN price_direction,
  DROP COLUMN asset_preference,
  DROP COLUMN direction_intensity,
  DROP COLUMN volatility_level,
  DROP COLUMN liquidity_level,
  DROP COLUMN execution_aggression_level,
  ADD CONSTRAINT chk_stock_order_book_daily_regime_phase CHECK (
    CASE regime_phase
      WHEN 'SLOT_0600' THEN 1
      WHEN 'SLOT_0900' THEN 1
      WHEN 'SLOT_1200' THEN 1
      WHEN 'SLOT_1500' THEN 1
      ELSE 0
    END = 1
  ),
  ADD CONSTRAINT chk_stock_order_book_daily_regime_price CHECK (price_pressure BETWEEN -100 AND 100),
  ADD CONSTRAINT chk_stock_order_book_daily_regime_asset CHECK (asset_preference_pressure BETWEEN -100 AND 100),
  ADD CONSTRAINT chk_stock_order_book_daily_regime_volatility CHECK (volatility_pressure BETWEEN -100 AND 100),
  ADD CONSTRAINT chk_stock_order_book_daily_regime_liquidity CHECK (liquidity_pressure BETWEEN -100 AND 100),
  ADD CONSTRAINT chk_stock_order_book_daily_regime_aggression CHECK (execution_aggression_pressure BETWEEN -100 AND 100);

ALTER TABLE stock_order_book_regime_modifier
  DROP CHECK chk_stock_order_book_regime_modifier_phase,
  DROP CHECK chk_stock_order_book_regime_modifier_price_direction,
  DROP CHECK chk_stock_order_book_regime_modifier_asset_preference,
  DROP CHECK chk_stock_order_book_regime_modifier_intensity,
  DROP CHECK chk_stock_order_book_regime_modifier_volatility,
  DROP CHECK chk_stock_order_book_regime_modifier_liquidity,
  DROP CHECK chk_stock_order_book_regime_modifier_execution_aggression,
  ADD COLUMN price_pressure INT NULL AFTER modifier_window_start_at,
  ADD COLUMN asset_preference_pressure INT NULL AFTER price_pressure,
  ADD COLUMN volatility_pressure INT NULL AFTER asset_preference_pressure,
  ADD COLUMN liquidity_pressure INT NULL AFTER volatility_pressure,
  ADD COLUMN execution_aggression_pressure INT NULL AFTER liquidity_pressure;

UPDATE stock_order_book_regime_modifier
SET price_pressure = price_direction_modifier * 10,
    asset_preference_pressure = asset_preference_modifier * 10,
    volatility_pressure = CASE
      WHEN volatility_modifier BETWEEN 1 AND 10 THEN ROUND((volatility_modifier - 5.5) * 100 / 4.5)
      ELSE 0
    END,
    liquidity_pressure = CASE
      WHEN liquidity_modifier BETWEEN 1 AND 10 THEN ROUND((liquidity_modifier - 5.5) * 100 / 4.5)
      ELSE 0
    END,
    execution_aggression_pressure = CASE
      WHEN execution_aggression_modifier BETWEEN 1 AND 10 THEN ROUND((execution_aggression_modifier - 5.5) * 100 / 4.5)
      ELSE 0
    END,
    regime_phase = CASE regime_phase
      WHEN 'OPENING' THEN 'SLOT_0600'
      WHEN 'MIDDAY' THEN 'SLOT_1200'
      ELSE regime_phase
    END;

ALTER TABLE stock_order_book_regime_modifier
  MODIFY COLUMN price_pressure INT NOT NULL,
  MODIFY COLUMN asset_preference_pressure INT NOT NULL,
  MODIFY COLUMN volatility_pressure INT NOT NULL,
  MODIFY COLUMN liquidity_pressure INT NOT NULL,
  MODIFY COLUMN execution_aggression_pressure INT NOT NULL,
  DROP COLUMN price_direction_modifier,
  DROP COLUMN asset_preference_modifier,
  DROP COLUMN direction_intensity_modifier,
  DROP COLUMN volatility_modifier,
  DROP COLUMN liquidity_modifier,
  DROP COLUMN execution_aggression_modifier,
  ADD CONSTRAINT chk_stock_order_book_regime_modifier_phase CHECK (
    CASE regime_phase
      WHEN 'SLOT_0600' THEN 1
      WHEN 'SLOT_0900' THEN 1
      WHEN 'SLOT_1200' THEN 1
      WHEN 'SLOT_1500' THEN 1
      ELSE 0
    END = 1
  ),
  ADD CONSTRAINT chk_stock_order_book_regime_modifier_price CHECK (price_pressure BETWEEN -100 AND 100),
  ADD CONSTRAINT chk_stock_order_book_regime_modifier_asset CHECK (asset_preference_pressure BETWEEN -100 AND 100),
  ADD CONSTRAINT chk_stock_order_book_regime_modifier_volatility CHECK (volatility_pressure BETWEEN -100 AND 100),
  ADD CONSTRAINT chk_stock_order_book_regime_modifier_liquidity CHECK (liquidity_pressure BETWEEN -100 AND 100),
  ADD CONSTRAINT chk_stock_order_book_regime_modifier_aggression CHECK (execution_aggression_pressure BETWEEN -100 AND 100);
