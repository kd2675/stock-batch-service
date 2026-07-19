USE STOCK_SERVICE;

-- Use a MySQL 8 metadata guard so a maintenance-window retry does not fail after
-- a prior successful column addition.
SET @stock_execution_daily_account_last_executed_at_sql = (
  SELECT CASE
           WHEN COUNT(*) = 0 THEN
             'ALTER TABLE stock_execution_daily_account_snapshot ADD COLUMN last_executed_at DATETIME NULL AFTER execution_amount'
           ELSE 'SELECT 1'
         END
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_execution_daily_account_snapshot'
     AND column_name = 'last_executed_at'
);
PREPARE stock_execution_daily_account_last_executed_at_statement
  FROM @stock_execution_daily_account_last_executed_at_sql;
EXECUTE stock_execution_daily_account_last_executed_at_statement;
DEALLOCATE PREPARE stock_execution_daily_account_last_executed_at_statement;
