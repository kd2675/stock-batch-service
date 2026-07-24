USE STOCK_SERVICE;

SET @stock_order_expires_at_sql := IF(
    EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = DATABASE()
           AND table_name = 'stock_order'
           AND column_name = 'expires_at'
    ),
    'SELECT 1',
    'ALTER TABLE stock_order ADD COLUMN expires_at DATETIME NULL AFTER funding_budget_type, ALGORITHM=INSTANT'
);
PREPARE stock_order_expires_at_stmt FROM @stock_order_expires_at_sql;
EXECUTE stock_order_expires_at_stmt;
DEALLOCATE PREPARE stock_order_expires_at_stmt;

SET @stock_order_auto_profile_type_sql := IF(
    EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = DATABASE()
           AND table_name = 'stock_order'
           AND column_name = 'auto_profile_type'
    ),
    'SELECT 1',
    'ALTER TABLE stock_order ADD COLUMN auto_profile_type VARCHAR(40) NULL AFTER expires_at, ALGORITHM=INSTANT'
);
PREPARE stock_order_auto_profile_type_stmt FROM @stock_order_auto_profile_type_sql;
EXECUTE stock_order_auto_profile_type_stmt;
DEALLOCATE PREPARE stock_order_auto_profile_type_stmt;

SET @stock_order_auto_behavior_model_sql := IF(
    EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = DATABASE()
           AND table_name = 'stock_order'
           AND column_name = 'auto_behavior_model_version'
    ),
    'SELECT 1',
    'ALTER TABLE stock_order ADD COLUMN auto_behavior_model_version VARCHAR(20) NULL AFTER auto_profile_type, ALGORITHM=INSTANT'
);
PREPARE stock_order_auto_behavior_model_stmt FROM @stock_order_auto_behavior_model_sql;
EXECUTE stock_order_auto_behavior_model_stmt;
DEALLOCATE PREPARE stock_order_auto_behavior_model_stmt;

-- The hot-ledger CHECKs are added together by stock_auto_market_reprice_index_alter.sql
-- so this upgrade rebuilds stock_order at most once.
