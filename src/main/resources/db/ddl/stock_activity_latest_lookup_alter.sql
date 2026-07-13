USE STOCK_SERVICE;

SET @stock_order_account_market_created_exists = (
  SELECT COUNT(*)
    FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_order'
     AND index_name = 'idx_stock_order_account_market_created'
);
SET @stock_order_account_market_created_sql = IF(
  @stock_order_account_market_created_exists = 0,
  'ALTER TABLE stock_order ADD INDEX idx_stock_order_account_market_created (account_id, market_type, created_at)',
  'SELECT 1'
);
PREPARE stock_order_account_market_created_stmt FROM @stock_order_account_market_created_sql;
EXECUTE stock_order_account_market_created_stmt;
DEALLOCATE PREPARE stock_order_account_market_created_stmt;

SET @stock_execution_account_source_time_exists = (
  SELECT COUNT(*)
    FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_execution'
     AND index_name = 'idx_stock_execution_account_source_time'
);
SET @stock_execution_account_source_time_sql = IF(
  @stock_execution_account_source_time_exists = 0,
  'ALTER TABLE stock_execution ADD INDEX idx_stock_execution_account_source_time (account_id, source, executed_at)',
  'SELECT 1'
);
PREPARE stock_execution_account_source_time_stmt FROM @stock_execution_account_source_time_sql;
EXECUTE stock_execution_account_source_time_stmt;
DEALLOCATE PREPARE stock_execution_account_source_time_stmt;

SET @stock_execution_candle_columns = (
  SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index)
    FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_execution'
     AND index_name = 'idx_stock_execution_candle'
);
SET @stock_execution_candle_sql = IF(
  @stock_execution_candle_columns = 'source,symbol,side,executed_at,id,price,quantity,gross_amount',
  'SELECT 1',
  IF(
    @stock_execution_candle_columns IS NULL,
    'ALTER TABLE stock_execution ADD INDEX idx_stock_execution_candle (source, symbol, side, executed_at, id, price, quantity, gross_amount)',
    'ALTER TABLE stock_execution DROP INDEX idx_stock_execution_candle, ADD INDEX idx_stock_execution_candle (source, symbol, side, executed_at, id, price, quantity, gross_amount)'
  )
);
PREPARE stock_execution_candle_stmt FROM @stock_execution_candle_sql;
EXECUTE stock_execution_candle_stmt;
DEALLOCATE PREPARE stock_execution_candle_stmt;
