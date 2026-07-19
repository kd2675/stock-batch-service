USE STOCK_SERVICE;

-- Apply only after stock-back-service and stock-batch-service are both stopped.
-- The structural ALTERs touch only the compact account/day read model. The one-time
-- backfill scans stock_execution, so it is deliberately guarded and runs only when at
-- least one financial summary column was missing or an interrupted default-zero rebuild is
-- detected in the compact summary table.
SET @stock_execution_profit_summary_columns_were_missing = (
  SELECT CASE WHEN COUNT(*) = 7 THEN 0 ELSE 1 END
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_execution_account_day_summary'
     AND column_name IN (
       'buy_gross_amount',
       'sell_gross_amount',
       'buy_net_amount',
       'sell_net_amount',
       'fee_amount',
       'tax_amount',
       'realized_profit'
     )
);

SET @stock_execution_profit_summary_sql = (
  SELECT CASE
           WHEN COUNT(*) = 0 THEN
             'ALTER TABLE stock_execution_account_day_summary ADD COLUMN buy_gross_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00'
           ELSE 'SELECT 1'
         END
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_execution_account_day_summary'
     AND column_name = 'buy_gross_amount'
);
PREPARE stock_execution_profit_summary_statement FROM @stock_execution_profit_summary_sql;
EXECUTE stock_execution_profit_summary_statement;
DEALLOCATE PREPARE stock_execution_profit_summary_statement;

SET @stock_execution_profit_summary_sql = (
  SELECT CASE
           WHEN COUNT(*) = 0 THEN
             'ALTER TABLE stock_execution_account_day_summary ADD COLUMN sell_gross_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00'
           ELSE 'SELECT 1'
         END
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_execution_account_day_summary'
     AND column_name = 'sell_gross_amount'
);
PREPARE stock_execution_profit_summary_statement FROM @stock_execution_profit_summary_sql;
EXECUTE stock_execution_profit_summary_statement;
DEALLOCATE PREPARE stock_execution_profit_summary_statement;

SET @stock_execution_profit_summary_sql = (
  SELECT CASE
           WHEN COUNT(*) = 0 THEN
             'ALTER TABLE stock_execution_account_day_summary ADD COLUMN buy_net_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00'
           ELSE 'SELECT 1'
         END
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_execution_account_day_summary'
     AND column_name = 'buy_net_amount'
);
PREPARE stock_execution_profit_summary_statement FROM @stock_execution_profit_summary_sql;
EXECUTE stock_execution_profit_summary_statement;
DEALLOCATE PREPARE stock_execution_profit_summary_statement;

SET @stock_execution_profit_summary_sql = (
  SELECT CASE
           WHEN COUNT(*) = 0 THEN
             'ALTER TABLE stock_execution_account_day_summary ADD COLUMN sell_net_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00'
           ELSE 'SELECT 1'
         END
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_execution_account_day_summary'
     AND column_name = 'sell_net_amount'
);
PREPARE stock_execution_profit_summary_statement FROM @stock_execution_profit_summary_sql;
EXECUTE stock_execution_profit_summary_statement;
DEALLOCATE PREPARE stock_execution_profit_summary_statement;

SET @stock_execution_profit_summary_sql = (
  SELECT CASE
           WHEN COUNT(*) = 0 THEN
             'ALTER TABLE stock_execution_account_day_summary ADD COLUMN fee_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00'
           ELSE 'SELECT 1'
         END
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_execution_account_day_summary'
     AND column_name = 'fee_amount'
);
PREPARE stock_execution_profit_summary_statement FROM @stock_execution_profit_summary_sql;
EXECUTE stock_execution_profit_summary_statement;
DEALLOCATE PREPARE stock_execution_profit_summary_statement;

SET @stock_execution_profit_summary_sql = (
  SELECT CASE
           WHEN COUNT(*) = 0 THEN
             'ALTER TABLE stock_execution_account_day_summary ADD COLUMN tax_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00'
           ELSE 'SELECT 1'
         END
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_execution_account_day_summary'
     AND column_name = 'tax_amount'
);
PREPARE stock_execution_profit_summary_statement FROM @stock_execution_profit_summary_sql;
EXECUTE stock_execution_profit_summary_statement;
DEALLOCATE PREPARE stock_execution_profit_summary_statement;

SET @stock_execution_profit_summary_sql = (
  SELECT CASE
           WHEN COUNT(*) = 0 THEN
             'ALTER TABLE stock_execution_account_day_summary ADD COLUMN realized_profit DECIMAL(19,2) NOT NULL DEFAULT 0.00'
           ELSE 'SELECT 1'
         END
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_execution_account_day_summary'
     AND column_name = 'realized_profit'
);
PREPARE stock_execution_profit_summary_statement FROM @stock_execution_profit_summary_sql;
EXECUTE stock_execution_profit_summary_statement;
DEALLOCATE PREPARE stock_execution_profit_summary_statement;

