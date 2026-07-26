USE STOCK_SERVICE;

-- Institution portfolios and decision runs are direct-to-LIVE. Pending legacy
-- intents are retired before the legacy execution-mode values are normalized.
UPDATE stock_institution_order_intent intent
JOIN stock_institution_decision_run decision_run
  ON decision_run.id = intent.decision_run_id
SET intent.status = 'REJECTED',
    intent.submission_reason = 'LEGACY_NON_LIVE_MODE_RETIRED',
    intent.updated_at = CURRENT_TIMESTAMP
WHERE intent.status = 'PENDING'
  AND decision_run.execution_mode <> 'LIVE';

UPDATE stock_institution_portfolio
SET execution_mode = 'LIVE',
    updated_at = CURRENT_TIMESTAMP
WHERE execution_mode <> 'LIVE';

UPDATE stock_institution_decision_run
SET execution_mode = 'LIVE'
WHERE execution_mode <> 'LIVE';

ALTER TABLE stock_institution_portfolio
  ALTER COLUMN execution_mode SET DEFAULT 'LIVE';

SET @stock_institution_portfolio_mode_check_exists = (
  SELECT COUNT(*)
    FROM information_schema.table_constraints
   WHERE constraint_schema = DATABASE()
     AND table_name = 'stock_institution_portfolio'
     AND constraint_name = 'chk_stock_institution_portfolio_mode'
     AND constraint_type = 'CHECK'
);
SET @stock_institution_portfolio_drop_mode_check_sql = IF(
  @stock_institution_portfolio_mode_check_exists = 1,
  'ALTER TABLE stock_institution_portfolio DROP CHECK chk_stock_institution_portfolio_mode',
  'SELECT 1'
);
PREPARE stock_institution_portfolio_drop_mode_check_stmt
  FROM @stock_institution_portfolio_drop_mode_check_sql;
EXECUTE stock_institution_portfolio_drop_mode_check_stmt;
DEALLOCATE PREPARE stock_institution_portfolio_drop_mode_check_stmt;

ALTER TABLE stock_institution_portfolio
  ADD CONSTRAINT chk_stock_institution_portfolio_mode
  CHECK (`execution_mode` = 'LIVE');

SET @stock_institution_decision_run_mode_check_exists = (
  SELECT COUNT(*)
    FROM information_schema.table_constraints
   WHERE constraint_schema = DATABASE()
     AND table_name = 'stock_institution_decision_run'
     AND constraint_name = 'chk_stock_institution_decision_run_mode'
     AND constraint_type = 'CHECK'
);
SET @stock_institution_decision_run_drop_mode_check_sql = IF(
  @stock_institution_decision_run_mode_check_exists = 1,
  'ALTER TABLE stock_institution_decision_run DROP CHECK chk_stock_institution_decision_run_mode',
  'SELECT 1'
);
PREPARE stock_institution_decision_run_drop_mode_check_stmt
  FROM @stock_institution_decision_run_drop_mode_check_sql;
EXECUTE stock_institution_decision_run_drop_mode_check_stmt;
DEALLOCATE PREPARE stock_institution_decision_run_drop_mode_check_stmt;

ALTER TABLE stock_institution_decision_run
  ADD CONSTRAINT chk_stock_institution_decision_run_mode
  CHECK (`execution_mode` = 'LIVE');

-- The direct-to-LIVE engine uses only the institution-market runtime job.
-- Remove the obsolete SHADOW control and lease rows if an earlier rollout
-- created them.
DELETE FROM stock_batch_job_lock
 WHERE job_name = 'institution-shadow-decision';

DELETE FROM stock_batch_job_control
 WHERE job_name = 'institution-shadow-decision';
