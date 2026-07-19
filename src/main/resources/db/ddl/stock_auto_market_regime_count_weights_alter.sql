USE STOCK_SERVICE;

SET @ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'STOCK_SERVICE'
           AND table_name = 'stock_auto_market_config'
           AND column_name = 'primary_regime_count_1_weight'
    ),
    'SELECT 1',
    'ALTER TABLE stock_auto_market_config ADD COLUMN primary_regime_count_1_weight INT NOT NULL DEFAULT 0 AFTER enabled'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'STOCK_SERVICE'
           AND table_name = 'stock_auto_market_config'
           AND column_name = 'primary_regime_count_2_weight'
    ),
    'SELECT 1',
    'ALTER TABLE stock_auto_market_config ADD COLUMN primary_regime_count_2_weight INT NOT NULL DEFAULT 0 AFTER primary_regime_count_1_weight'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'STOCK_SERVICE'
           AND table_name = 'stock_auto_market_config'
           AND column_name = 'primary_regime_count_3_weight'
    ),
    'SELECT 1',
    'ALTER TABLE stock_auto_market_config ADD COLUMN primary_regime_count_3_weight INT NOT NULL DEFAULT 0 AFTER primary_regime_count_2_weight'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'STOCK_SERVICE'
           AND table_name = 'stock_auto_market_config'
           AND column_name = 'primary_regime_count_4_weight'
    ),
    'SELECT 1',
    'ALTER TABLE stock_auto_market_config ADD COLUMN primary_regime_count_4_weight INT NOT NULL DEFAULT 100 AFTER primary_regime_count_3_weight'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.table_constraints
         WHERE constraint_schema = 'STOCK_SERVICE'
           AND table_name = 'stock_auto_market_config'
           AND constraint_name = 'chk_stock_auto_market_regime_count_weights'
    ),
    'SELECT 1',
    'ALTER TABLE stock_auto_market_config ADD CONSTRAINT chk_stock_auto_market_regime_count_weights CHECK (primary_regime_count_1_weight BETWEEN 0 AND 100 AND primary_regime_count_2_weight BETWEEN 0 AND 100 AND primary_regime_count_3_weight BETWEEN 0 AND 100 AND primary_regime_count_4_weight BETWEEN 0 AND 100 AND primary_regime_count_1_weight + primary_regime_count_2_weight + primary_regime_count_3_weight + primary_regime_count_4_weight > 0)'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'STOCK_SERVICE'
           AND table_name = 'stock_order_book_daily_regime'
           AND column_name = 'source_regime_phase'
    ),
    'SELECT 1',
    'ALTER TABLE stock_order_book_daily_regime ADD COLUMN source_regime_phase VARCHAR(20) NULL AFTER regime_phase'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE stock_order_book_daily_regime
   SET source_regime_phase = regime_phase
 WHERE source_regime_phase IS NULL;

SET @ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.table_constraints
         WHERE constraint_schema = 'STOCK_SERVICE'
           AND table_name = 'stock_order_book_daily_regime'
           AND constraint_name = 'chk_stock_order_book_daily_regime_source_phase'
    ),
    'SELECT 1',
    'ALTER TABLE stock_order_book_daily_regime ADD CONSTRAINT chk_stock_order_book_daily_regime_source_phase CHECK (source_regime_phase IS NULL OR CASE source_regime_phase WHEN ''SLOT_0600'' THEN 1 WHEN ''SLOT_0900'' THEN 1 WHEN ''SLOT_1200'' THEN 1 WHEN ''SLOT_1500'' THEN 1 ELSE 0 END = 1)'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

