USE STOCK_SERVICE;

-- Fail fast instead of waiting indefinitely behind a long-running transaction's metadata lock.
SET SESSION lock_wait_timeout = 15;

SET @stock_portfolio_snapshot_table_count := (
  SELECT COUNT(*)
    FROM information_schema.tables
   WHERE table_schema = DATABASE()
     AND table_name = 'portfolio_snapshot'
     AND table_type = 'BASE TABLE'
     AND engine = 'InnoDB'
);

SET @stock_portfolio_snapshot_holding_metric_column_count := (
  SELECT COUNT(*)
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'portfolio_snapshot'
     AND column_name IN ('holding_quantity', 'reserved_sell_quantity', 'holding_position_count')
);

SET @stock_portfolio_snapshot_holding_metric_correct_column_count := (
  SELECT COUNT(*)
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'portfolio_snapshot'
     AND column_name IN ('holding_quantity', 'reserved_sell_quantity', 'holding_position_count')
     AND column_type = 'bigint'
     AND is_nullable = 'YES'
     AND column_default IS NULL
     AND extra = ''
);

SET @stock_portfolio_snapshot_holding_metric_check_count := (
  SELECT COUNT(*)
    FROM information_schema.check_constraints cc
    JOIN information_schema.table_constraints tc
      ON tc.constraint_schema = cc.constraint_schema
     AND tc.constraint_name = cc.constraint_name
   WHERE cc.constraint_schema = DATABASE()
     AND cc.constraint_name = 'chk_portfolio_snapshot_holding_metrics_complete'
     AND tc.table_name = 'portfolio_snapshot'
     AND tc.constraint_type = 'CHECK'
     AND tc.enforced = 'YES'
     AND REPLACE(LOWER(cc.check_clause), '`', '') LIKE '%holding_quantity is null%'
     AND REPLACE(LOWER(cc.check_clause), '`', '') LIKE '%reserved_sell_quantity is null%'
     AND REPLACE(LOWER(cc.check_clause), '`', '') LIKE '%holding_position_count is null%'
     AND REPLACE(LOWER(cc.check_clause), '`', '') LIKE '%holding_quantity is not null%'
     AND REPLACE(LOWER(cc.check_clause), '`', '') LIKE '%reserved_sell_quantity is not null%'
     AND REPLACE(LOWER(cc.check_clause), '`', '') LIKE '%holding_position_count is not null%'
     AND REPLACE(LOWER(cc.check_clause), '`', '') LIKE '%holding_quantity >= 0%'
     AND REPLACE(LOWER(cc.check_clause), '`', '') LIKE '%reserved_sell_quantity >= 0%'
     AND REPLACE(LOWER(cc.check_clause), '`', '') LIKE '%reserved_sell_quantity <= holding_quantity%'
     AND REPLACE(LOWER(cc.check_clause), '`', '') LIKE '%holding_position_count >= 0%'
);

-- Reject missing base tables, partial migrations, and incompatible pre-existing columns before DDL auto-commit.
SET @stock_portfolio_snapshot_holding_metric_guard_sql := IF(
  @stock_portfolio_snapshot_table_count = 1
  AND (
    (
      @stock_portfolio_snapshot_holding_metric_column_count = 0
      AND @stock_portfolio_snapshot_holding_metric_correct_column_count = 0
      AND @stock_portfolio_snapshot_holding_metric_check_count = 0
    )
    OR (
      @stock_portfolio_snapshot_holding_metric_column_count = 3
      AND @stock_portfolio_snapshot_holding_metric_correct_column_count = 3
      AND @stock_portfolio_snapshot_holding_metric_check_count = 1
    )
  ),
  'SELECT 1',
  'SELECT 1 FROM stock_migration_required_portfolio_snapshot_holding_metrics_schema'
);

PREPARE stock_portfolio_snapshot_holding_metric_guard_stmt
  FROM @stock_portfolio_snapshot_holding_metric_guard_sql;
EXECUTE stock_portfolio_snapshot_holding_metric_guard_stmt;
DEALLOCATE PREPARE stock_portfolio_snapshot_holding_metric_guard_stmt;

SET @stock_portfolio_snapshot_holding_metric_alter_sql := IF(
  @stock_portfolio_snapshot_holding_metric_column_count = 0,
  'ALTER TABLE portfolio_snapshot
     ADD COLUMN holding_quantity BIGINT NULL AFTER market_value,
     ADD COLUMN reserved_sell_quantity BIGINT NULL AFTER holding_quantity,
     ADD COLUMN holding_position_count BIGINT NULL AFTER reserved_sell_quantity,
     ADD CONSTRAINT chk_portfolio_snapshot_holding_metrics_complete CHECK (
       (holding_quantity IS NULL AND reserved_sell_quantity IS NULL AND holding_position_count IS NULL)
       OR (
         holding_quantity IS NOT NULL
         AND reserved_sell_quantity IS NOT NULL
         AND holding_position_count IS NOT NULL
         AND holding_quantity >= 0
         AND reserved_sell_quantity >= 0
         AND reserved_sell_quantity <= holding_quantity
         AND holding_position_count >= 0
       )
     ),
     ALGORITHM=COPY,
     LOCK=SHARED',
  'SELECT 1'
);

PREPARE stock_portfolio_snapshot_holding_metric_alter_stmt
  FROM @stock_portfolio_snapshot_holding_metric_alter_sql;
EXECUTE stock_portfolio_snapshot_holding_metric_alter_stmt;
DEALLOCATE PREPARE stock_portfolio_snapshot_holding_metric_alter_stmt;
