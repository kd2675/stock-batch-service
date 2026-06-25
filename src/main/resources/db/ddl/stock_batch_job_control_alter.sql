USE STOCK_SERVICE;

CREATE TABLE IF NOT EXISTS stock_batch_job_control (
  job_name VARCHAR(100) NOT NULL,
  runtime_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  updated_by VARCHAR(64) NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (job_name),
  CONSTRAINT chk_stock_batch_job_control_name CHECK (job_name <> '')
);

CREATE TABLE IF NOT EXISTS stock_batch_job_lock (
  job_name VARCHAR(100) NOT NULL,
  lock_owner VARCHAR(128) NOT NULL,
  locked_until DATETIME NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (job_name),
  KEY idx_stock_batch_job_lock_until (locked_until),
  CONSTRAINT chk_stock_batch_job_lock_name CHECK (job_name <> ''),
  CONSTRAINT chk_stock_batch_job_lock_owner CHECK (lock_owner <> '')
);
