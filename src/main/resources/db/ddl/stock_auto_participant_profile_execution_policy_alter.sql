USE STOCK_SERVICE;

SET @profile_decision_frequency_existed := EXISTS (
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_auto_participant_profile_config'
       AND column_name = 'decision_frequency_multiplier'
);
SET @profile_decision_frequency_sql := IF(
    @profile_decision_frequency_existed,
    'SELECT 1',
    'ALTER TABLE stock_auto_participant_profile_config ADD COLUMN decision_frequency_multiplier DECIMAL(8,4) DEFAULT NULL AFTER order_multiplier'
);
PREPARE profile_decision_frequency_stmt FROM @profile_decision_frequency_sql;
EXECUTE profile_decision_frequency_stmt;
DEALLOCATE PREPARE profile_decision_frequency_stmt;
SET @profile_decision_frequency_backfill_sql :=
    'UPDATE stock_auto_participant_profile_config SET decision_frequency_multiplier = ROUND(LEAST(20.0000, GREATEST(order_multiplier, 0.2500) / GREATEST(order_ttl_multiplier, 0.2500)), 4) WHERE decision_frequency_multiplier IS NULL';
PREPARE profile_decision_frequency_backfill_stmt FROM @profile_decision_frequency_backfill_sql;
EXECUTE profile_decision_frequency_backfill_stmt;
DEALLOCATE PREPARE profile_decision_frequency_backfill_stmt;

SET @profile_orders_per_decision_existed := EXISTS (
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_auto_participant_profile_config'
       AND column_name = 'orders_per_decision_multiplier'
);
SET @profile_orders_per_decision_sql := IF(
    @profile_orders_per_decision_existed,
    'SELECT 1',
    'ALTER TABLE stock_auto_participant_profile_config ADD COLUMN orders_per_decision_multiplier DECIMAL(8,4) DEFAULT NULL AFTER decision_frequency_multiplier'
);
PREPARE profile_orders_per_decision_stmt FROM @profile_orders_per_decision_sql;
EXECUTE profile_orders_per_decision_stmt;
DEALLOCATE PREPARE profile_orders_per_decision_stmt;
SET @profile_orders_per_decision_backfill_sql :=
    'UPDATE stock_auto_participant_profile_config SET orders_per_decision_multiplier = LEAST(5.0000, GREATEST(0.0000, order_multiplier)) WHERE orders_per_decision_multiplier IS NULL';
PREPARE profile_orders_per_decision_backfill_stmt FROM @profile_orders_per_decision_backfill_sql;
EXECUTE profile_orders_per_decision_backfill_stmt;
DEALLOCATE PREPARE profile_orders_per_decision_backfill_stmt;

SET @profile_pricing_mode_existed := EXISTS (
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_auto_participant_profile_config'
       AND column_name = 'pricing_mode'
);
SET @profile_pricing_mode_sql := IF(
    @profile_pricing_mode_existed,
    'SELECT 1',
    'ALTER TABLE stock_auto_participant_profile_config ADD COLUMN pricing_mode VARCHAR(30) DEFAULT NULL AFTER profit_taking_weight'
);
PREPARE profile_pricing_mode_stmt FROM @profile_pricing_mode_sql;
EXECUTE profile_pricing_mode_stmt;
DEALLOCATE PREPARE profile_pricing_mode_stmt;
SET @profile_pricing_mode_backfill_sql :=
    'UPDATE stock_auto_participant_profile_config SET pricing_mode = CASE WHEN COALESCE(market_making_weight, 0) >= 0.8000 THEN ''MARKET_MAKING'' ELSE ''DIRECTIONAL'' END WHERE pricing_mode IS NULL';
PREPARE profile_pricing_mode_backfill_stmt FROM @profile_pricing_mode_backfill_sql;
EXECUTE profile_pricing_mode_backfill_stmt;
DEALLOCATE PREPARE profile_pricing_mode_backfill_stmt;

