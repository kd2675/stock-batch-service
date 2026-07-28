USE STOCK_SERVICE;

-- V3 removes the former general-participant market-maker identity and all V1/V2
-- runtime model labels. Drop the legacy checks before rewriting identifiers;
-- economic order, execution, cash and holding values are not changed.
SET @v3_profile_types := '''NEWS_REACTIVE'', ''MOMENTUM_FOLLOWER'', ''CONTRARIAN'',
  ''LOSS_AVERSE'', ''OVERCONFIDENT'', ''HERD_FOLLOWER'',
  ''PASSIVE_LIMIT_TRADER'', ''NOISE_TRADER'', ''VALUE_ANCHOR'', ''SCALPER'',
  ''DAY_TRADER'', ''SWING_TRADER'', ''LONG_TERM_HOLDER'', ''PAYDAY_ACCUMULATOR'',
  ''DIVIDEND_REINVESTOR'', ''LIMIT_DOWN_TRAPPED'', ''AVERAGE_DOWN_BUYER'',
  ''STOP_LOSS_TRADER'', ''FOMO_BUYER'', ''PANIC_SELLER'', ''DIP_BUYER'',
  ''PROFIT_LOCKER'', ''LIQUIDITY_AVOIDANT'', ''CASH_DEFENSIVE'', ''WHALE'',
  ''SMALL_DIVERSIFIER'', ''OBSERVER''';

SET @v3_drop_participant_profile_check_sql := IF(
  EXISTS (
    SELECT 1 FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE()
       AND table_name = 'stock_auto_participant'
       AND constraint_name = 'chk_stock_auto_participant_profile_type'
  ),
  'ALTER TABLE stock_auto_participant
     DROP CHECK chk_stock_auto_participant_profile_type',
  'SELECT 1'
);
PREPARE v3_drop_participant_profile_check_stmt
  FROM @v3_drop_participant_profile_check_sql;
EXECUTE v3_drop_participant_profile_check_stmt;
DEALLOCATE PREPARE v3_drop_participant_profile_check_stmt;

SET @v3_drop_profile_config_type_check_sql := IF(
  EXISTS (
    SELECT 1 FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE()
       AND table_name = 'stock_auto_participant_profile_config'
       AND constraint_name = 'chk_stock_auto_profile_config_type'
  ),
  'ALTER TABLE stock_auto_participant_profile_config
     DROP CHECK chk_stock_auto_profile_config_type',
  'SELECT 1'
);
PREPARE v3_drop_profile_config_type_check_stmt
  FROM @v3_drop_profile_config_type_check_sql;
EXECUTE v3_drop_profile_config_type_check_stmt;
DEALLOCATE PREPARE v3_drop_profile_config_type_check_stmt;

SET @v3_drop_event_profile_type_check_sql := IF(
  EXISTS (
    SELECT 1 FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE()
       AND table_name = 'stock_auto_participant_event_profile_config'
       AND constraint_name = 'chk_stock_auto_event_profile_type'
  ),
  'ALTER TABLE stock_auto_participant_event_profile_config
     DROP CHECK chk_stock_auto_event_profile_type',
  'SELECT 1'
);
PREPARE v3_drop_event_profile_type_check_stmt
  FROM @v3_drop_event_profile_type_check_sql;
EXECUTE v3_drop_event_profile_type_check_stmt;
DEALLOCATE PREPARE v3_drop_event_profile_type_check_stmt;

SET @v3_drop_close_snapshot_profile_check_sql := IF(
  EXISTS (
    SELECT 1 FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE()
       AND table_name = 'stock_close_account_snapshot'
       AND constraint_name = 'chk_stock_close_account_snapshot_profile_type'
  ),
  'ALTER TABLE stock_close_account_snapshot
     DROP CHECK chk_stock_close_account_snapshot_profile_type',
  'SELECT 1'
);
PREPARE v3_drop_close_snapshot_profile_check_stmt
  FROM @v3_drop_close_snapshot_profile_check_sql;
EXECUTE v3_drop_close_snapshot_profile_check_stmt;
DEALLOCATE PREPARE v3_drop_close_snapshot_profile_check_stmt;

