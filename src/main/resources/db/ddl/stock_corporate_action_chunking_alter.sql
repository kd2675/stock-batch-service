USE STOCK_SERVICE;

SET @corporate_action_entitlement_chunk_index_exists = (
  SELECT COUNT(*)
    FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_corporate_action_entitlement'
     AND index_name = 'idx_stock_corporate_action_entitlement_action_status_id'
);
SET @corporate_action_entitlement_chunk_index_sql = IF(
  @corporate_action_entitlement_chunk_index_exists = 0,
  'ALTER TABLE stock_corporate_action_entitlement ADD INDEX idx_stock_corporate_action_entitlement_action_status_id (action_id, status, id)',
  'SELECT 1'
);
PREPARE corporate_action_entitlement_chunk_index_stmt
  FROM @corporate_action_entitlement_chunk_index_sql;
EXECUTE corporate_action_entitlement_chunk_index_stmt;
DEALLOCATE PREPARE corporate_action_entitlement_chunk_index_stmt;
