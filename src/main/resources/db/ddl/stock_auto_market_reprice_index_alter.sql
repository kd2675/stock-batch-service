USE STOCK_SERVICE;

SET @stock_order_funding_budget_check_missing := NOT EXISTS (
    SELECT 1 FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE()
       AND table_name = 'stock_order'
       AND constraint_name = 'chk_stock_order_funding_budget_type'
);
SET @stock_order_auto_behavior_model_check_missing := NOT EXISTS (
    SELECT 1 FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE()
       AND table_name = 'stock_order'
       AND constraint_name = 'chk_stock_order_auto_behavior_model'
);
SET @stock_order_auto_profile_type_check_missing := NOT EXISTS (
    SELECT 1 FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE()
       AND table_name = 'stock_order'
       AND constraint_name = 'chk_stock_order_auto_profile_type'
);
SET @stock_order_auto_reprice_index_exists := EXISTS (
    SELECT 1 FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_order'
       AND index_name = 'idx_stock_order_auto_reprice'
);

SET @stock_order_v2_alter_clauses := '';
SET @stock_order_v2_alter_clauses := IF(
    @stock_order_funding_budget_check_missing,
    'ADD CONSTRAINT chk_stock_order_funding_budget_type CHECK (funding_budget_type IS NULL OR funding_budget_type IN (''PAYDAY'', ''DIVIDEND''))',
    @stock_order_v2_alter_clauses
);
SET @stock_order_v2_alter_clauses := IF(
    @stock_order_auto_behavior_model_check_missing,
    CONCAT_WS(', ', NULLIF(@stock_order_v2_alter_clauses, ''),
        'ADD CONSTRAINT chk_stock_order_auto_behavior_model CHECK (auto_behavior_model_version IS NULL OR auto_behavior_model_version IN (''V1'', ''V2''))'),
    @stock_order_v2_alter_clauses
);
SET @stock_order_v2_alter_clauses := IF(
    @stock_order_auto_profile_type_check_missing,
    CONCAT_WS(', ', NULLIF(@stock_order_v2_alter_clauses, ''),
        'ADD CONSTRAINT chk_stock_order_auto_profile_type CHECK (auto_profile_type IS NULL OR auto_profile_type IN (''NEWS_REACTIVE'', ''MOMENTUM_FOLLOWER'', ''CONTRARIAN'', ''LOSS_AVERSE'', ''OVERCONFIDENT'', ''HERD_FOLLOWER'', ''MARKET_MAKER'', ''NOISE_TRADER'', ''VALUE_ANCHOR'', ''SCALPER'', ''DAY_TRADER'', ''SWING_TRADER'', ''LONG_TERM_HOLDER'', ''PAYDAY_ACCUMULATOR'', ''DIVIDEND_REINVESTOR'', ''LIMIT_DOWN_TRAPPED'', ''AVERAGE_DOWN_BUYER'', ''STOP_LOSS_TRADER'', ''FOMO_BUYER'', ''PANIC_SELLER'', ''DIP_BUYER'', ''PROFIT_LOCKER'', ''LIQUIDITY_AVOIDANT'', ''CASH_DEFENSIVE'', ''WHALE'', ''SMALL_DIVERSIFIER'', ''OBSERVER''))'),
    @stock_order_v2_alter_clauses
);
SET @stock_order_v2_schema_sql := IF(
    @stock_order_v2_alter_clauses = '',
    'SELECT 1',
    CONCAT('ALTER TABLE stock_order ', @stock_order_v2_alter_clauses)
);
PREPARE stock_order_v2_schema_stmt FROM @stock_order_v2_schema_sql;
EXECUTE stock_order_v2_schema_stmt;
DEALLOCATE PREPARE stock_order_v2_schema_stmt;

-- The former eight-column reprice index amplified every stock_order write even when no V2
-- market maker was active. Reprice discovery now bounds active V2 accounts first and reuses
-- idx_stock_order_account_status_created, so remove the legacy hot-ledger index if it exists.
SET @stock_order_auto_reprice_cleanup_sql := IF(
    @stock_order_auto_reprice_index_exists,
    'ALTER TABLE stock_order DROP INDEX idx_stock_order_auto_reprice, ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1'
);
PREPARE stock_order_auto_reprice_cleanup_stmt FROM @stock_order_auto_reprice_cleanup_sql;
EXECUTE stock_order_auto_reprice_cleanup_stmt;
DEALLOCATE PREPARE stock_order_auto_reprice_cleanup_stmt;