SET @v3_drop_order_profile_check_sql := IF(
  EXISTS (
    SELECT 1 FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE()
       AND table_name = 'stock_order'
       AND constraint_name = 'chk_stock_order_auto_profile_type'
  ),
  'ALTER TABLE stock_order DROP CHECK chk_stock_order_auto_profile_type',
  'SELECT 1'
);
PREPARE v3_drop_order_profile_check_stmt FROM @v3_drop_order_profile_check_sql;
EXECUTE v3_drop_order_profile_check_stmt;
DEALLOCATE PREPARE v3_drop_order_profile_check_stmt;

SET @v3_drop_order_model_check_sql := IF(
  EXISTS (
    SELECT 1 FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE()
       AND table_name = 'stock_order'
       AND constraint_name = 'chk_stock_order_auto_behavior_model'
  ),
  'ALTER TABLE stock_order DROP CHECK chk_stock_order_auto_behavior_model',
  'SELECT 1'
);
PREPARE v3_drop_order_model_check_stmt FROM @v3_drop_order_model_check_sql;
EXECUTE v3_drop_order_model_check_stmt;
DEALLOCATE PREPARE v3_drop_order_model_check_stmt;

SET @v3_drop_profile_model_check_sql := IF(
  EXISTS (
    SELECT 1 FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE()
       AND table_name = 'stock_auto_participant_profile_config'
       AND constraint_name = 'chk_stock_auto_profile_behavior_model'
  ),
  'ALTER TABLE stock_auto_participant_profile_config
     DROP CHECK chk_stock_auto_profile_behavior_model',
  'SELECT 1'
);
PREPARE v3_drop_profile_model_check_stmt FROM @v3_drop_profile_model_check_sql;
EXECUTE v3_drop_profile_model_check_stmt;
DEALLOCATE PREPARE v3_drop_profile_model_check_stmt;

UPDATE stock_auto_participant
   SET profile_type = 'PASSIVE_LIMIT_TRADER'
 WHERE profile_type = 'MARKET_MAKER';

UPDATE stock_auto_participant_profile_config
   SET profile_type = 'PASSIVE_LIMIT_TRADER'
 WHERE profile_type = 'MARKET_MAKER';

UPDATE stock_auto_participant_event_profile_config
   SET profile_type = 'PASSIVE_LIMIT_TRADER'
 WHERE profile_type = 'MARKET_MAKER';

UPDATE stock_close_account_snapshot
   SET participant_profile_type = 'PASSIVE_LIMIT_TRADER'
 WHERE participant_profile_type = 'MARKET_MAKER';

UPDATE stock_order
   SET auto_profile_type = 'PASSIVE_LIMIT_TRADER'
 WHERE auto_profile_type = 'MARKET_MAKER';

UPDATE stock_order
   SET auto_behavior_model_version = 'V3'
 WHERE auto_behavior_model_version IN ('V1', 'V2');

UPDATE stock_auto_participant_profile_config
   SET behavior_model_version = 'V3'
 WHERE behavior_model_version <> 'V3';

SET @v3_profile_type_check_sql := CONCAT(
  'ALTER TABLE stock_auto_participant ',
  'ADD CONSTRAINT chk_stock_auto_participant_profile_type ',
  'CHECK (profile_type IN (', @v3_profile_types, '))'
);
PREPARE v3_profile_type_check_stmt FROM @v3_profile_type_check_sql;
EXECUTE v3_profile_type_check_stmt;
DEALLOCATE PREPARE v3_profile_type_check_stmt;

SET @v3_profile_config_type_check_sql := CONCAT(
  'ALTER TABLE stock_auto_participant_profile_config ',
  'ADD CONSTRAINT chk_stock_auto_profile_config_type ',
  'CHECK (profile_type IN (', @v3_profile_types, '))'
);
PREPARE v3_profile_config_type_check_stmt FROM @v3_profile_config_type_check_sql;
EXECUTE v3_profile_config_type_check_stmt;
DEALLOCATE PREPARE v3_profile_config_type_check_stmt;

SET @v3_event_profile_type_check_sql := CONCAT(
  'ALTER TABLE stock_auto_participant_event_profile_config ',
  'ADD CONSTRAINT chk_stock_auto_event_profile_type ',
  'CHECK (profile_type IN (', @v3_profile_types, '))'
);
PREPARE v3_event_profile_type_check_stmt FROM @v3_event_profile_type_check_sql;
EXECUTE v3_event_profile_type_check_stmt;
DEALLOCATE PREPARE v3_event_profile_type_check_stmt;

