USE STOCK_SERVICE;

INSERT INTO stock_market_participant(
    participant_code, display_name, participant_type, status,
    self_trade_group_id, created_at, updated_at
)
SELECT
    'DEFAULT_LIQUIDITY_PROVIDER', '기본 유동성공급기관',
    'LIQUIDITY_PROVIDER', 'ACTIVE',
    'LIQUIDITY_PROVIDER:DEFAULT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1
      FROM stock_market_participant
     WHERE participant_code = 'DEFAULT_LIQUIDITY_PROVIDER'
);

CREATE TABLE IF NOT EXISTS stock_liquidity_transition (
  id BIGINT NOT NULL AUTO_INCREMENT,
  transition_key VARCHAR(120) NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  mandate_id BIGINT NOT NULL,
  participant_id BIGINT NOT NULL,
  liquidity_account_id BIGINT NOT NULL,
  source_account_id BIGINT NOT NULL,
  legacy_account_id BIGINT NULL,
  stage VARCHAR(30) NOT NULL DEFAULT 'LIVE_ACTIVE',
  reference_daily_volume BIGINT NOT NULL,
  seed_inventory_quantity BIGINT NOT NULL,
  seed_cash_amount DECIMAL(19,2) NOT NULL,
  effective_business_date DATE NOT NULL,
  legacy_disabled_at DATETIME NULL,
  activated_at DATETIME NULL,
  requested_by VARCHAR(64) NOT NULL,
  change_reason VARCHAR(500) NOT NULL,
  policy_version BIGINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_liquidity_transition_key (transition_key),
  UNIQUE KEY uk_stock_liquidity_transition_symbol (symbol),
  UNIQUE KEY uk_stock_liquidity_transition_mandate (mandate_id),
  UNIQUE KEY uk_stock_liquidity_transition_account (liquidity_account_id),
  KEY idx_stock_liquidity_transition_stage (
    stage, effective_business_date, symbol
  ),
  KEY idx_stock_liquidity_transition_source (
    source_account_id, symbol, id
  ),
  CONSTRAINT chk_stock_liquidity_transition_stage CHECK (
    CASE stage
      WHEN 'LIVE_ACTIVE' THEN 1
      WHEN 'SUSPENDED' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_liquidity_transition_seed CHECK (
    reference_daily_volume > 0
    AND seed_inventory_quantity > 0
    AND seed_cash_amount > 0
  ),
  CONSTRAINT chk_stock_liquidity_transition_activation CHECK (
    stage IN ('LIVE_ACTIVE', 'SUSPENDED')
    AND activated_at IS NOT NULL
  ),
  CONSTRAINT chk_stock_liquidity_transition_audit CHECK (
    transition_key <> ''
    AND requested_by <> ''
    AND change_reason <> ''
    AND policy_version > 0
  )
);

SET @has_security_allocation_reason_check := (
  SELECT COUNT(*)
    FROM information_schema.table_constraints
   WHERE constraint_schema = DATABASE()
     AND table_name = 'stock_security_allocation_ledger'
     AND constraint_name = 'chk_stock_security_allocation_reason'
     AND constraint_type = 'CHECK'
);
SET @security_allocation_reason_check_ready := (
  SELECT COUNT(*)
    FROM information_schema.table_constraints tc
    JOIN information_schema.check_constraints cc
      ON cc.constraint_schema = tc.constraint_schema
     AND cc.constraint_name = tc.constraint_name
   WHERE tc.constraint_schema = DATABASE()
     AND tc.table_name = 'stock_security_allocation_ledger'
     AND tc.constraint_name = 'chk_stock_security_allocation_reason'
     AND tc.constraint_type = 'CHECK'
     AND cc.check_clause LIKE '%LIQUIDITY_SEED_TRANSFER%'
);
SET @drop_security_allocation_reason_check := IF(
  @security_allocation_reason_check_ready = 0
    AND @has_security_allocation_reason_check > 0,
  'ALTER TABLE stock_security_allocation_ledger DROP CHECK chk_stock_security_allocation_reason',
  'SELECT 1'
);
PREPARE stock_liquidity_transition_stmt
  FROM @drop_security_allocation_reason_check;
EXECUTE stock_liquidity_transition_stmt;
DEALLOCATE PREPARE stock_liquidity_transition_stmt;

SET @add_security_allocation_reason_check := IF(
  @security_allocation_reason_check_ready = 0,
  'ALTER TABLE stock_security_allocation_ledger ADD CONSTRAINT chk_stock_security_allocation_reason CHECK (CASE allocation_reason WHEN ''INITIAL_FLOAT_CUSTODY'' THEN 1 WHEN ''INITIAL_FLOAT_UNDERWRITER'' THEN 1 WHEN ''INITIAL_LOCKED_CUSTODY'' THEN 1 WHEN ''PUBLIC_ALLOCATION'' THEN 1 WHEN ''UNSOLD_UNDERWRITING'' THEN 1 WHEN ''CORPORATE_ACTION_ALLOCATION'' THEN 1 WHEN ''LOCK_RELEASE'' THEN 1 WHEN ''LIQUIDITY_SEED_TRANSFER'' THEN 1 ELSE 0 END = 1)',
  'SELECT 1'
);
PREPARE stock_liquidity_transition_stmt
  FROM @add_security_allocation_reason_check;
EXECUTE stock_liquidity_transition_stmt;
DEALLOCATE PREPARE stock_liquidity_transition_stmt;
