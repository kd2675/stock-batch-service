USE STOCK_SERVICE;

CREATE TABLE IF NOT EXISTS stock_auto_participant_cash_flow_run (
  run_key VARCHAR(160) NOT NULL,
  operation VARCHAR(20) NOT NULL,
  last_account_id BIGINT NOT NULL DEFAULT 0,
  processed_count BIGINT NOT NULL DEFAULT 0,
  completed_at DATETIME NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (run_key),
  KEY idx_stock_auto_participant_cash_flow_run_completed (completed_at, run_key),
  CONSTRAINT chk_stock_auto_participant_cash_flow_run_operation CHECK (
    CASE `operation` WHEN 'SCHEDULED' THEN 1 WHEN 'MANUAL' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_auto_participant_cash_flow_run_cursor CHECK (last_account_id >= 0),
  CONSTRAINT chk_stock_auto_participant_cash_flow_run_count CHECK (processed_count >= 0)
);