SET @v3_close_snapshot_profile_check_sql := CONCAT(
  'ALTER TABLE stock_close_account_snapshot ',
  'ADD CONSTRAINT chk_stock_close_account_snapshot_profile_type ',
  'CHECK (participant_profile_type IS NULL OR participant_profile_type IN (',
  @v3_profile_types, '))'
);
PREPARE v3_close_snapshot_profile_check_stmt
  FROM @v3_close_snapshot_profile_check_sql;
EXECUTE v3_close_snapshot_profile_check_stmt;
DEALLOCATE PREPARE v3_close_snapshot_profile_check_stmt;

-- stock_order is the largest table. Add every V3 column and both rewritten
-- checks in one ALTER so MySQL performs at most one table copy during cutover.
SET @v3_order_cutover_clauses := '';
SET @v3_order_cutover_clauses := IF(
  EXISTS (
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'stock_order'
       AND column_name = 'auto_policy_version'
  ),
  @v3_order_cutover_clauses,
  'ADD COLUMN auto_policy_version BIGINT NULL AFTER auto_behavior_model_version'
);
SET @v3_order_cutover_clauses := IF(
  EXISTS (
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'stock_order'
       AND column_name = 'auto_behavior_event_sequence'
  ),
  @v3_order_cutover_clauses,
  CONCAT_WS(', ', NULLIF(@v3_order_cutover_clauses, ''),
    'ADD COLUMN auto_behavior_event_sequence BIGINT NULL AFTER auto_policy_version')
);
SET @v3_order_cutover_clauses := IF(
  EXISTS (
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'stock_order'
       AND column_name = 'decision_urgency'
  ),
  @v3_order_cutover_clauses,
  CONCAT_WS(', ', NULLIF(@v3_order_cutover_clauses, ''),
    'ADD COLUMN decision_urgency VARCHAR(30) NULL AFTER auto_behavior_event_sequence')
);
SET @v3_order_cutover_clauses := IF(
  EXISTS (
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'stock_order'
       AND column_name = 'cancel_reason'
  ),
  @v3_order_cutover_clauses,
  CONCAT_WS(', ', NULLIF(@v3_order_cutover_clauses, ''),
    'ADD COLUMN cancel_reason VARCHAR(40) NULL AFTER expires_at')
);
SET @v3_order_cutover_clauses := CONCAT_WS(
  ', ',
  NULLIF(@v3_order_cutover_clauses, ''),
  CONCAT(
    'ADD CONSTRAINT chk_stock_order_auto_profile_type ',
    'CHECK (auto_profile_type IS NULL OR auto_profile_type IN (',
    @v3_profile_types, '))'
  ),
  'ADD CONSTRAINT chk_stock_order_auto_behavior_model CHECK (
    auto_behavior_model_version IS NULL OR auto_behavior_model_version = ''V3''
  )'
);
SET @v3_order_cutover_sql := CONCAT(
  'ALTER TABLE stock_order ',
  @v3_order_cutover_clauses
);
PREPARE v3_order_cutover_stmt FROM @v3_order_cutover_sql;
EXECUTE v3_order_cutover_stmt;
DEALLOCATE PREPARE v3_order_cutover_stmt;

ALTER TABLE stock_auto_participant_profile_config
  ADD CONSTRAINT chk_stock_auto_profile_behavior_model CHECK (
    behavior_model_version = 'V3'
  );

SET @v3_participant_model_column_sql := IF(
  EXISTS (
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_auto_participant'
       AND column_name = 'behavior_model_version'
  ),
  'ALTER TABLE stock_auto_participant DROP COLUMN behavior_model_version',
  'SELECT 1'
);
PREPARE v3_participant_model_column_stmt FROM @v3_participant_model_column_sql;
EXECUTE v3_participant_model_column_stmt;
DEALLOCATE PREPARE v3_participant_model_column_stmt;