SET @profile_exit_mode_existed := EXISTS (
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_auto_participant_profile_config'
       AND column_name = 'exit_mode'
);
SET @profile_exit_mode_sql := IF(
    @profile_exit_mode_existed,
    'SELECT 1',
    'ALTER TABLE stock_auto_participant_profile_config ADD COLUMN exit_mode VARCHAR(30) DEFAULT NULL AFTER pricing_mode'
);
PREPARE profile_exit_mode_stmt FROM @profile_exit_mode_sql;
EXECUTE profile_exit_mode_stmt;
DEALLOCATE PREPARE profile_exit_mode_stmt;
SET @profile_exit_mode_backfill_sql :=
    'UPDATE stock_auto_participant_profile_config SET exit_mode = CASE WHEN profit_taking_weight >= 0.9000 THEN ''TAKE_PROFIT_FIRST'' WHEN holding_patience_weight >= 0.8500 THEN ''HOLD_LOSSES'' ELSE ''SIGNAL_DRIVEN'' END WHERE exit_mode IS NULL';
PREPARE profile_exit_mode_backfill_stmt FROM @profile_exit_mode_backfill_sql;
EXECUTE profile_exit_mode_backfill_stmt;
DEALLOCATE PREPARE profile_exit_mode_backfill_stmt;

SET @profile_inventory_mode_existed := EXISTS (
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_auto_participant_profile_config'
       AND column_name = 'inventory_mode'
);
SET @profile_inventory_mode_sql := IF(
    @profile_inventory_mode_existed,
    'SELECT 1',
    'ALTER TABLE stock_auto_participant_profile_config ADD COLUMN inventory_mode VARCHAR(30) DEFAULT NULL AFTER exit_mode'
);
PREPARE profile_inventory_mode_stmt FROM @profile_inventory_mode_sql;
EXECUTE profile_inventory_mode_stmt;
DEALLOCATE PREPARE profile_inventory_mode_stmt;
SET @profile_inventory_mode_backfill_sql :=
    'UPDATE stock_auto_participant_profile_config SET inventory_mode = CASE WHEN COALESCE(market_making_weight, 0) >= 0.8000 THEN ''TARGET_ALLOCATION'' ELSE ''SIGNAL_DRIVEN'' END WHERE inventory_mode IS NULL';
PREPARE profile_inventory_mode_backfill_stmt FROM @profile_inventory_mode_backfill_sql;
EXECUTE profile_inventory_mode_backfill_stmt;
DEALLOCATE PREPARE profile_inventory_mode_backfill_stmt;

