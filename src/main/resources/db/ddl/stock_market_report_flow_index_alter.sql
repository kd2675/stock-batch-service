USE STOCK_SERVICE;

SET @stock_execution_market_report_flow_exists = (
  SELECT COUNT(*)
    FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_execution'
     AND index_name = 'idx_stock_execution_market_report_flow'
);
SET @stock_execution_market_report_flow_sql = IF(
  @stock_execution_market_report_flow_exists = 0,
  'ALTER TABLE stock_execution ADD INDEX idx_stock_execution_market_report_flow (source, symbol, executed_at, account_id, side, quantity, gross_amount, net_amount), ALGORITHM=INPLACE, LOCK=NONE',
  'SELECT 1'
);
PREPARE stock_execution_market_report_flow_stmt FROM @stock_execution_market_report_flow_sql;
EXECUTE stock_execution_market_report_flow_stmt;
DEALLOCATE PREPARE stock_execution_market_report_flow_stmt;
