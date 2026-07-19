USE STOCK_SERVICE;

CREATE TABLE IF NOT EXISTS stock_corporate_action_processing (
  id BIGINT NOT NULL AUTO_INCREMENT,
  action_id BIGINT NOT NULL,
  account_scope_key VARCHAR(40) NOT NULL DEFAULT 'ALL',
  action_phase VARCHAR(80) NOT NULL,
  effective_business_date DATE NOT NULL,
  status VARCHAR(20) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 1,
  processed_count INT NOT NULL DEFAULT 0,
  amount DECIMAL(19,2) NULL,
  quantity BIGINT NULL,
  ledger_reference_id VARCHAR(100) NULL,
  processed_at DATETIME NULL,
  last_error VARCHAR(1000) NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_corporate_action_processing_unit (
    action_id, account_scope_key, action_phase, effective_business_date
  ),
  KEY idx_stock_corporate_action_processing_status_date (
    status, effective_business_date, action_phase, action_id
  ),
  CONSTRAINT chk_stock_corporate_action_processing_scope CHECK (account_scope_key <> ''),
  CONSTRAINT chk_stock_corporate_action_processing_status CHECK (
    CASE `status` WHEN 'PENDING' THEN 1 WHEN 'COMPLETED' THEN 1 WHEN 'FAILED' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_corporate_action_processing_attempt CHECK (attempt_count > 0),
  CONSTRAINT chk_stock_corporate_action_processing_count CHECK (processed_count >= 0),
  CONSTRAINT chk_stock_corporate_action_processing_amount CHECK (amount IS NULL OR amount >= 0),
  CONSTRAINT chk_stock_corporate_action_processing_quantity CHECK (quantity IS NULL OR quantity >= 0)
);