CREATE TABLE IF NOT EXISTS stock_auto_participant_policy_revision (
  policy_version BIGINT NOT NULL AUTO_INCREMENT,
  status VARCHAR(20) NOT NULL,
  effective_trade_date DATE NULL,
  runtime_enabled BIT NOT NULL DEFAULT b'1',
  runtime_change_reason VARCHAR(200) NULL,
  runtime_changed_by VARCHAR(64) NULL,
  runtime_changed_at DATETIME NULL,
  policy_json LONGTEXT NOT NULL,
  created_by VARCHAR(64) NOT NULL,
  created_at DATETIME NOT NULL,
  activated_at DATETIME NULL,
  retired_at DATETIME NULL,
  PRIMARY KEY (policy_version),
  KEY idx_stock_auto_policy_status_effective (
    status, effective_trade_date, policy_version
  ),
  CONSTRAINT chk_stock_auto_policy_status CHECK (
    status IN ('DRAFT', 'SCHEDULED', 'ACTIVE', 'RETIRED')
  ),
  CONSTRAINT chk_stock_auto_policy_effective_date CHECK (
    (status = 'DRAFT' AND effective_trade_date IS NULL)
    OR (status <> 'DRAFT' AND effective_trade_date IS NOT NULL)
  ),
  CONSTRAINT chk_stock_auto_policy_activation_time CHECK (
    (status IN ('DRAFT', 'SCHEDULED') AND activated_at IS NULL)
    OR (status IN ('ACTIVE', 'RETIRED') AND activated_at IS NOT NULL)
  ),
  CONSTRAINT chk_stock_auto_policy_retired_time CHECK (
    (status <> 'RETIRED' AND retired_at IS NULL)
    OR (status = 'RETIRED' AND retired_at IS NOT NULL)
  ),
  CONSTRAINT chk_stock_auto_policy_json CHECK (JSON_VALID(policy_json)),
  CONSTRAINT chk_stock_auto_policy_runtime_audit CHECK (
    (
      runtime_change_reason IS NULL
      AND runtime_changed_by IS NULL
      AND runtime_changed_at IS NULL
    )
    OR (
      runtime_change_reason <> ''
      AND runtime_changed_by <> ''
      AND runtime_changed_at IS NOT NULL
    )
  )
);

-- Keep the cutover idempotent when a policy table was created by an earlier
-- pre-release V3 build before the runtime audit fields were introduced.
SET @v3_runtime_reason_column_sql := IF(
    EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = DATABASE()
           AND table_name = 'stock_auto_participant_policy_revision'
           AND column_name = 'runtime_change_reason'
    ),
    'SELECT 1',
    'ALTER TABLE stock_auto_participant_policy_revision
       ADD COLUMN runtime_change_reason VARCHAR(200) NULL AFTER runtime_enabled'
);
PREPARE v3_runtime_reason_column_stmt FROM @v3_runtime_reason_column_sql;
EXECUTE v3_runtime_reason_column_stmt;
DEALLOCATE PREPARE v3_runtime_reason_column_stmt;

SET @v3_runtime_actor_column_sql := IF(
    EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = DATABASE()
           AND table_name = 'stock_auto_participant_policy_revision'
           AND column_name = 'runtime_changed_by'
    ),
    'SELECT 1',
    'ALTER TABLE stock_auto_participant_policy_revision
       ADD COLUMN runtime_changed_by VARCHAR(64) NULL AFTER runtime_change_reason'
);
PREPARE v3_runtime_actor_column_stmt FROM @v3_runtime_actor_column_sql;
EXECUTE v3_runtime_actor_column_stmt;
DEALLOCATE PREPARE v3_runtime_actor_column_stmt;

SET @v3_runtime_changed_at_column_sql := IF(
    EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = DATABASE()
           AND table_name = 'stock_auto_participant_policy_revision'
           AND column_name = 'runtime_changed_at'
    ),
    'SELECT 1',
    'ALTER TABLE stock_auto_participant_policy_revision
       ADD COLUMN runtime_changed_at DATETIME NULL AFTER runtime_changed_by'
);
PREPARE v3_runtime_changed_at_column_stmt FROM @v3_runtime_changed_at_column_sql;
EXECUTE v3_runtime_changed_at_column_stmt;
DEALLOCATE PREPARE v3_runtime_changed_at_column_stmt;

SET @v3_runtime_audit_check_sql := IF(
    EXISTS (
        SELECT 1
          FROM information_schema.table_constraints
         WHERE constraint_schema = DATABASE()
           AND table_name = 'stock_auto_participant_policy_revision'
           AND constraint_name = 'chk_stock_auto_policy_runtime_audit'
    ),
    'SELECT 1',
    'ALTER TABLE stock_auto_participant_policy_revision
       ADD CONSTRAINT chk_stock_auto_policy_runtime_audit CHECK (
         (
           runtime_change_reason IS NULL
           AND runtime_changed_by IS NULL
           AND runtime_changed_at IS NULL
         )
         OR (
           runtime_change_reason <> ''''
           AND runtime_changed_by <> ''''
           AND runtime_changed_at IS NOT NULL
         )
       )'
);
PREPARE v3_runtime_audit_check_stmt FROM @v3_runtime_audit_check_sql;
EXECUTE v3_runtime_audit_check_stmt;
DEALLOCATE PREPARE v3_runtime_audit_check_stmt;

