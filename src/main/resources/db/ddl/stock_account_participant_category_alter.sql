USE STOCK_SERVICE;

-- Canonicalize the live participant role on the small stock_account table.
-- This migration never alters or scans stock_order or stock_execution.
SET @stock_account_participant_category_sql = (
  SELECT CASE
           WHEN COUNT(*) = 0 THEN
             'ALTER TABLE stock_account ADD COLUMN participant_category VARCHAR(30) NULL AFTER status'
           ELSE 'SELECT 1'
         END
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_account'
     AND column_name = 'participant_category'
);
PREPARE stock_account_participant_category_statement FROM @stock_account_participant_category_sql;
EXECUTE stock_account_participant_category_statement;
DEALLOCATE PREPARE stock_account_participant_category_statement;

UPDATE stock_account account
   SET participant_category = CASE
         WHEN account.user_key LIKE 'stock-listing-%' THEN 'LISTING_UNDERWRITER'
         WHEN EXISTS (
              SELECT 1
                FROM stock_auto_participant participant
               WHERE participant.user_key = account.user_key
         ) THEN 'AUTO_PARTICIPANT'
         ELSE 'MANUAL_PARTICIPANT'
       END
 WHERE participant_category IS NULL;

SET @stock_account_participant_category_sql = (
  SELECT CASE
           WHEN COUNT(*) = 1 THEN
             'ALTER TABLE stock_account MODIFY COLUMN participant_category VARCHAR(30) NOT NULL DEFAULT ''MANUAL_PARTICIPANT'' AFTER status'
           ELSE 'SELECT 1'
         END
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_account'
     AND column_name = 'participant_category'
     AND is_nullable = 'YES'
);
PREPARE stock_account_participant_category_statement FROM @stock_account_participant_category_sql;
EXECUTE stock_account_participant_category_statement;
DEALLOCATE PREPARE stock_account_participant_category_statement;

SET @stock_account_participant_category_sql = (
  SELECT CASE
           WHEN COUNT(*) = 0 THEN
             'ALTER TABLE stock_account ADD CONSTRAINT chk_stock_account_participant_category CHECK (CASE participant_category WHEN ''MANUAL_PARTICIPANT'' THEN 1 WHEN ''AUTO_PARTICIPANT'' THEN 1 WHEN ''LISTING_UNDERWRITER'' THEN 1 ELSE 0 END = 1)'
           ELSE 'SELECT 1'
         END
    FROM information_schema.table_constraints
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_account'
     AND constraint_name = 'chk_stock_account_participant_category'
);
PREPARE stock_account_participant_category_statement FROM @stock_account_participant_category_sql;
EXECUTE stock_account_participant_category_statement;
DEALLOCATE PREPARE stock_account_participant_category_statement;

SET @stock_account_participant_category_sql = (
  SELECT CASE
           WHEN COUNT(*) = 0 THEN
             'ALTER TABLE stock_account ADD INDEX idx_stock_account_status_participant_id (status, participant_category, id)'
           ELSE 'SELECT 1'
         END
    FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_account'
     AND index_name = 'idx_stock_account_status_participant_id'
);
PREPARE stock_account_participant_category_statement FROM @stock_account_participant_category_sql;
EXECUTE stock_account_participant_category_statement;
DEALLOCATE PREPARE stock_account_participant_category_statement;
