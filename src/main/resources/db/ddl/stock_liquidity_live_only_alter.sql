USE STOCK_SERVICE;

-- LP creation is direct-to-LIVE. Incomplete legacy SHADOW/PILOT setups are suspended,
-- never promoted to active trading. Order, execution, account, and holding ledgers stay intact.
UPDATE stock_liquidity_daily_state
SET state_status = 'HALTED',
    gate_reason = 'LEGACY_NON_LIVE_MODE_RETIRED',
    limit_breached = TRUE,
    updated_at = CURRENT_TIMESTAMP
WHERE state_status = 'SHADOW';

UPDATE stock_liquidity_mandate
SET execution_mode = 'LIVE',
    status = 'SUSPENDED',
    next_quote_at = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE execution_mode <> 'LIVE';

UPDATE stock_liquidity_transition
SET stage = 'SUSPENDED',
    activated_at = COALESCE(activated_at, updated_at, CURRENT_TIMESTAMP),
    updated_at = CURRENT_TIMESTAMP
WHERE stage = 'SHADOW_READY';

ALTER TABLE stock_liquidity_mandate
  ALTER COLUMN execution_mode SET DEFAULT 'LIVE';

ALTER TABLE stock_liquidity_daily_state
  ALTER COLUMN state_status SET DEFAULT 'QUOTING';

ALTER TABLE stock_liquidity_transition
  ALTER COLUMN stage SET DEFAULT 'LIVE_ACTIVE';

SET @stock_liquidity_mandate_mode_check_exists = (
  SELECT COUNT(*)
    FROM information_schema.table_constraints
   WHERE constraint_schema = DATABASE()
     AND table_name = 'stock_liquidity_mandate'
     AND constraint_name = 'chk_stock_liquidity_mandate_mode'
     AND constraint_type = 'CHECK'
);
SET @stock_liquidity_mandate_drop_mode_check_sql = IF(
  @stock_liquidity_mandate_mode_check_exists = 1,
  'ALTER TABLE stock_liquidity_mandate DROP CHECK chk_stock_liquidity_mandate_mode',
  'SELECT 1'
);
PREPARE stock_liquidity_mandate_drop_mode_check_stmt
  FROM @stock_liquidity_mandate_drop_mode_check_sql;
EXECUTE stock_liquidity_mandate_drop_mode_check_stmt;
DEALLOCATE PREPARE stock_liquidity_mandate_drop_mode_check_stmt;

ALTER TABLE stock_liquidity_mandate
  ADD CONSTRAINT chk_stock_liquidity_mandate_mode
  CHECK (`execution_mode` = 'LIVE');

SET @stock_liquidity_daily_state_status_check_exists = (
  SELECT COUNT(*)
    FROM information_schema.table_constraints
   WHERE constraint_schema = DATABASE()
     AND table_name = 'stock_liquidity_daily_state'
     AND constraint_name = 'chk_stock_liquidity_daily_state_status'
     AND constraint_type = 'CHECK'
);
SET @stock_liquidity_daily_state_drop_status_check_sql = IF(
  @stock_liquidity_daily_state_status_check_exists = 1,
  'ALTER TABLE stock_liquidity_daily_state DROP CHECK chk_stock_liquidity_daily_state_status',
  'SELECT 1'
);
PREPARE stock_liquidity_daily_state_drop_status_check_stmt
  FROM @stock_liquidity_daily_state_drop_status_check_sql;
EXECUTE stock_liquidity_daily_state_drop_status_check_stmt;
DEALLOCATE PREPARE stock_liquidity_daily_state_drop_status_check_stmt;

ALTER TABLE stock_liquidity_daily_state
  ADD CONSTRAINT chk_stock_liquidity_daily_state_status
  CHECK (
    CASE `state_status`
      WHEN 'QUOTING' THEN 1
      WHEN 'EXEMPT' THEN 1
      WHEN 'HALTED' THEN 1
      WHEN 'ERROR' THEN 1
      ELSE 0
    END = 1
  );

SET @stock_liquidity_transition_stage_check_exists = (
  SELECT COUNT(*)
    FROM information_schema.table_constraints
   WHERE constraint_schema = DATABASE()
     AND table_name = 'stock_liquidity_transition'
     AND constraint_name = 'chk_stock_liquidity_transition_stage'
     AND constraint_type = 'CHECK'
);
SET @stock_liquidity_transition_drop_stage_check_sql = IF(
  @stock_liquidity_transition_stage_check_exists = 1,
  'ALTER TABLE stock_liquidity_transition DROP CHECK chk_stock_liquidity_transition_stage',
  'SELECT 1'
);
PREPARE stock_liquidity_transition_drop_stage_check_stmt
  FROM @stock_liquidity_transition_drop_stage_check_sql;
EXECUTE stock_liquidity_transition_drop_stage_check_stmt;
DEALLOCATE PREPARE stock_liquidity_transition_drop_stage_check_stmt;

ALTER TABLE stock_liquidity_transition
  ADD CONSTRAINT chk_stock_liquidity_transition_stage
  CHECK (
    CASE stage
      WHEN 'LIVE_ACTIVE' THEN 1
      WHEN 'SUSPENDED' THEN 1
      ELSE 0
    END = 1
  );

SET @stock_liquidity_transition_activation_check_exists = (
  SELECT COUNT(*)
    FROM information_schema.table_constraints
   WHERE constraint_schema = DATABASE()
     AND table_name = 'stock_liquidity_transition'
     AND constraint_name = 'chk_stock_liquidity_transition_activation'
     AND constraint_type = 'CHECK'
);
SET @stock_liquidity_transition_drop_activation_check_sql = IF(
  @stock_liquidity_transition_activation_check_exists = 1,
  'ALTER TABLE stock_liquidity_transition DROP CHECK chk_stock_liquidity_transition_activation',
  'SELECT 1'
);
PREPARE stock_liquidity_transition_drop_activation_check_stmt
  FROM @stock_liquidity_transition_drop_activation_check_sql;
EXECUTE stock_liquidity_transition_drop_activation_check_stmt;
DEALLOCATE PREPARE stock_liquidity_transition_drop_activation_check_stmt;

ALTER TABLE stock_liquidity_transition
  ADD CONSTRAINT chk_stock_liquidity_transition_activation
  CHECK (
    stage IN ('LIVE_ACTIVE', 'SUSPENDED')
    AND activated_at IS NOT NULL
  );