SET @profile_execution_policy_not_null_sql := IF(
    EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = DATABASE()
           AND table_name = 'stock_auto_participant_profile_config'
           AND column_name IN (
               'decision_frequency_multiplier', 'orders_per_decision_multiplier',
               'pricing_mode', 'exit_mode', 'inventory_mode'
           )
           AND is_nullable = 'YES'
    ),
    'ALTER TABLE stock_auto_participant_profile_config
       MODIFY COLUMN decision_frequency_multiplier DECIMAL(8,4) NOT NULL DEFAULT 1.0000,
       MODIFY COLUMN orders_per_decision_multiplier DECIMAL(8,4) NOT NULL DEFAULT 1.0000,
       MODIFY COLUMN pricing_mode VARCHAR(30) NOT NULL DEFAULT ''DIRECTIONAL'',
       MODIFY COLUMN exit_mode VARCHAR(30) NOT NULL DEFAULT ''SIGNAL_DRIVEN'',
       MODIFY COLUMN inventory_mode VARCHAR(30) NOT NULL DEFAULT ''SIGNAL_DRIVEN''',
    'SELECT 1'
);
PREPARE profile_execution_policy_not_null_stmt FROM @profile_execution_policy_not_null_sql;
EXECUTE profile_execution_policy_not_null_stmt;
DEALLOCATE PREPARE profile_execution_policy_not_null_stmt;

SET @profile_decision_frequency_check_sql := IF(
    EXISTS (SELECT 1 FROM information_schema.table_constraints
             WHERE constraint_schema = DATABASE()
               AND table_name = 'stock_auto_participant_profile_config'
               AND constraint_name = 'chk_stock_auto_profile_decision_frequency'),
    'SELECT 1',
    'ALTER TABLE stock_auto_participant_profile_config ADD CONSTRAINT chk_stock_auto_profile_decision_frequency CHECK (decision_frequency_multiplier IS NULL OR (decision_frequency_multiplier >= 0 AND decision_frequency_multiplier <= 20))'
);
PREPARE profile_decision_frequency_check_stmt FROM @profile_decision_frequency_check_sql;
EXECUTE profile_decision_frequency_check_stmt;
DEALLOCATE PREPARE profile_decision_frequency_check_stmt;

SET @profile_orders_per_decision_check_sql := IF(
    EXISTS (SELECT 1 FROM information_schema.table_constraints
             WHERE constraint_schema = DATABASE()
               AND table_name = 'stock_auto_participant_profile_config'
               AND constraint_name = 'chk_stock_auto_profile_orders_per_decision'),
    'SELECT 1',
    'ALTER TABLE stock_auto_participant_profile_config ADD CONSTRAINT chk_stock_auto_profile_orders_per_decision CHECK (orders_per_decision_multiplier IS NULL OR (orders_per_decision_multiplier >= 0 AND orders_per_decision_multiplier <= 5))'
);
PREPARE profile_orders_per_decision_check_stmt FROM @profile_orders_per_decision_check_sql;
EXECUTE profile_orders_per_decision_check_stmt;
DEALLOCATE PREPARE profile_orders_per_decision_check_stmt;

SET @profile_pricing_mode_check_sql := IF(
    EXISTS (SELECT 1 FROM information_schema.table_constraints
             WHERE constraint_schema = DATABASE()
               AND table_name = 'stock_auto_participant_profile_config'
               AND constraint_name = 'chk_stock_auto_profile_pricing_mode'),
    'SELECT 1',
    'ALTER TABLE stock_auto_participant_profile_config ADD CONSTRAINT chk_stock_auto_profile_pricing_mode CHECK (pricing_mode IS NULL OR CASE pricing_mode WHEN ''DIRECTIONAL'' THEN 1 WHEN ''MARKET_MAKING'' THEN 1 ELSE 0 END = 1)'
);
PREPARE profile_pricing_mode_check_stmt FROM @profile_pricing_mode_check_sql;
EXECUTE profile_pricing_mode_check_stmt;
DEALLOCATE PREPARE profile_pricing_mode_check_stmt;

SET @profile_exit_mode_check_sql := IF(
    EXISTS (SELECT 1 FROM information_schema.table_constraints
             WHERE constraint_schema = DATABASE()
               AND table_name = 'stock_auto_participant_profile_config'
               AND constraint_name = 'chk_stock_auto_profile_exit_mode'),
    'SELECT 1',
    'ALTER TABLE stock_auto_participant_profile_config ADD CONSTRAINT chk_stock_auto_profile_exit_mode CHECK (exit_mode IS NULL OR CASE exit_mode WHEN ''SIGNAL_DRIVEN'' THEN 1 WHEN ''TAKE_PROFIT_FIRST'' THEN 1 WHEN ''HOLD_LOSSES'' THEN 1 ELSE 0 END = 1)'
);
PREPARE profile_exit_mode_check_stmt FROM @profile_exit_mode_check_sql;
EXECUTE profile_exit_mode_check_stmt;
DEALLOCATE PREPARE profile_exit_mode_check_stmt;

SET @profile_inventory_mode_check_sql := IF(
    EXISTS (SELECT 1 FROM information_schema.table_constraints
             WHERE constraint_schema = DATABASE()
               AND table_name = 'stock_auto_participant_profile_config'
               AND constraint_name = 'chk_stock_auto_profile_inventory_mode'),
    'SELECT 1',
    'ALTER TABLE stock_auto_participant_profile_config ADD CONSTRAINT chk_stock_auto_profile_inventory_mode CHECK (inventory_mode IS NULL OR CASE inventory_mode WHEN ''SIGNAL_DRIVEN'' THEN 1 WHEN ''TARGET_ALLOCATION'' THEN 1 ELSE 0 END = 1)'
);
PREPARE profile_inventory_mode_check_stmt FROM @profile_inventory_mode_check_sql;
EXECUTE profile_inventory_mode_check_stmt;
DEALLOCATE PREPARE profile_inventory_mode_check_stmt;
