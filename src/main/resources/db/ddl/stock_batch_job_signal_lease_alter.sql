USE STOCK_SERVICE;

SET @stock_batch_signal_column_clauses = CONCAT_WS(', ',
  IF((SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'stock_batch_job_signal'
         AND column_name = 'requested_business_date') = 0,
     'ADD COLUMN requested_business_date DATE NULL AFTER requested_at', NULL),
  IF((SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'stock_batch_job_signal'
         AND column_name = 'requested_session_epoch') = 0,
     'ADD COLUMN requested_session_epoch BIGINT NULL AFTER requested_business_date', NULL),
  IF((SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'stock_batch_job_signal'
         AND column_name = 'expected_cycle_id') = 0,
     'ADD COLUMN expected_cycle_id BIGINT NULL AFTER requested_session_epoch', NULL),
  IF((SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'stock_batch_job_signal'
         AND column_name = 'eligible_at') = 0,
     'ADD COLUMN eligible_at DATETIME NULL AFTER expected_cycle_id', NULL),
  IF((SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'stock_batch_job_signal'
         AND column_name = 'next_attempt_at') = 0,
     'ADD COLUMN next_attempt_at DATETIME NULL AFTER eligible_at', NULL),
  IF((SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'stock_batch_job_signal'
         AND column_name = 'attempt_count') = 0,
     'ADD COLUMN attempt_count INT NOT NULL DEFAULT 0 AFTER next_attempt_at', NULL),
  IF((SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'stock_batch_job_signal'
         AND column_name = 'max_attempts') = 0,
     'ADD COLUMN max_attempts INT NOT NULL DEFAULT 8 AFTER attempt_count', NULL),
  IF((SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'stock_batch_job_signal'
         AND column_name = 'claim_token') = 0,
     'ADD COLUMN claim_token VARCHAR(64) NULL AFTER max_attempts', NULL),
  IF((SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'stock_batch_job_signal'
         AND column_name = 'lease_until') = 0,
     'ADD COLUMN lease_until DATETIME NULL AFTER claim_token', NULL),
  IF((SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema = DATABASE() AND table_name = 'stock_batch_job_signal'
         AND column_name = 'failure_class') = 0,
     'ADD COLUMN failure_class VARCHAR(40) NULL AFTER lease_until', NULL)
);
SET @stock_batch_signal_sql = IF(
  @stock_batch_signal_column_clauses = '',
  'SELECT 1',
  CONCAT('ALTER TABLE stock_batch_job_signal ', @stock_batch_signal_column_clauses)
);
PREPARE stock_batch_signal_statement FROM @stock_batch_signal_sql;
EXECUTE stock_batch_signal_statement;
DEALLOCATE PREPARE stock_batch_signal_statement;

UPDATE stock_batch_job_signal
   SET next_attempt_at = COALESCE(next_attempt_at, requested_at)
 WHERE next_attempt_at IS NULL;

UPDATE stock_batch_job_signal job_signal
  JOIN stock_market_business_state state
    ON state.state_id = 'DEFAULT'
  LEFT JOIN stock_post_close_cycle cycle
    ON cycle.business_date = state.active_business_date
   AND cycle.scope_type = 'FULL_MARKET'
   AND cycle.scope_key = 'ALL'
   SET job_signal.requested_business_date = state.active_business_date,
       job_signal.expected_cycle_id = COALESCE(job_signal.expected_cycle_id, cycle.id)
 WHERE job_signal.requested_business_date IS NULL
   AND job_signal.symbol IS NULL
   AND job_signal.status IN ('PENDING', 'PROCESSING');

UPDATE stock_batch_job_signal job_signal
  JOIN stock_market_business_state state
    ON state.state_id = 'DEFAULT'
  JOIN stock_market_session_fence fence
    ON fence.market_type = 'ORDER_BOOK'
   AND fence.symbol = job_signal.symbol
  LEFT JOIN stock_post_close_cycle cycle
    ON cycle.business_date = state.active_business_date
   AND cycle.scope_type = 'SYMBOL'
   AND cycle.scope_key = job_signal.symbol
   SET job_signal.requested_business_date = state.active_business_date,
       job_signal.requested_session_epoch = COALESCE(job_signal.requested_session_epoch, fence.session_epoch),
       job_signal.expected_cycle_id = COALESCE(job_signal.expected_cycle_id, cycle.id)
 WHERE job_signal.requested_business_date IS NULL
   AND job_signal.symbol IS NOT NULL
   AND job_signal.status IN ('PENDING', 'PROCESSING');

