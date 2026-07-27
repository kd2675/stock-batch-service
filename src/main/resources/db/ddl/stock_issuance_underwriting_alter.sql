-- Adds role-separated initial issuance, underwriting, and security-allocation audit.
-- Existing instruments and obsolete automatic-liquidity accounts are not rewritten.

USE STOCK_SERVICE;

INSERT INTO stock_market_participant(
    participant_code, display_name, participant_type, status,
    self_trade_group_id, created_at, updated_at
)
SELECT
    'DEFAULT_ISSUE_UNDERWRITER', '기본 인수기관', 'ISSUE_UNDERWRITER', 'ACTIVE',
    'ISSUE_UNDERWRITER:DEFAULT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1
      FROM stock_market_participant
     WHERE participant_code = 'DEFAULT_ISSUE_UNDERWRITER'
);

CREATE TABLE IF NOT EXISTS stock_underwriting_contract (
  id BIGINT NOT NULL AUTO_INCREMENT,
  contract_code VARCHAR(80) NOT NULL,
  corporate_action_id BIGINT NULL,
  symbol VARCHAR(20) NOT NULL,
  participant_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  total_issue_quantity BIGINT NOT NULL,
  tradable_allocation_quantity BIGINT NOT NULL,
  locked_allocation_quantity BIGINT NOT NULL,
  external_allocation_quantity BIGINT NOT NULL DEFAULT 0,
  underwritten_quantity BIGINT NOT NULL,
  issue_price DECIMAL(19,2) NOT NULL,
  underwriting_type VARCHAR(30) NOT NULL DEFAULT 'FIRM_COMMITMENT',
  stabilization_start_date DATE NULL,
  stabilization_end_date DATE NULL,
  stabilization_quantity_limit BIGINT NOT NULL DEFAULT 0,
  stabilization_amount_limit DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  status VARCHAR(20) NOT NULL DEFAULT 'ALLOCATED',
  policy_version BIGINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_underwriting_contract_code (contract_code),
  UNIQUE KEY uk_stock_underwriting_contract_action (corporate_action_id),
  KEY idx_stock_underwriting_contract_symbol (symbol, status, id),
  KEY idx_stock_underwriting_contract_participant (
    participant_id, status, symbol, id
  ),
  KEY idx_stock_underwriting_contract_account (account_id, status, symbol, id),
  CONSTRAINT chk_stock_underwriting_contract_quantity CHECK (
    total_issue_quantity > 0
    AND tradable_allocation_quantity > 0
    AND locked_allocation_quantity >= 0
    AND external_allocation_quantity >= 0
    AND underwritten_quantity >= 0
    AND tradable_allocation_quantity + locked_allocation_quantity = total_issue_quantity
    AND external_allocation_quantity + underwritten_quantity = tradable_allocation_quantity
  ),
  CONSTRAINT chk_stock_underwriting_contract_price CHECK (issue_price > 0),
  CONSTRAINT chk_stock_underwriting_contract_type CHECK (
    CASE underwriting_type
      WHEN 'FIRM_COMMITMENT' THEN 1
      WHEN 'BEST_EFFORTS' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_underwriting_contract_stabilization CHECK (
    stabilization_quantity_limit >= 0
    AND stabilization_amount_limit >= 0
    AND (
      stabilization_start_date IS NULL
      OR stabilization_end_date IS NULL
      OR stabilization_end_date >= stabilization_start_date
    )
  ),
  CONSTRAINT chk_stock_underwriting_contract_status CHECK (
    CASE status
      WHEN 'ALLOCATED' THEN 1
      WHEN 'STABILIZING' THEN 1
      WHEN 'COMPLETED' THEN 1
      WHEN 'CANCELLED' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_underwriting_contract_version CHECK (policy_version > 0)
);

CREATE TABLE IF NOT EXISTS stock_security_allocation_ledger (
  id BIGINT NOT NULL AUTO_INCREMENT,
  idempotency_key VARCHAR(120) NOT NULL,
  event_type VARCHAR(40) NOT NULL,
  corporate_action_id BIGINT NULL,
  underwriting_contract_id BIGINT NULL,
  source_account_id BIGINT NULL,
  destination_account_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  quantity BIGINT NOT NULL,
  unit_price DECIMAL(19,2) NOT NULL,
  allocation_reason VARCHAR(50) NOT NULL,
  tradability_status VARCHAR(20) NOT NULL,
  effective_business_date DATE NOT NULL,
  unlock_business_date DATE NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_security_allocation_idempotency (idempotency_key),
  KEY idx_stock_security_allocation_symbol (
    symbol, effective_business_date, id
  ),
  KEY idx_stock_security_allocation_destination (
    destination_account_id, symbol, effective_business_date, id
  ),
  KEY idx_stock_security_allocation_contract (
    underwriting_contract_id, id
  ),
  CONSTRAINT chk_stock_security_allocation_event CHECK (
    CASE event_type
      WHEN 'INITIAL_ISSUE' THEN 1
      WHEN 'CAPITAL_INCREASE' THEN 1
      WHEN 'LOCK_RELEASE' THEN 1
      WHEN 'MANUAL_REALLOCATION' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_security_allocation_amount CHECK (
    quantity > 0 AND unit_price >= 0
  ),
  CONSTRAINT chk_stock_security_allocation_reason CHECK (
    CASE allocation_reason
      WHEN 'INITIAL_FLOAT_CUSTODY' THEN 1
      WHEN 'INITIAL_FLOAT_UNDERWRITER' THEN 1
      WHEN 'INITIAL_LOCKED_CUSTODY' THEN 1
      WHEN 'PUBLIC_ALLOCATION' THEN 1
      WHEN 'UNSOLD_UNDERWRITING' THEN 1
      WHEN 'CORPORATE_ACTION_ALLOCATION' THEN 1
      WHEN 'LOCK_RELEASE' THEN 1
      WHEN 'LIQUIDITY_SEED_TRANSFER' THEN 1
      WHEN 'LIQUIDITY_ACCOUNT_TRANSFER' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_security_allocation_tradability CHECK (
    CASE tradability_status
      WHEN 'TRADABLE' THEN 1
      WHEN 'LOCKED' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_security_allocation_unlock CHECK (
    unlock_business_date IS NULL
    OR unlock_business_date >= effective_business_date
  )
);
