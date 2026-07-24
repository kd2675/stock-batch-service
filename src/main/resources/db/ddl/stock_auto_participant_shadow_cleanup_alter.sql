USE STOCK_SERVICE;

SET @drop_funding_shadow_check_sql := IF(
    EXISTS (
        SELECT 1
          FROM information_schema.table_constraints
         WHERE constraint_schema = DATABASE()
           AND table_name = 'stock_auto_participant'
           AND constraint_name = 'chk_stock_auto_participant_funding_shadow'
    ),
    'ALTER TABLE stock_auto_participant DROP CHECK chk_stock_auto_participant_funding_shadow',
    'SELECT 1'
);
PREPARE drop_funding_shadow_check_stmt FROM @drop_funding_shadow_check_sql;
EXECUTE drop_funding_shadow_check_stmt;
DEALLOCATE PREPARE drop_funding_shadow_check_stmt;

SET @drop_rollout_pair_check_sql := IF(
    EXISTS (
        SELECT 1
          FROM information_schema.table_constraints
         WHERE constraint_schema = DATABASE()
           AND table_name = 'stock_auto_participant'
           AND constraint_name = 'chk_stock_auto_participant_behavior_rollout_pair'
    ),
    'ALTER TABLE stock_auto_participant DROP CHECK chk_stock_auto_participant_behavior_rollout_pair',
    'SELECT 1'
);
PREPARE drop_rollout_pair_check_stmt FROM @drop_rollout_pair_check_sql;
EXECUTE drop_rollout_pair_check_stmt;
DEALLOCATE PREPARE drop_rollout_pair_check_stmt;

SET @drop_evaluation_check_sql := IF(
    EXISTS (
        SELECT 1
          FROM information_schema.table_constraints
         WHERE constraint_schema = DATABASE()
           AND table_name = 'stock_auto_participant'
           AND constraint_name = 'chk_stock_auto_participant_behavior_evaluation'
    ),
    'ALTER TABLE stock_auto_participant DROP CHECK chk_stock_auto_participant_behavior_evaluation',
    'SELECT 1'
);
PREPARE drop_evaluation_check_stmt FROM @drop_evaluation_check_sql;
EXECUTE drop_evaluation_check_stmt;
DEALLOCATE PREPARE drop_evaluation_check_stmt;

-- SHADOW used V1 for actual orders. Preserve that effective behavior after
-- removing the legacy rollout checks that reject the V1/SHADOW transition row.
SET @shadow_to_v1_sql := IF(
    EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = DATABASE()
           AND table_name = 'stock_auto_participant'
           AND column_name = 'behavior_evaluation_mode'
    ),
    'UPDATE stock_auto_participant SET behavior_model_version = ''V1'' WHERE behavior_evaluation_mode = ''SHADOW''',
    'SELECT 1'
);
PREPARE shadow_to_v1_stmt FROM @shadow_to_v1_sql;
EXECUTE shadow_to_v1_stmt;
DEALLOCATE PREPARE shadow_to_v1_stmt;

SET @drop_evaluation_column_sql := IF(
    EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = DATABASE()
           AND table_name = 'stock_auto_participant'
           AND column_name = 'behavior_evaluation_mode'
    ),
    'ALTER TABLE stock_auto_participant DROP COLUMN behavior_evaluation_mode',
    'SELECT 1'
);
PREPARE drop_evaluation_column_stmt FROM @drop_evaluation_column_sql;
EXECUTE drop_evaluation_column_stmt;
DEALLOCATE PREPARE drop_evaluation_column_stmt;

DROP TABLE IF EXISTS stock_auto_profile_decision_day_summary;
