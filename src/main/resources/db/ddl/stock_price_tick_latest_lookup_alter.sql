USE STOCK_SERVICE;

SET @stock_price_tick_latest_lookup_index_exists := (
  SELECT COUNT(*)
    FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_price_tick'
     AND index_name = 'idx_stock_price_tick_symbol_time_id'
);

SET @stock_price_tick_latest_lookup_index_add_sql := IF(
  @stock_price_tick_latest_lookup_index_exists = 0,
  'ALTER TABLE stock_price_tick ADD INDEX idx_stock_price_tick_symbol_time_id (symbol, price_time, id)',
  'SELECT 1'
);

PREPARE stock_price_tick_latest_lookup_index_add_stmt
  FROM @stock_price_tick_latest_lookup_index_add_sql;
EXECUTE stock_price_tick_latest_lookup_index_add_stmt;
DEALLOCATE PREPARE stock_price_tick_latest_lookup_index_add_stmt;

SET @stock_price_tick_legacy_lookup_index_exists := (
  SELECT COUNT(*)
    FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_price_tick'
     AND index_name = 'idx_stock_price_tick_symbol_time'
);

SET @stock_price_tick_legacy_lookup_index_drop_sql := IF(
  @stock_price_tick_legacy_lookup_index_exists > 0,
  'ALTER TABLE stock_price_tick DROP INDEX idx_stock_price_tick_symbol_time',
  'SELECT 1'
);

PREPARE stock_price_tick_legacy_lookup_index_drop_stmt
  FROM @stock_price_tick_legacy_lookup_index_drop_sql;
EXECUTE stock_price_tick_legacy_lookup_index_drop_stmt;
DEALLOCATE PREPARE stock_price_tick_legacy_lookup_index_drop_stmt;
