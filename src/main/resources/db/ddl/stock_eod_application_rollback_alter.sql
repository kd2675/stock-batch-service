USE STOCK_SERVICE;

-- Non-destructive application compatibility rollback for the EOD v1 schema.
-- Run only while stock-back-service and stock-batch-service are stopped.
--
-- This intentionally keeps additive EOD tables, columns, indexes, and audit data so a later
-- forward deployment can resume without data loss. Exact pre-migration schema restoration must
-- use the maintenance-window dump captured before the forward ALTERs.
--
-- The legacy application does not understand eligible_at, DEFERRED, DEAD_LETTER, or signal
-- leases. Fail those commands closed instead of allowing the legacy batch process to execute a
-- phase-gated or possibly partially completed command.
SET @stock_eod_rollback_signal_lease_column_count = (
  SELECT COUNT(*)
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_batch_job_signal'
     AND column_name IN ('eligible_at', 'claim_token', 'lease_until', 'failure_class')
);
SET @stock_eod_rollback_sql = IF(
  @stock_eod_rollback_signal_lease_column_count = 4,
  'UPDATE stock_batch_job_signal
      SET status = ''FAILED'',
          completed_at = COALESCE(completed_at, CURRENT_TIMESTAMP),
          processed_count = COALESCE(processed_count, 0),
          message = LEFT(CONCAT_WS('' | '', NULLIF(message, ''''),
            ''Stopped by EOD application compatibility rollback; manual review required''), 500),
          error_message = LEFT(CONCAT_WS('' | '', NULLIF(error_message, ''''),
            ''EOD_APPLICATION_ROLLBACK''), 1000),
          claim_token = NULL,
          lease_until = NULL,
          failure_class = ''APPLICATION_ROLLBACK'',
          updated_at = CURRENT_TIMESTAMP
    WHERE status IN (''DEFERRED'', ''PROCESSING'', ''DEAD_LETTER'')
       OR (status = ''PENDING'' AND eligible_at IS NOT NULL)',
  'SELECT 1'
);
PREPARE stock_eod_rollback_statement FROM @stock_eod_rollback_sql;
EXECUTE stock_eod_rollback_statement;
DEALLOCATE PREPARE stock_eod_rollback_statement;

-- Restore the legacy status domain in one atomic ALTER. The new columns remain available for a
-- later forward deployment, but the legacy application can only create its four known statuses.
SET @stock_eod_rollback_sql = (
  SELECT CASE
           WHEN COUNT(*) = 0 THEN
             'ALTER TABLE stock_batch_job_signal ADD CONSTRAINT chk_stock_batch_job_signal_status CHECK (CASE `status` WHEN ''PENDING'' THEN 1 WHEN ''PROCESSING'' THEN 1 WHEN ''COMPLETED'' THEN 1 WHEN ''FAILED'' THEN 1 ELSE 0 END = 1)'
           WHEN MAX(checks.check_clause) LIKE '%DEFERRED%'
             OR MAX(checks.check_clause) LIKE '%DEAD_LETTER%' THEN
             'ALTER TABLE stock_batch_job_signal DROP CHECK chk_stock_batch_job_signal_status, ADD CONSTRAINT chk_stock_batch_job_signal_status CHECK (CASE `status` WHEN ''PENDING'' THEN 1 WHEN ''PROCESSING'' THEN 1 WHEN ''COMPLETED'' THEN 1 WHEN ''FAILED'' THEN 1 ELSE 0 END = 1)'
           ELSE 'SELECT 1'
         END
    FROM information_schema.table_constraints constraints_metadata
    JOIN information_schema.check_constraints checks
      ON checks.constraint_schema = constraints_metadata.constraint_schema
     AND checks.constraint_name = constraints_metadata.constraint_name
   WHERE constraints_metadata.table_schema = DATABASE()
     AND constraints_metadata.table_name = 'stock_batch_job_signal'
     AND constraints_metadata.constraint_name = 'chk_stock_batch_job_signal_status'
);
PREPARE stock_eod_rollback_statement FROM @stock_eod_rollback_sql;
EXECUTE stock_eod_rollback_statement;
DEALLOCATE PREPARE stock_eod_rollback_statement;

-- Legacy stock-back-service inserts only the pre-EOD signal columns. Nullable next_attempt_at is
-- the sole schema compatibility change required for that INSERT contract.
SET @stock_eod_rollback_sql = (
  SELECT CASE WHEN COUNT(*) = 1 THEN
    'ALTER TABLE stock_batch_job_signal MODIFY COLUMN next_attempt_at DATETIME NULL'
  ELSE 'SELECT 1' END
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_batch_job_signal'
     AND column_name = 'next_attempt_at'
     AND is_nullable = 'NO'
);
PREPARE stock_eod_rollback_statement FROM @stock_eod_rollback_sql;
EXECUTE stock_eod_rollback_statement;
DEALLOCATE PREPARE stock_eod_rollback_statement;
