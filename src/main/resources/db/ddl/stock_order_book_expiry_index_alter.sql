USE STOCK_SERVICE;

CREATE TABLE IF NOT EXISTS stock_auto_participant_order_schedule (
  user_key VARCHAR(64) NOT NULL,
  profile_type VARCHAR(40) NOT NULL,
  next_run_at DATETIME NOT NULL,
  last_run_at DATETIME NULL,
  lease_until DATETIME NULL,
  lease_owner VARCHAR(80) NULL,
  run_interval_seconds INT NOT NULL,
  priority INT NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (user_key),
  KEY idx_stock_auto_order_schedule_due (next_run_at, lease_until, priority, user_key),
  KEY idx_stock_auto_order_schedule_profile_due (profile_type, next_run_at, user_key),
  CONSTRAINT chk_stock_auto_order_schedule_interval CHECK (run_interval_seconds > 0),
  CONSTRAINT chk_stock_auto_order_schedule_priority CHECK (priority between 1 and 100)
);

CREATE TABLE IF NOT EXISTS stock_auto_participant_order_schedule_participant_migration (
  user_key VARCHAR(64) NOT NULL,
  profile_type VARCHAR(40) NOT NULL,
  next_run_at DATETIME NOT NULL,
  last_run_at DATETIME NULL,
  lease_until DATETIME NULL,
  lease_owner VARCHAR(80) NULL,
  run_interval_seconds INT NOT NULL,
  priority INT NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (user_key),
  KEY idx_stock_auto_order_schedule_due (next_run_at, lease_until, priority, user_key),
  KEY idx_stock_auto_order_schedule_profile_due (profile_type, next_run_at, user_key),
  CONSTRAINT chk_stock_auto_order_schedule_participant_migration_interval CHECK (run_interval_seconds > 0),
  CONSTRAINT chk_stock_auto_order_schedule_participant_migration_priority CHECK (priority between 1 and 100)
);

SET @stock_auto_order_schedule_symbol_column_exists := (
  SELECT COUNT(*)
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_auto_participant_order_schedule'
     AND column_name = 'symbol'
);

SET @stock_auto_order_schedule_migration_insert_sql := IF(
  @stock_auto_order_schedule_symbol_column_exists > 0,
  'INSERT INTO stock_auto_participant_order_schedule_participant_migration(
      user_key, profile_type, next_run_at, last_run_at, lease_until, lease_owner,
      run_interval_seconds, priority, created_at, updated_at
   )
   SELECT user_key,
          SUBSTRING_INDEX(GROUP_CONCAT(profile_type ORDER BY next_run_at ASC, priority DESC, symbol ASC), '','', 1) as profile_type,
          MIN(next_run_at) as next_run_at,
          MAX(last_run_at) as last_run_at,
          NULL as lease_until,
          NULL as lease_owner,
          CAST(SUBSTRING_INDEX(GROUP_CONCAT(run_interval_seconds ORDER BY next_run_at ASC, priority DESC, symbol ASC), '','', 1) AS UNSIGNED) as run_interval_seconds,
          MAX(priority) as priority,
          MIN(created_at) as created_at,
          MAX(updated_at) as updated_at
     FROM stock_auto_participant_order_schedule
    GROUP BY user_key
   ON DUPLICATE KEY UPDATE
          profile_type = VALUES(profile_type),
          next_run_at = LEAST(next_run_at, VALUES(next_run_at)),
          last_run_at = GREATEST(COALESCE(last_run_at, VALUES(last_run_at)), COALESCE(VALUES(last_run_at), last_run_at)),
          lease_until = NULL,
          lease_owner = NULL,
          run_interval_seconds = VALUES(run_interval_seconds),
          priority = GREATEST(priority, VALUES(priority)),
          updated_at = GREATEST(updated_at, VALUES(updated_at))',
  'SELECT 1'
);

PREPARE stock_auto_order_schedule_migration_insert_stmt FROM @stock_auto_order_schedule_migration_insert_sql;
EXECUTE stock_auto_order_schedule_migration_insert_stmt;
DEALLOCATE PREPARE stock_auto_order_schedule_migration_insert_stmt;