SET @stock_batch_signal_sql = (
  SELECT CASE WHEN COUNT(*) = 1 THEN
    'ALTER TABLE stock_batch_job_signal MODIFY COLUMN next_attempt_at DATETIME NOT NULL'
  ELSE 'SELECT 1' END
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_batch_job_signal'
     AND column_name = 'next_attempt_at'
     AND is_nullable = 'YES'
);
PREPARE stock_batch_signal_statement FROM @stock_batch_signal_sql;
EXECUTE stock_batch_signal_statement;
DEALLOCATE PREPARE stock_batch_signal_statement;

SET @stock_batch_signal_sql = (
  SELECT CASE
           WHEN COUNT(*) = 1 AND MAX(checks.check_clause) NOT LIKE '%DEAD_LETTER%' THEN
             'ALTER TABLE stock_batch_job_signal DROP CHECK chk_stock_batch_job_signal_status'
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
PREPARE stock_batch_signal_statement FROM @stock_batch_signal_sql;
EXECUTE stock_batch_signal_statement;
DEALLOCATE PREPARE stock_batch_signal_statement;

SET @stock_batch_signal_constraint_clauses = CONCAT_WS(', ',
  IF((SELECT COUNT(*) FROM information_schema.table_constraints
       WHERE table_schema = DATABASE() AND table_name = 'stock_batch_job_signal'
         AND constraint_name = 'chk_stock_batch_job_signal_status') = 0,
     'ADD CONSTRAINT chk_stock_batch_job_signal_status CHECK (CASE `status` WHEN ''PENDING'' THEN 1 WHEN ''DEFERRED'' THEN 1 WHEN ''PROCESSING'' THEN 1 WHEN ''COMPLETED'' THEN 1 WHEN ''FAILED'' THEN 1 WHEN ''DEAD_LETTER'' THEN 1 ELSE 0 END = 1)', NULL),
  IF((SELECT COUNT(*) FROM information_schema.table_constraints
       WHERE table_schema = DATABASE() AND table_name = 'stock_batch_job_signal'
         AND constraint_name = 'chk_stock_batch_job_signal_attempt_count') = 0,
     'ADD CONSTRAINT chk_stock_batch_job_signal_attempt_count CHECK (attempt_count >= 0)', NULL),
  IF((SELECT COUNT(*) FROM information_schema.table_constraints
       WHERE table_schema = DATABASE() AND table_name = 'stock_batch_job_signal'
         AND constraint_name = 'chk_stock_batch_job_signal_max_attempts') = 0,
     'ADD CONSTRAINT chk_stock_batch_job_signal_max_attempts CHECK (max_attempts > 0)', NULL),
  IF((SELECT COUNT(*) FROM information_schema.table_constraints
       WHERE table_schema = DATABASE() AND table_name = 'stock_batch_job_signal'
         AND constraint_name = 'chk_stock_batch_job_signal_epoch') = 0,
     'ADD CONSTRAINT chk_stock_batch_job_signal_epoch CHECK (requested_session_epoch IS NULL OR requested_session_epoch > 0)', NULL)
);
SET @stock_batch_signal_sql = IF(
  @stock_batch_signal_constraint_clauses = '',
  'SELECT 1',
  CONCAT('ALTER TABLE stock_batch_job_signal ', @stock_batch_signal_constraint_clauses)
);
PREPARE stock_batch_signal_statement FROM @stock_batch_signal_sql;
EXECUTE stock_batch_signal_statement;
DEALLOCATE PREPARE stock_batch_signal_statement;

SET @stock_batch_signal_index_clauses = CONCAT_WS(', ',
  IF((SELECT COUNT(*) FROM information_schema.statistics
       WHERE table_schema = DATABASE() AND table_name = 'stock_batch_job_signal'
         AND index_name = 'idx_stock_batch_job_signal_claim') = 0,
     'ADD KEY idx_stock_batch_job_signal_claim (status, next_attempt_at, eligible_at, id)', NULL),
  IF((SELECT COUNT(*) FROM information_schema.statistics
       WHERE table_schema = DATABASE() AND table_name = 'stock_batch_job_signal'
         AND index_name = 'idx_stock_batch_job_signal_lease') = 0,
     'ADD KEY idx_stock_batch_job_signal_lease (status, lease_until, id)', NULL),
  IF((SELECT COUNT(*) FROM information_schema.statistics
       WHERE table_schema = DATABASE() AND table_name = 'stock_batch_job_signal'
         AND index_name = 'idx_stock_batch_job_signal_cycle') = 0,
     'ADD KEY idx_stock_batch_job_signal_cycle (expected_cycle_id, status, id)', NULL)
);
SET @stock_batch_signal_sql = IF(
  @stock_batch_signal_index_clauses = '',
  'SELECT 1',
  CONCAT('ALTER TABLE stock_batch_job_signal ', @stock_batch_signal_index_clauses)
);
PREPARE stock_batch_signal_statement FROM @stock_batch_signal_sql;
EXECUTE stock_batch_signal_statement;
DEALLOCATE PREPARE stock_batch_signal_statement;
