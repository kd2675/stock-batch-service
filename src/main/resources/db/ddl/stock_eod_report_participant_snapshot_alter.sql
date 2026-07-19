USE STOCK_SERVICE;

-- Apply after stock_eod_immutable_snapshot_alter.sql.
-- Participant classification is frozen on the low-write close-account snapshot so the overnight
-- report never rejoins mutable account/profile configuration. This migration does not alter or
-- scan stock_order or stock_execution and must run only in the approved maintenance window.
SET @stock_eod_report_participant_sql = (
  SELECT CASE
           WHEN COUNT(*) = 0 THEN
             'ALTER TABLE stock_close_account_snapshot ADD COLUMN participant_category VARCHAR(30) NULL AFTER account_status'
           ELSE 'SELECT 1'
         END
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_close_account_snapshot'
     AND column_name = 'participant_category'
);
PREPARE stock_eod_report_participant_statement FROM @stock_eod_report_participant_sql;
EXECUTE stock_eod_report_participant_statement;
DEALLOCATE PREPARE stock_eod_report_participant_statement;

-- Legacy rows did not freeze this value. The best available one-time classification is derived
-- from the frozen user_key plus the current low-frequency participant registry. New close cycles
-- always write the category atomically with the account snapshot.
UPDATE stock_close_account_snapshot snapshot
   SET participant_category = CASE
         WHEN snapshot.user_key LIKE 'stock-listing-%' THEN 'LISTING_UNDERWRITER'
         WHEN EXISTS (
              SELECT 1
                FROM stock_auto_participant participant
               WHERE participant.user_key = snapshot.user_key
         ) THEN 'AUTO_PARTICIPANT'
         ELSE 'MANUAL_PARTICIPANT'
       END
 WHERE snapshot.participant_category IS NULL;

SET @stock_eod_report_participant_sql = (
  SELECT CASE
           WHEN COUNT(*) = 1 THEN
             'ALTER TABLE stock_close_account_snapshot MODIFY COLUMN participant_category VARCHAR(30) NOT NULL DEFAULT ''MANUAL_PARTICIPANT'' AFTER account_status'
           ELSE 'SELECT 1'
         END
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_close_account_snapshot'
     AND column_name = 'participant_category'
     AND is_nullable = 'YES'
);
PREPARE stock_eod_report_participant_statement FROM @stock_eod_report_participant_sql;
EXECUTE stock_eod_report_participant_statement;
DEALLOCATE PREPARE stock_eod_report_participant_statement;

SET @stock_eod_report_participant_sql = (
  SELECT CASE
           WHEN COUNT(*) = 0 THEN
             'ALTER TABLE stock_close_account_snapshot ADD CONSTRAINT chk_stock_close_account_snapshot_participant_category CHECK (CASE `participant_category` WHEN ''MANUAL_PARTICIPANT'' THEN 1 WHEN ''AUTO_PARTICIPANT'' THEN 1 WHEN ''LISTING_UNDERWRITER'' THEN 1 ELSE 0 END = 1)'
           ELSE 'SELECT 1'
         END
    FROM information_schema.table_constraints
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_close_account_snapshot'
     AND constraint_name = 'chk_stock_close_account_snapshot_participant_category'
);
PREPARE stock_eod_report_participant_statement FROM @stock_eod_report_participant_sql;
EXECUTE stock_eod_report_participant_statement;
DEALLOCATE PREPARE stock_eod_report_participant_statement;
