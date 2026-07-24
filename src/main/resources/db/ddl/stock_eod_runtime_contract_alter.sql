USE STOCK_SERVICE;

SET @stock_eod_contract_add_cycle_column = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE stock_post_close_cycle ADD COLUMN eod_contract_version VARCHAR(100) NULL AFTER schema_version',
    'SELECT 1'
  )
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_post_close_cycle'
     AND column_name = 'eod_contract_version'
);
PREPARE stock_eod_contract_add_cycle_column_stmt FROM @stock_eod_contract_add_cycle_column;
EXECUTE stock_eod_contract_add_cycle_column_stmt;
DEALLOCATE PREPARE stock_eod_contract_add_cycle_column_stmt;

SET @stock_eod_contract_add_attempt_column = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE stock_post_close_phase_attempt ADD COLUMN eod_contract_version VARCHAR(100) NULL AFTER schema_version',
    'SELECT 1'
  )
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_post_close_phase_attempt'
     AND column_name = 'eod_contract_version'
);
PREPARE stock_eod_contract_add_attempt_column_stmt FROM @stock_eod_contract_add_attempt_column;
EXECUTE stock_eod_contract_add_attempt_column_stmt;
DEALLOCATE PREPARE stock_eod_contract_add_attempt_column_stmt;

UPDATE stock_post_close_cycle
   SET eod_contract_version = CASE
         WHEN schema_version IN (
           '2026-07-22-eod-v3',
           '2026-07-23-auto-profile-v2-direct'
         ) THEN 'EOD_V1'
         WHEN status = 'COMPLETED' THEN 'LEGACY_COMPLETED'
         ELSE 'UNDECLARED'
       END
 WHERE eod_contract_version IS NULL
    OR TRIM(eod_contract_version) = '';

UPDATE stock_post_close_phase_attempt attempt
  LEFT JOIN stock_post_close_cycle cycle ON cycle.id = attempt.cycle_id
   SET attempt.eod_contract_version = COALESCE(cycle.eod_contract_version, 'UNDECLARED')
 WHERE attempt.eod_contract_version IS NULL
    OR TRIM(attempt.eod_contract_version) = '';

SET @stock_eod_contract_harden_cycle_column = (
  SELECT IF(
    is_nullable = 'YES' OR COALESCE(column_default, '') <> 'UNDECLARED',
    'ALTER TABLE stock_post_close_cycle MODIFY COLUMN eod_contract_version VARCHAR(100) NOT NULL DEFAULT ''UNDECLARED''',
    'SELECT 1'
  )
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_post_close_cycle'
     AND column_name = 'eod_contract_version'
);
PREPARE stock_eod_contract_harden_cycle_column_stmt FROM @stock_eod_contract_harden_cycle_column;
EXECUTE stock_eod_contract_harden_cycle_column_stmt;
DEALLOCATE PREPARE stock_eod_contract_harden_cycle_column_stmt;

SET @stock_eod_contract_harden_attempt_column = (
  SELECT IF(
    is_nullable = 'YES' OR COALESCE(column_default, '') <> 'UNDECLARED',
    'ALTER TABLE stock_post_close_phase_attempt MODIFY COLUMN eod_contract_version VARCHAR(100) NOT NULL DEFAULT ''UNDECLARED''',
    'SELECT 1'
  )
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_post_close_phase_attempt'
     AND column_name = 'eod_contract_version'
);
PREPARE stock_eod_contract_harden_attempt_column_stmt FROM @stock_eod_contract_harden_attempt_column;
EXECUTE stock_eod_contract_harden_attempt_column_stmt;
DEALLOCATE PREPARE stock_eod_contract_harden_attempt_column_stmt;

SET @stock_eod_contract_add_cycle_check = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE stock_post_close_cycle ADD CONSTRAINT chk_stock_post_close_cycle_eod_contract CHECK (eod_contract_version <> '''')',
    'SELECT 1'
  )
    FROM information_schema.table_constraints
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_post_close_cycle'
     AND constraint_name = 'chk_stock_post_close_cycle_eod_contract'
);
PREPARE stock_eod_contract_add_cycle_check_stmt FROM @stock_eod_contract_add_cycle_check;
EXECUTE stock_eod_contract_add_cycle_check_stmt;
DEALLOCATE PREPARE stock_eod_contract_add_cycle_check_stmt;

SET @stock_eod_contract_add_attempt_check = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE stock_post_close_phase_attempt ADD CONSTRAINT chk_stock_post_close_phase_attempt_eod_contract CHECK (eod_contract_version <> '''')',
    'SELECT 1'
  )
    FROM information_schema.table_constraints
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_post_close_phase_attempt'
     AND constraint_name = 'chk_stock_post_close_phase_attempt_eod_contract'
);
PREPARE stock_eod_contract_add_attempt_check_stmt FROM @stock_eod_contract_add_attempt_check;
EXECUTE stock_eod_contract_add_attempt_check_stmt;
DEALLOCATE PREPARE stock_eod_contract_add_attempt_check_stmt;
