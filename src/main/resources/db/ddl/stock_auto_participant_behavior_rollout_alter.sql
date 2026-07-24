USE STOCK_SERVICE;

SET @behavior_model_column_sql := IF(
    EXISTS (SELECT 1 FROM information_schema.columns
             WHERE table_schema = DATABASE()
               AND table_name = 'stock_auto_participant'
               AND column_name = 'behavior_model_version'),
    'SELECT 1',
    'ALTER TABLE stock_auto_participant ADD COLUMN behavior_model_version VARCHAR(20) NOT NULL DEFAULT ''V1'' AFTER profile_type'
);
PREPARE behavior_model_column_stmt FROM @behavior_model_column_sql;
EXECUTE behavior_model_column_stmt;
DEALLOCATE PREPARE behavior_model_column_stmt;

SET @behavior_seed_column_sql := IF(
    EXISTS (SELECT 1 FROM information_schema.columns
             WHERE table_schema = DATABASE()
               AND table_name = 'stock_auto_participant'
               AND column_name = 'behavior_seed'),
    'SELECT 1',
    'ALTER TABLE stock_auto_participant ADD COLUMN behavior_seed BIGINT NULL AFTER behavior_model_version'
);
PREPARE behavior_seed_column_stmt FROM @behavior_seed_column_sql;
EXECUTE behavior_seed_column_stmt;
DEALLOCATE PREPARE behavior_seed_column_stmt;

-- Existing participants receive the same SHA-256 first-60-bit seed used by
-- application creation and legacy NULL fallback. The stored value is immutable.
UPDATE stock_auto_participant
   SET behavior_seed = CAST(CONV(SUBSTRING(SHA2(user_key, 256), 1, 15), 16, 10) AS UNSIGNED)
 WHERE behavior_seed IS NULL;

SET @behavior_model_check_sql := IF(
    EXISTS (SELECT 1 FROM information_schema.table_constraints
             WHERE constraint_schema = DATABASE()
               AND table_name = 'stock_auto_participant'
               AND constraint_name = 'chk_stock_auto_participant_behavior_model'),
    'SELECT 1',
    'ALTER TABLE stock_auto_participant ADD CONSTRAINT chk_stock_auto_participant_behavior_model CHECK (CASE behavior_model_version WHEN ''V1'' THEN 1 WHEN ''V2'' THEN 1 ELSE 0 END = 1)'
);
PREPARE behavior_model_check_stmt FROM @behavior_model_check_sql;
EXECUTE behavior_model_check_stmt;
DEALLOCATE PREPARE behavior_model_check_stmt;
