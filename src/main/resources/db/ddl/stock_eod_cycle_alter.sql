USE STOCK_SERVICE;

CREATE TABLE IF NOT EXISTS stock_post_close_cycle (
  id BIGINT NOT NULL AUTO_INCREMENT,
  business_date DATE NOT NULL,
  scope_type VARCHAR(20) NOT NULL,
  scope_key VARCHAR(40) NOT NULL,
  cycle_kind VARCHAR(20) NOT NULL DEFAULT 'TRADING',
  skip_reason VARCHAR(500) NULL,
  phase VARCHAR(60) NOT NULL DEFAULT 'OPEN',
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  phase_revision INT NOT NULL DEFAULT 1,
  version BIGINT NOT NULL DEFAULT 0,
  owner_id VARCHAR(128) NULL,
  lease_until DATETIME NULL,
  next_retry_at DATETIME NULL,
  close_run_id BIGINT NULL,
  settlement_eligible_at DATETIME NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  started_at DATETIME NULL,
  completed_at DATETIME NULL,
  last_error_code VARCHAR(80) NULL,
  last_error_message VARCHAR(1000) NULL,
  build_version VARCHAR(100) NULL,
  schema_version VARCHAR(100) NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_post_close_cycle_scope (business_date, scope_type, scope_key),
  KEY idx_stock_post_close_cycle_scope_date_status (scope_type, scope_key, business_date, status, id),
  KEY idx_stock_post_close_cycle_phase_status (phase, status, business_date, id),
  KEY idx_stock_post_close_cycle_lease (status, lease_until, business_date, id),
  KEY idx_stock_post_close_cycle_close_run (close_run_id),
  CONSTRAINT chk_stock_post_close_cycle_scope_type CHECK (
    CASE `scope_type` WHEN 'FULL_MARKET' THEN 1 WHEN 'SYMBOL' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_post_close_cycle_scope_key CHECK (scope_key <> ''),
  CONSTRAINT chk_stock_post_close_cycle_kind CHECK (
    CASE `cycle_kind` WHEN 'TRADING' THEN 1 WHEN 'SKIPPED' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_post_close_cycle_skip_reason CHECK (
    cycle_kind <> 'SKIPPED' OR skip_reason IS NOT NULL
  ),
  CONSTRAINT chk_stock_post_close_cycle_phase CHECK (
    CASE `phase`
      WHEN 'OPEN' THEN 1 WHEN 'CLOSE_REQUESTED' THEN 1 WHEN 'ORDER_ENTRY_CLOSED' THEN 1
      WHEN 'EXECUTION_DRAINED' THEN 1 WHEN 'LEDGER_FROZEN' THEN 1 WHEN 'PORTFOLIO_SETTLED' THEN 1
      WHEN 'OVERNIGHT_CASH_APPLIED' THEN 1 WHEN 'CORPORATE_CASH_APPLIED' THEN 1
      WHEN 'REPORTS_AGGREGATED' THEN 1 WHEN 'PREOPEN_SECURITY_TRANSFORMS_APPLIED' THEN 1
      WHEN 'MARKET_DATA_PREPARED' THEN 1 WHEN 'AUTO_MARKET_PREPARED' THEN 1
      WHEN 'READY_TO_OPEN' THEN 1 WHEN 'COMPLETED' THEN 1 ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_post_close_cycle_status CHECK (
    CASE `status` WHEN 'PENDING' THEN 1 WHEN 'RUNNING' THEN 1 WHEN 'DEFERRED' THEN 1 WHEN 'FAILED' THEN 1 WHEN 'COMPLETED' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_post_close_cycle_revision CHECK (phase_revision > 0),
  CONSTRAINT chk_stock_post_close_cycle_version CHECK (version >= 0),
  CONSTRAINT chk_stock_post_close_cycle_attempt_count CHECK (attempt_count >= 0)
);

SET @stock_eod_cycle_add_next_retry_at = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE stock_post_close_cycle ADD COLUMN next_retry_at DATETIME NULL AFTER lease_until',
    'SELECT 1'
  )
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_post_close_cycle'
     AND column_name = 'next_retry_at'
);
PREPARE stock_eod_cycle_add_next_retry_at_stmt FROM @stock_eod_cycle_add_next_retry_at;
EXECUTE stock_eod_cycle_add_next_retry_at_stmt;
DEALLOCATE PREPARE stock_eod_cycle_add_next_retry_at_stmt;

CREATE TABLE IF NOT EXISTS stock_post_close_phase_attempt (
  id BIGINT NOT NULL AUTO_INCREMENT,
  cycle_id BIGINT NOT NULL,
  phase VARCHAR(60) NOT NULL,
  attempt_no INT NOT NULL,
  batch_job_execution_id BIGINT NULL,
  owner_id VARCHAR(128) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
  started_at DATETIME NOT NULL,
  completed_at DATETIME NULL,
  error_code VARCHAR(80) NULL,
  error_message VARCHAR(1000) NULL,
  build_version VARCHAR(100) NULL,
  schema_version VARCHAR(100) NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_post_close_phase_attempt (cycle_id, phase, attempt_no),
  KEY idx_stock_post_close_phase_attempt_cycle_id (cycle_id, id),
  KEY idx_stock_post_close_phase_attempt_status (status, started_at, id),
  KEY idx_stock_post_close_phase_attempt_job (batch_job_execution_id),
  CONSTRAINT chk_stock_post_close_phase_attempt_no CHECK (attempt_no > 0),
  CONSTRAINT chk_stock_post_close_phase_attempt_owner CHECK (owner_id <> ''),
  CONSTRAINT chk_stock_post_close_phase_attempt_status CHECK (
    CASE `status` WHEN 'RUNNING' THEN 1 WHEN 'COMPLETED' THEN 1 WHEN 'FAILED' THEN 1 WHEN 'ABANDONED' THEN 1 ELSE 0 END = 1
  )
);

CREATE TABLE IF NOT EXISTS stock_post_close_readiness_check (
  close_cycle_id BIGINT NOT NULL,
  check_code VARCHAR(60) NOT NULL,
  display_order INT NOT NULL,
  check_status VARCHAR(20) NOT NULL,
  failure_count BIGINT NOT NULL DEFAULT 0,
  message VARCHAR(500) NULL,
  checked_at DATETIME NOT NULL,
  PRIMARY KEY (close_cycle_id, check_code),
  UNIQUE KEY uk_stock_post_close_readiness_order (close_cycle_id, display_order),
  CONSTRAINT chk_stock_post_close_readiness_order CHECK (display_order > 0),
  CONSTRAINT chk_stock_post_close_readiness_status CHECK (
    CASE `check_status` WHEN 'PASSED' THEN 1 WHEN 'FAILED' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_post_close_readiness_failure_count CHECK (failure_count >= 0)
);

-- Legacy close runs predate the immutable close-cycle input tables. Mark them complete
-- instead of presenting them as LEDGER_FROZEN work that the new settlement job could claim.
INSERT INTO stock_post_close_cycle(
    business_date, scope_type, scope_key, phase, status, phase_revision, version,
    owner_id, lease_until, close_run_id, settlement_eligible_at, attempt_count,
    started_at, completed_at, created_at, updated_at
)
SELECT latest.business_date,
       'FULL_MARKET',
       'ALL',
       'COMPLETED',
       'COMPLETED',
       1,
       0,
       NULL,
       NULL,
       latest.id,
       NULL,
       0,
       latest.closed_at,
       COALESCE(latest.completed_at, latest.closed_at),
       latest.created_at,
       NOW()
  FROM stock_market_close_run latest
  JOIN (
      SELECT business_date, MAX(id) AS id
        FROM stock_market_close_run
       WHERE symbol IS NULL
         AND status = 'COMPLETED'
       GROUP BY business_date
  ) selected ON selected.id = latest.id
ON DUPLICATE KEY UPDATE
  close_run_id = COALESCE(stock_post_close_cycle.close_run_id, VALUES(close_run_id)),
  updated_at = VALUES(updated_at);

INSERT INTO stock_post_close_cycle(
    business_date, scope_type, scope_key, phase, status, phase_revision, version,
    owner_id, lease_until, close_run_id, settlement_eligible_at, attempt_count,
    started_at, completed_at, created_at, updated_at
)
SELECT latest.business_date,
       'SYMBOL',
       latest.symbol,
       'COMPLETED',
       'COMPLETED',
       1,
       0,
       NULL,
       NULL,
       latest.id,
       NULL,
       0,
       latest.closed_at,
       COALESCE(latest.completed_at, latest.closed_at),
       latest.created_at,
       NOW()
  FROM stock_market_close_run latest
  JOIN (
      SELECT business_date, symbol, MAX(id) AS id
        FROM stock_market_close_run
       WHERE symbol IS NOT NULL
         AND status = 'COMPLETED'
       GROUP BY business_date, symbol
  ) selected ON selected.id = latest.id
ON DUPLICATE KEY UPDATE
  close_run_id = COALESCE(stock_post_close_cycle.close_run_id, VALUES(close_run_id)),
  updated_at = VALUES(updated_at);