INSERT INTO stock_auto_participant_policy_revision(
    status, effective_trade_date, runtime_enabled,
    policy_json, created_by, created_at, activated_at, retired_at
)
SELECT
    'ACTIVE', '1970-01-01', b'1',
    '{"model":"V3","executionIntercept":-0.35,"signalSensitivity":1.70,"fatigueSensitivity":1.15,"fatigueHalfLifeSeconds":2700,"reentryTauSeconds":180,"ordinaryQuantityGamma":3.0,"rareLargeOrderProbability":0.025}',
    'SYSTEM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL
WHERE NOT EXISTS (
    SELECT 1
      FROM stock_auto_participant_policy_revision
     WHERE status = 'ACTIVE'
);

CREATE TABLE IF NOT EXISTS stock_auto_participant_daily_behavior_state (
  simulation_trade_date DATE NOT NULL,
  account_id BIGINT NOT NULL,
  user_key VARCHAR(64) NOT NULL,
  profile_type VARCHAR(40) NOT NULL,
  policy_version BIGINT NOT NULL,
  participant_config_version BIGINT NOT NULL,
  activity_state VARCHAR(20) NOT NULL,
  activity_session VARCHAR(20) NOT NULL,
  daily_seed BIGINT NOT NULL,
  event_sequence BIGINT NOT NULL DEFAULT 0,
  fatigue_score DECIMAL(12,6) NOT NULL DEFAULT 0.000000,
  fatigue_updated_at DATETIME NOT NULL,
  submitted_order_count BIGINT NOT NULL DEFAULT 0,
  submitted_notional DECIMAL(24,2) NOT NULL DEFAULT 0.00,
  observed_execution_count BIGINT NOT NULL DEFAULT 0,
  observed_execution_notional DECIMAL(24,2) NOT NULL DEFAULT 0.00,
  observed_cancel_count BIGINT NOT NULL DEFAULT 0,
  last_attention_at DATETIME NULL,
  last_decision_at DATETIME NULL,
  last_order_at DATETIME NULL,
  last_result_reason VARCHAR(50) NULL,
  last_hold_reason VARCHAR(50) NULL,
  recovery_factor DECIMAL(12,8) NOT NULL DEFAULT 0.00000000,
  optimistic_version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (simulation_trade_date, account_id),
  KEY idx_stock_auto_behavior_account_date (account_id, simulation_trade_date),
  KEY idx_stock_auto_behavior_policy_date (
    policy_version, simulation_trade_date, account_id
  ),
  KEY idx_stock_auto_behavior_state_date (
    activity_state, simulation_trade_date, account_id
  ),
  CONSTRAINT chk_stock_auto_behavior_activity_state CHECK (
    activity_state IN ('OFFLINE', 'LOW', 'NORMAL', 'HIGH')
  ),
  CONSTRAINT chk_stock_auto_behavior_session CHECK (
    activity_session IN ('OPENING', 'MIDDAY', 'CLOSING', 'RANDOM')
  ),
  CONSTRAINT chk_stock_auto_behavior_values CHECK (
    policy_version > 0
    AND participant_config_version > 0
    AND event_sequence >= 0
    AND fatigue_score >= 0
    AND submitted_order_count >= 0
    AND submitted_notional >= 0
    AND observed_execution_count >= 0
    AND observed_execution_notional >= 0
    AND observed_cancel_count >= 0
    AND recovery_factor BETWEEN 0 AND 1
    AND optimistic_version >= 0
  )
);

-- The schedule is a rebuildable lease projection, not a ledger. Recreate it at the
-- V3 cutover so no V1/V2 due time or lease can survive into the first V3 session.
DROP TABLE IF EXISTS stock_auto_participant_order_schedule;

