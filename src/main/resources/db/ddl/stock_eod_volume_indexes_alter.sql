USE STOCK_SERVICE;

-- Apply after stock_eod_immutable_snapshot_alter.sql.
-- No statement in this migration alters stock_order or stock_execution. Control/snapshot indexes
-- affect only low-write EOD tables; the cash-flow and entitlement indexes affect low-frequency
-- cash/corporate ledgers and prevent every account-snapshot chunk from rescanning the same
-- watermark or entitlement set.
SET @stock_eod_volume_index_sql = (
  SELECT CASE
           WHEN COUNT(*) = 0 THEN
             'ALTER TABLE stock_close_open_order_snapshot ADD COLUMN source_order_status VARCHAR(20) NULL AFTER side'
           ELSE 'SELECT 1'
         END
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_close_open_order_snapshot'
     AND column_name = 'source_order_status'
);
PREPARE stock_eod_volume_index_statement FROM @stock_eod_volume_index_sql;
EXECUTE stock_eod_volume_index_statement;
DEALLOCATE PREPARE stock_eod_volume_index_statement;

-- The admin EOD page polls every 15 seconds, but it must remain isolated from order/execution
-- volume. These indexes cover only the low-write cycle and signal control tables.
SET @stock_eod_volume_index_sql = (
  SELECT CASE
           WHEN COUNT(*) = 0 THEN
             'ALTER TABLE stock_post_close_cycle ADD INDEX idx_stock_post_close_cycle_scope_status_date (scope_type, scope_key, status, business_date, id)'
           ELSE 'SELECT 1'
         END
    FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_post_close_cycle'
     AND index_name = 'idx_stock_post_close_cycle_scope_status_date'
);
PREPARE stock_eod_volume_index_statement FROM @stock_eod_volume_index_sql;
EXECUTE stock_eod_volume_index_statement;
DEALLOCATE PREPARE stock_eod_volume_index_statement;

SET @stock_eod_volume_index_sql = (
  SELECT CASE
           WHEN COUNT(*) = 0 THEN
             'ALTER TABLE stock_batch_job_signal ADD INDEX idx_stock_batch_job_signal_cycle_id (expected_cycle_id, id)'
           ELSE 'SELECT 1'
         END
    FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_batch_job_signal'
     AND index_name = 'idx_stock_batch_job_signal_cycle_id'
);
PREPARE stock_eod_volume_index_statement FROM @stock_eod_volume_index_sql;
EXECUTE stock_eod_volume_index_statement;
DEALLOCATE PREPARE stock_eod_volume_index_statement;

SET @stock_eod_volume_index_sql = (
  SELECT CASE
           WHEN COUNT(*) = 0 THEN
             'ALTER TABLE stock_account_cash_flow ADD INDEX idx_stock_account_cash_flow_account_id (account_id, id)'
           ELSE 'SELECT 1'
         END
    FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_account_cash_flow'
     AND index_name = 'idx_stock_account_cash_flow_account_id'
);
PREPARE stock_eod_volume_index_statement FROM @stock_eod_volume_index_sql;
EXECUTE stock_eod_volume_index_statement;
DEALLOCATE PREPARE stock_eod_volume_index_statement;

SET @stock_eod_volume_index_sql = (
  SELECT CASE
           WHEN COUNT(*) = 0 THEN
             'ALTER TABLE stock_corporate_action_entitlement ADD INDEX idx_stock_corporate_action_entitlement_account_status (account_id, status)'
           ELSE 'SELECT 1'
         END
    FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_corporate_action_entitlement'
     AND index_name = 'idx_stock_corporate_action_entitlement_account_status'
);
PREPARE stock_eod_volume_index_statement FROM @stock_eod_volume_index_sql;
EXECUTE stock_eod_volume_index_statement;
DEALLOCATE PREPARE stock_eod_volume_index_statement;

-- Existing completed snapshots do not use this cursor again. For an interrupted cycle, the
-- current open order status preserves the correct stream; already-cancelled legacy rows safely
-- fall back to PENDING because released_at is the authoritative refund checkpoint. Guard the
-- backfill itself so an idempotent re-run never scans the snapshot table or joins stock_order.
SET @stock_eod_volume_index_sql = (
  SELECT CASE
           WHEN COUNT(*) = 1 THEN
             'UPDATE stock_close_open_order_snapshot snapshot LEFT JOIN stock_order orders ON orders.id = snapshot.order_id SET snapshot.source_order_status = CASE WHEN orders.status = ''PARTIALLY_FILLED'' THEN ''PARTIALLY_FILLED'' ELSE ''PENDING'' END WHERE snapshot.source_order_status IS NULL'
           ELSE 'SELECT 1'
         END
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_close_open_order_snapshot'
     AND column_name = 'source_order_status'
     AND is_nullable = 'YES'
);
PREPARE stock_eod_volume_index_statement FROM @stock_eod_volume_index_sql;
EXECUTE stock_eod_volume_index_statement;
DEALLOCATE PREPARE stock_eod_volume_index_statement;