SET @stock_auto_order_schedule_drop_old_sql := IF(
  @stock_auto_order_schedule_symbol_column_exists > 0,
  'DROP TABLE stock_auto_participant_order_schedule',
  'SELECT 1'
);

PREPARE stock_auto_order_schedule_drop_old_stmt FROM @stock_auto_order_schedule_drop_old_sql;
EXECUTE stock_auto_order_schedule_drop_old_stmt;
DEALLOCATE PREPARE stock_auto_order_schedule_drop_old_stmt;

SET @stock_auto_order_schedule_rename_sql := IF(
  @stock_auto_order_schedule_symbol_column_exists > 0,
  'RENAME TABLE stock_auto_participant_order_schedule_participant_migration TO stock_auto_participant_order_schedule',
  'DROP TABLE IF EXISTS stock_auto_participant_order_schedule_participant_migration'
);

PREPARE stock_auto_order_schedule_rename_stmt FROM @stock_auto_order_schedule_rename_sql;
EXECUTE stock_auto_order_schedule_rename_stmt;
DEALLOCATE PREPARE stock_auto_order_schedule_rename_stmt;

SET @stock_auto_order_schedule_due_index_exists := (
  SELECT COUNT(*)
    FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_auto_participant_order_schedule'
     AND index_name = 'idx_stock_auto_order_schedule_due'
);

SET @stock_auto_order_schedule_due_index_sql := IF(
  @stock_auto_order_schedule_due_index_exists = 0,
  'ALTER TABLE stock_auto_participant_order_schedule ADD INDEX idx_stock_auto_order_schedule_due (next_run_at, lease_until, priority, user_key)',
  'SELECT 1'
);

PREPARE stock_auto_order_schedule_due_index_stmt FROM @stock_auto_order_schedule_due_index_sql;
EXECUTE stock_auto_order_schedule_due_index_stmt;
DEALLOCATE PREPARE stock_auto_order_schedule_due_index_stmt;

SET @stock_auto_order_schedule_profile_due_index_exists := (
  SELECT COUNT(*)
    FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_auto_participant_order_schedule'
     AND index_name = 'idx_stock_auto_order_schedule_profile_due'
);

SET @stock_auto_order_schedule_profile_due_index_sql := IF(
  @stock_auto_order_schedule_profile_due_index_exists = 0,
  'ALTER TABLE stock_auto_participant_order_schedule ADD INDEX idx_stock_auto_order_schedule_profile_due (profile_type, next_run_at, user_key)',
  'SELECT 1'
);

PREPARE stock_auto_order_schedule_profile_due_index_stmt FROM @stock_auto_order_schedule_profile_due_index_sql;
EXECUTE stock_auto_order_schedule_profile_due_index_stmt;
DEALLOCATE PREPARE stock_auto_order_schedule_profile_due_index_stmt;

SET @stock_order_book_expiry_index_exists := (
  SELECT COUNT(*)
    FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_order'
     AND index_name = 'idx_stock_order_order_book_expiry'
);

SET @stock_order_book_expiry_index_sql := IF(
  @stock_order_book_expiry_index_exists = 0,
  'ALTER TABLE stock_order ADD INDEX idx_stock_order_order_book_expiry (market_type, symbol, created_at, id, status, account_id)',
  'SELECT 1'
);

PREPARE stock_order_book_expiry_index_stmt FROM @stock_order_book_expiry_index_sql;
EXECUTE stock_order_book_expiry_index_stmt;
DEALLOCATE PREPARE stock_order_book_expiry_index_stmt;

SET @stock_order_status_symbol_index_exists := (
  SELECT COUNT(*)
    FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_order'
     AND index_name = 'idx_stock_order_status_symbol'
);

SET @stock_order_status_symbol_index_sql := IF(
  @stock_order_status_symbol_index_exists > 0,
  'ALTER TABLE stock_order DROP INDEX idx_stock_order_status_symbol',
  'SELECT 1'
);

PREPARE stock_order_status_symbol_index_stmt FROM @stock_order_status_symbol_index_sql;
EXECUTE stock_order_status_symbol_index_stmt;
DEALLOCATE PREPARE stock_order_status_symbol_index_stmt;