CREATE TABLE stock_auto_participant_order_schedule (
  account_id BIGINT NOT NULL,
  user_key VARCHAR(64) NOT NULL,
  profile_type VARCHAR(40) NOT NULL,
  behavior_model_version VARCHAR(20) NOT NULL DEFAULT 'V3',
  simulation_trade_date DATE NOT NULL,
  next_attention_at DATETIME NULL,
  next_guard_at DATETIME NOT NULL,
  next_run_at DATETIME NOT NULL,
  last_run_at DATETIME NULL,
  lease_until DATETIME NULL,
  lease_owner VARCHAR(80) NULL,
  priority INT NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (account_id),
  UNIQUE KEY uk_stock_auto_order_schedule_user (user_key),
  KEY idx_stock_auto_order_schedule_due (
    next_run_at, lease_until, priority, account_id
  ),
  KEY idx_stock_auto_order_schedule_profile_due (
    profile_type, next_run_at, account_id
  ),
  KEY idx_stock_auto_order_schedule_trade_date (
    simulation_trade_date, next_run_at, account_id
  ),
  CONSTRAINT chk_stock_auto_order_schedule_model CHECK (
    behavior_model_version = 'V3'
  ),
  CONSTRAINT chk_stock_auto_order_schedule_next_run CHECK (
    next_run_at = LEAST(COALESCE(next_attention_at, next_guard_at), next_guard_at)
  ),
  CONSTRAINT chk_stock_auto_order_schedule_priority CHECK (
    priority BETWEEN 1 AND 100
  )
);

CREATE TABLE IF NOT EXISTS stock_auto_participant_liquidation_plan (
  simulation_trade_date DATE NOT NULL,
  account_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  urgency VARCHAR(30) NOT NULL,
  trigger_reason VARCHAR(50) NOT NULL,
  status VARCHAR(20) NOT NULL,
  target_quantity BIGINT NOT NULL,
  submitted_quantity BIGINT NOT NULL DEFAULT 0,
  remaining_quantity BIGINT NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME NULL,
  last_error VARCHAR(120) NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (simulation_trade_date, account_id, symbol, urgency),
  KEY idx_stock_auto_liquidation_retry (
    status, next_retry_at, account_id, symbol
  ),
  CONSTRAINT chk_stock_auto_liquidation_urgency CHECK (
    urgency IN ('RISK_REDUCTION', 'MANDATORY_CLOSE')
  ),
  CONSTRAINT chk_stock_auto_liquidation_status CHECK (
    status IN ('PENDING', 'SUBMITTED', 'COMPLETED', 'INCOMPLETE')
  ),
  CONSTRAINT chk_stock_auto_liquidation_quantity CHECK (
    target_quantity > 0
    AND submitted_quantity >= 0
    AND remaining_quantity >= 0
    AND attempt_count >= 0
  )
);

SET @v3_order_columns := '';
SET @v3_order_columns := IF(
  EXISTS (
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'stock_order'
       AND column_name = 'auto_policy_version'
  ),
  @v3_order_columns,
  'ADD COLUMN auto_policy_version BIGINT NULL AFTER auto_behavior_model_version'
);
SET @v3_order_columns := IF(
  EXISTS (
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'stock_order'
       AND column_name = 'auto_behavior_event_sequence'
  ),
  @v3_order_columns,
  CONCAT_WS(', ', NULLIF(@v3_order_columns, ''),
    'ADD COLUMN auto_behavior_event_sequence BIGINT NULL AFTER auto_policy_version')
);
SET @v3_order_columns := IF(
  EXISTS (
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'stock_order'
       AND column_name = 'decision_urgency'
  ),
  @v3_order_columns,
  CONCAT_WS(', ', NULLIF(@v3_order_columns, ''),
    'ADD COLUMN decision_urgency VARCHAR(30) NULL AFTER auto_behavior_event_sequence')
);
SET @v3_order_columns := IF(
  EXISTS (
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'stock_order'
       AND column_name = 'cancel_reason'
  ),
  @v3_order_columns,
  CONCAT_WS(', ', NULLIF(@v3_order_columns, ''),
    'ADD COLUMN cancel_reason VARCHAR(40) NULL AFTER expires_at')
);
SET @v3_order_alter_sql := IF(
  @v3_order_columns = '',
  'SELECT 1',
  CONCAT('ALTER TABLE stock_order ', @v3_order_columns)
);
PREPARE v3_order_alter_stmt FROM @v3_order_alter_sql;
EXECUTE v3_order_alter_stmt;
DEALLOCATE PREPARE v3_order_alter_stmt;

SET @institution_intent_status_check_sql := IF(
  EXISTS (
    SELECT 1 FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE()
       AND table_name = 'stock_institution_order_intent'
       AND constraint_name = 'chk_stock_institution_order_intent_status'
  ),
  'ALTER TABLE stock_institution_order_intent
     DROP CHECK chk_stock_institution_order_intent_status',
  'SELECT 1'
);
PREPARE institution_intent_status_check_stmt
  FROM @institution_intent_status_check_sql;