SET @stock_execution_profit_summary_align_check = (
  SELECT CASE
           WHEN COUNT(*) = 1
            AND MAX(checks.check_clause LIKE '%buy_gross_amount%') = 1 THEN 0
           ELSE 1
         END
    FROM information_schema.table_constraints constraints_meta
    LEFT JOIN information_schema.check_constraints checks
      ON checks.constraint_schema = constraints_meta.constraint_schema
     AND checks.constraint_name = constraints_meta.constraint_name
   WHERE constraints_meta.table_schema = DATABASE()
     AND constraints_meta.table_name = 'stock_execution_account_day_summary'
     AND constraints_meta.constraint_name = 'chk_stock_execution_account_day_amount'
);

SET @stock_execution_profit_summary_sql = (
  SELECT CASE
           WHEN @stock_execution_profit_summary_align_check = 1 AND COUNT(*) = 1 THEN
             'ALTER TABLE stock_execution_account_day_summary DROP CHECK chk_stock_execution_account_day_amount'
           ELSE 'SELECT 1'
         END
    FROM information_schema.table_constraints
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_execution_account_day_summary'
     AND constraint_name = 'chk_stock_execution_account_day_amount'
);
PREPARE stock_execution_profit_summary_statement FROM @stock_execution_profit_summary_sql;
EXECUTE stock_execution_profit_summary_statement;
DEALLOCATE PREPARE stock_execution_profit_summary_statement;

SET @stock_execution_profit_summary_sql = IF(
  @stock_execution_profit_summary_align_check = 1,
  'ALTER TABLE stock_execution_account_day_summary ADD CONSTRAINT chk_stock_execution_account_day_amount CHECK (gross_amount >= 0 AND buy_gross_amount >= 0 AND sell_gross_amount >= 0 AND buy_net_amount >= 0 AND sell_net_amount >= 0 AND fee_amount >= 0 AND tax_amount >= 0)',
  'SELECT 1'
);
PREPARE stock_execution_profit_summary_statement FROM @stock_execution_profit_summary_sql;
EXECUTE stock_execution_profit_summary_statement;
DEALLOCATE PREPARE stock_execution_profit_summary_statement;

-- A retry after the columns were added but before the one-statement backfill committed must
-- not silently skip the historical rebuild. The compact summary invariant is cheap to inspect
-- and distinguishes the default-zero interrupted shape without rescanning stock_execution.
SET @stock_execution_profit_summary_requires_backfill = IF(
  @stock_execution_profit_summary_columns_were_missing = 1
  OR EXISTS (
    SELECT 1
      FROM stock_execution_account_day_summary
     WHERE gross_amount <> buy_gross_amount + sell_gross_amount
     LIMIT 1
  ),
  1,
  0
);

SET @stock_execution_profit_summary_sql = IF(
  @stock_execution_profit_summary_requires_backfill = 1,
  'REPLACE INTO stock_execution_account_day_summary(
     simulation_trade_date, account_id, execution_count, buy_quantity, sell_quantity,
     gross_amount, buy_gross_amount, sell_gross_amount, buy_net_amount, sell_net_amount,
     fee_amount, tax_amount, realized_profit, last_executed_at, updated_at
   )
   SELECT DATE(executed_at),
          account_id,
          COUNT(*),
          SUM(CASE WHEN side = ''BUY'' THEN quantity ELSE 0 END),
          SUM(CASE WHEN side = ''SELL'' THEN quantity ELSE 0 END),
          SUM(gross_amount),
          SUM(CASE WHEN side = ''BUY'' THEN gross_amount ELSE 0 END),
          SUM(CASE WHEN side = ''SELL'' THEN gross_amount ELSE 0 END),
          SUM(CASE WHEN side = ''BUY'' THEN net_amount ELSE 0 END),
          SUM(CASE WHEN side = ''SELL'' THEN net_amount ELSE 0 END),
          SUM(fee_amount),
          SUM(tax_amount),
          SUM(COALESCE(realized_profit, 0)),
          MAX(executed_at),
          NOW()
     FROM stock_execution
    GROUP BY DATE(executed_at), account_id',
  'SELECT 1'
);
PREPARE stock_execution_profit_summary_statement FROM @stock_execution_profit_summary_sql;
EXECUTE stock_execution_profit_summary_statement;
DEALLOCATE PREPARE stock_execution_profit_summary_statement;
