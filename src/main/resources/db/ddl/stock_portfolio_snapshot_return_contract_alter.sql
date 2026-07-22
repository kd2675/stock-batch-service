USE STOCK_SERVICE;

-- Persist portfolio profit and contribution explicitly so group performance never depends on
-- averaging account percentages or reverse engineering principal in a client. This migration is
-- bounded to immutable EOD snapshot tables and never reads or writes the hot trading ledgers.
SET SESSION lock_wait_timeout = 15;

SET @stock_portfolio_return_table_count := (
  SELECT COUNT(*)
    FROM information_schema.tables
   WHERE table_schema = DATABASE()
     AND table_name = 'portfolio_snapshot'
     AND table_type = 'BASE TABLE'
     AND engine = 'InnoDB'
);

SET @stock_portfolio_return_new_column_count := (
  SELECT COUNT(*)
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'portfolio_snapshot'
     AND column_name IN ('net_contribution', 'total_profit', 'return_rate_status')
);

SET @stock_portfolio_return_current_column_count := (
  SELECT COUNT(*)
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'portfolio_snapshot'
     AND (
       (column_name IN ('net_contribution', 'total_profit')
        AND data_type = 'decimal' AND numeric_precision = 19 AND numeric_scale = 2
        AND is_nullable = 'YES')
       OR (column_name = 'return_rate_status' AND data_type = 'varchar'
           AND character_maximum_length = 40 AND is_nullable = 'NO')
       OR (column_name = 'return_rate' AND data_type = 'decimal'
           AND numeric_precision = 19 AND numeric_scale = 8 AND is_nullable = 'YES')
     )
);

SET @stock_portfolio_return_check_count := (
  SELECT COUNT(*)
    FROM information_schema.table_constraints
   WHERE constraint_schema = DATABASE()
     AND table_name = 'portfolio_snapshot'
     AND constraint_name = 'chk_portfolio_snapshot_return_contract'
     AND constraint_type = 'CHECK'
     AND enforced = 'YES'
);

SET @stock_portfolio_return_guard_sql := IF(
  @stock_portfolio_return_table_count = 1
  AND (
    (@stock_portfolio_return_new_column_count = 0 AND @stock_portfolio_return_check_count = 0)
    OR (
      @stock_portfolio_return_new_column_count = 3
      AND @stock_portfolio_return_current_column_count = 4
      AND @stock_portfolio_return_check_count = 1
    )
  ),
  'SELECT 1',
  'SELECT 1 FROM stock_migration_required_portfolio_snapshot_return_contract_schema'
);

PREPARE stock_portfolio_return_guard_stmt FROM @stock_portfolio_return_guard_sql;
EXECUTE stock_portfolio_return_guard_stmt;
DEALLOCATE PREPARE stock_portfolio_return_guard_stmt;

SET @stock_portfolio_return_alter_sql := IF(
  @stock_portfolio_return_new_column_count = 0,
  'ALTER TABLE portfolio_snapshot
     ADD COLUMN net_contribution DECIMAL(19,2) NULL AFTER holding_position_count,
     ADD COLUMN total_profit DECIMAL(19,2) NULL AFTER net_contribution,
     MODIFY COLUMN return_rate DECIMAL(19,8) NULL,
     ADD COLUMN return_rate_status VARCHAR(40) NOT NULL DEFAULT ''LEGACY_UNVERIFIED'' AFTER return_rate,
     ADD CONSTRAINT chk_portfolio_snapshot_return_contract CHECK (
       (return_rate_status = ''LEGACY_UNVERIFIED'' AND net_contribution IS NULL AND total_profit IS NULL)
       OR (
         net_contribution IS NOT NULL
         AND total_profit IS NOT NULL
         AND total_profit = total_asset - net_contribution
         AND (
           (return_rate_status = ''DEFINED'' AND net_contribution > 0 AND return_rate IS NOT NULL)
           OR (return_rate_status = ''UNDEFINED_ZERO_CONTRIBUTION'' AND net_contribution = 0 AND return_rate IS NULL)
           OR (return_rate_status = ''UNDEFINED_NEGATIVE_CONTRIBUTION'' AND net_contribution < 0 AND return_rate IS NULL)
         )
       )
     )',
  'SELECT 1'
);

PREPARE stock_portfolio_return_alter_stmt FROM @stock_portfolio_return_alter_sql;
EXECUTE stock_portfolio_return_alter_stmt;
DEALLOCATE PREPARE stock_portfolio_return_alter_stmt;

-- Cycle-linked rows have an immutable contribution source and can be backfilled exactly.
UPDATE portfolio_snapshot portfolio
JOIN stock_close_account_snapshot account_snapshot
  ON account_snapshot.close_cycle_id = portfolio.close_cycle_id
 AND account_snapshot.close_run_id = portfolio.close_run_id
 AND account_snapshot.account_id = portfolio.account_id
SET portfolio.net_contribution = account_snapshot.external_net_cash_flow,
    portfolio.total_profit = portfolio.total_asset - account_snapshot.external_net_cash_flow,
    portfolio.return_rate = CASE
      WHEN account_snapshot.external_net_cash_flow > 0 THEN ROUND(
        (portfolio.total_asset - account_snapshot.external_net_cash_flow)
        * 100 / account_snapshot.external_net_cash_flow,
        8
      )
      ELSE NULL
    END,
    portfolio.return_rate_status = CASE
      WHEN account_snapshot.external_net_cash_flow > 0 THEN 'DEFINED'
      WHEN account_snapshot.external_net_cash_flow = 0 THEN 'UNDEFINED_ZERO_CONTRIBUTION'
      ELSE 'UNDEFINED_NEGATIVE_CONTRIBUTION'
    END,
    portfolio.calculation_version = 'portfolio-v5-net-contribution-return'
WHERE account_snapshot.settlement_target = TRUE
  AND account_snapshot.reconciliation_status = 'MATCHED';

-- Rows without immutable close inputs deliberately remain LEGACY_UNVERIFIED. Reconstructing their
-- principal from a rounded historical return would fabricate precision and is not allowed.