EXECUTE institution_intent_status_check_stmt;
DEALLOCATE PREPARE institution_intent_status_check_stmt;

SET @institution_intent_submission_check_sql := IF(
  EXISTS (
    SELECT 1 FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE()
       AND table_name = 'stock_institution_order_intent'
       AND constraint_name = 'chk_stock_institution_order_intent_submission'
  ),
  'ALTER TABLE stock_institution_order_intent
     DROP CHECK chk_stock_institution_order_intent_submission',
  'SELECT 1'
);
PREPARE institution_intent_submission_check_stmt
  FROM @institution_intent_submission_check_sql;
EXECUTE institution_intent_submission_check_stmt;
DEALLOCATE PREPARE institution_intent_submission_check_stmt;

ALTER TABLE stock_institution_order_intent
  ADD CONSTRAINT chk_stock_institution_order_intent_status CHECK (
    status IN (
      'PENDING', 'SUBMITTED', 'COMPLETED',
      'CANCELLED', 'REJECTED', 'FAILED'
    )
  ),
  ADD CONSTRAINT chk_stock_institution_order_intent_submission CHECK (
    (
      status IN ('SUBMITTED', 'COMPLETED', 'CANCELLED')
      AND submitted_order_id IS NOT NULL
      AND submitted_price > 0
      AND submitted_quantity > 0
      AND submitted_at IS NOT NULL
    )
    OR (
      status NOT IN ('SUBMITTED', 'COMPLETED', 'CANCELLED')
      AND submitted_order_id IS NULL
      AND submitted_price IS NULL
      AND submitted_quantity = 0
      AND submitted_at IS NULL
    )
  );

ALTER TABLE stock_institution_portfolio
  ALTER COLUMN daily_turnover_limit_rate SET DEFAULT 0.050000,
  ALTER COLUMN max_decision_turnover_rate SET DEFAULT 0.020000;

ALTER TABLE stock_institution_symbol_mandate
  ALTER COLUMN daily_participation_rate SET DEFAULT 0.050000;

UPDATE stock_auto_participant_profile_config
   SET pricing_mode = 'DIRECTIONAL'
 WHERE pricing_mode IS NULL OR pricing_mode <> 'DIRECTIONAL';

SET @v3_profile_pricing_check_sql := IF(
  EXISTS (
    SELECT 1 FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE()
       AND table_name = 'stock_auto_participant_profile_config'
       AND constraint_name = 'chk_stock_auto_profile_pricing_mode'
  ),
  'ALTER TABLE stock_auto_participant_profile_config
     DROP CHECK chk_stock_auto_profile_pricing_mode',
  'SELECT 1'
);
PREPARE v3_profile_pricing_check_stmt FROM @v3_profile_pricing_check_sql;
EXECUTE v3_profile_pricing_check_stmt;
DEALLOCATE PREPARE v3_profile_pricing_check_stmt;

ALTER TABLE stock_auto_participant_profile_config
  ADD CONSTRAINT chk_stock_auto_profile_pricing_mode CHECK (
    pricing_mode = 'DIRECTIONAL'
  );

UPDATE stock_auto_participant_profile_config
   SET inventory_mode = 'SIGNAL_DRIVEN'
 WHERE inventory_mode IS NULL OR inventory_mode <> 'SIGNAL_DRIVEN';

SET @v3_profile_inventory_check_sql := IF(
    EXISTS (
      SELECT 1
        FROM information_schema.table_constraints
       WHERE constraint_schema = DATABASE()
         AND table_name = 'stock_auto_participant_profile_config'
         AND constraint_name = 'chk_stock_auto_profile_inventory_mode'
    ),
    'ALTER TABLE stock_auto_participant_profile_config
     DROP CHECK chk_stock_auto_profile_inventory_mode',
    'SELECT 1'
);
PREPARE v3_profile_inventory_check_stmt FROM @v3_profile_inventory_check_sql;
EXECUTE v3_profile_inventory_check_stmt;
DEALLOCATE PREPARE v3_profile_inventory_check_stmt;

ALTER TABLE stock_auto_participant_profile_config
  ADD CONSTRAINT chk_stock_auto_profile_inventory_mode CHECK (
    inventory_mode = 'SIGNAL_DRIVEN'
  );