SET @stock_eod_volume_index_sql = (
  SELECT CASE
           WHEN COUNT(*) = 1 THEN
             'ALTER TABLE stock_close_open_order_snapshot MODIFY COLUMN source_order_status VARCHAR(20) NOT NULL AFTER side'
           ELSE 'SELECT 1'
         END
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_close_open_order_snapshot'
     AND column_name = 'source_order_status'
     AND is_nullable = 'YES'
);
PREPARE stock_eod_volume_index_statement FROM @stock_eod_volume_index_sql;
EXECUTE stock_eod_volume_index_statement;
DEALLOCATE PREPARE stock_eod_volume_index_statement;

SET @stock_eod_volume_index_sql = (
  SELECT CASE
           WHEN COUNT(*) = 0 THEN
             'ALTER TABLE stock_close_account_snapshot ADD INDEX idx_stock_close_account_snapshot_cycle_reconciliation (close_cycle_id, reconciliation_status, account_id)'
           ELSE 'SELECT 1'
         END
    FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_close_account_snapshot'
     AND index_name = 'idx_stock_close_account_snapshot_cycle_reconciliation'
);
PREPARE stock_eod_volume_index_statement FROM @stock_eod_volume_index_sql;
EXECUTE stock_eod_volume_index_statement;
DEALLOCATE PREPARE stock_eod_volume_index_statement;

SET @stock_eod_volume_index_sql = (
  SELECT CASE
           WHEN COUNT(*) = 0 THEN
             'ALTER TABLE stock_close_account_snapshot ADD INDEX idx_stock_close_account_snapshot_cycle_target (close_cycle_id, settlement_target, account_id)'
           ELSE 'SELECT 1'
         END
    FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_close_account_snapshot'
     AND index_name = 'idx_stock_close_account_snapshot_cycle_target'
);
PREPARE stock_eod_volume_index_statement FROM @stock_eod_volume_index_sql;
EXECUTE stock_eod_volume_index_statement;
DEALLOCATE PREPARE stock_eod_volume_index_statement;

SET @stock_eod_volume_index_sql = (
  SELECT CASE
           WHEN COUNT(*) = 0 THEN
             'ALTER TABLE stock_close_open_order_snapshot ADD INDEX idx_stock_close_open_order_snapshot_cycle_stream (close_cycle_id, symbol, source_order_status, order_id)'
           ELSE 'SELECT 1'
         END
    FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_close_open_order_snapshot'
     AND index_name = 'idx_stock_close_open_order_snapshot_cycle_stream'
);
PREPARE stock_eod_volume_index_statement FROM @stock_eod_volume_index_sql;
EXECUTE stock_eod_volume_index_statement;
DEALLOCATE PREPARE stock_eod_volume_index_statement;

SET @stock_eod_volume_index_sql = (
  SELECT CASE
           WHEN COUNT(*) = 0 THEN
             'ALTER TABLE stock_close_open_order_snapshot ADD CONSTRAINT chk_stock_close_open_order_snapshot_status CHECK (CASE `source_order_status` WHEN ''PENDING'' THEN 1 WHEN ''PARTIALLY_FILLED'' THEN 1 ELSE 0 END = 1)'
           ELSE 'SELECT 1'
         END
    FROM information_schema.table_constraints
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_close_open_order_snapshot'
     AND constraint_name = 'chk_stock_close_open_order_snapshot_status'
);
PREPARE stock_eod_volume_index_statement FROM @stock_eod_volume_index_sql;
EXECUTE stock_eod_volume_index_statement;
DEALLOCATE PREPARE stock_eod_volume_index_statement;

SET @stock_eod_volume_index_sql = (
  SELECT CASE
           WHEN COUNT(*) = 0 THEN
             'ALTER TABLE stock_close_open_order_snapshot ADD INDEX idx_stock_close_open_order_snapshot_cycle_release_order (close_cycle_id, released_at, order_id)'
           ELSE 'SELECT 1'
         END
    FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_close_open_order_snapshot'
     AND index_name = 'idx_stock_close_open_order_snapshot_cycle_release_order'
);
PREPARE stock_eod_volume_index_statement FROM @stock_eod_volume_index_sql;
EXECUTE stock_eod_volume_index_statement;
DEALLOCATE PREPARE stock_eod_volume_index_statement;
