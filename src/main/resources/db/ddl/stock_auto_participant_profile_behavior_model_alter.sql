USE STOCK_SERVICE;

-- The behavior model is a profile policy. Participants keep only their deterministic
-- behavior seed, while each generated order keeps its immutable model snapshot.
SET @profile_behavior_model_column_sql := IF(
    EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = DATABASE()
           AND table_name = 'stock_auto_participant_profile_config'
           AND column_name = 'behavior_model_version'
    ),
    'SELECT 1',
    'ALTER TABLE stock_auto_participant_profile_config
       ADD COLUMN behavior_model_version VARCHAR(20) NOT NULL DEFAULT ''V2'' AFTER profile_type'
);
PREPARE profile_behavior_model_column_stmt FROM @profile_behavior_model_column_sql;
EXECUTE profile_behavior_model_column_stmt;
DEALLOCATE PREPARE profile_behavior_model_column_stmt;

-- This rollout intentionally moves every configured profile to V2.
UPDATE stock_auto_participant_profile_config
   SET behavior_model_version = 'V2'
 WHERE behavior_model_version <> 'V2';

SET @profile_behavior_model_check_sql := IF(
    EXISTS (
        SELECT 1
          FROM information_schema.table_constraints
         WHERE constraint_schema = DATABASE()
           AND table_name = 'stock_auto_participant_profile_config'
           AND constraint_name = 'chk_stock_auto_profile_behavior_model'
    ),
    'SELECT 1',
    'ALTER TABLE stock_auto_participant_profile_config
       ADD CONSTRAINT chk_stock_auto_profile_behavior_model
       CHECK (CASE behavior_model_version WHEN ''V1'' THEN 1 WHEN ''V2'' THEN 1 ELSE 0 END = 1)'
);
PREPARE profile_behavior_model_check_stmt FROM @profile_behavior_model_check_sql;
EXECUTE profile_behavior_model_check_stmt;
DEALLOCATE PREPARE profile_behavior_model_check_stmt;

SET @participant_behavior_model_check_sql := IF(
    EXISTS (
        SELECT 1
          FROM information_schema.table_constraints
         WHERE constraint_schema = DATABASE()
           AND table_name = 'stock_auto_participant'
           AND constraint_name = 'chk_stock_auto_participant_behavior_model'
    ),
    'ALTER TABLE stock_auto_participant DROP CHECK chk_stock_auto_participant_behavior_model',
    'SELECT 1'
);
PREPARE participant_behavior_model_check_stmt FROM @participant_behavior_model_check_sql;
EXECUTE participant_behavior_model_check_stmt;
DEALLOCATE PREPARE participant_behavior_model_check_stmt;

SET @participant_behavior_model_column_sql := IF(
    EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = DATABASE()
           AND table_name = 'stock_auto_participant'
           AND column_name = 'behavior_model_version'
    ),
    'ALTER TABLE stock_auto_participant DROP COLUMN behavior_model_version',
    'SELECT 1'
);
PREPARE participant_behavior_model_column_stmt FROM @participant_behavior_model_column_sql;
EXECUTE participant_behavior_model_column_stmt;
DEALLOCATE PREPARE participant_behavior_model_column_stmt;
