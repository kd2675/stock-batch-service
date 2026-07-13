USE STOCK_SERVICE;

SET @listing_target_buy_add_sql := IF(
  EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'stock_listing_auto_account_config' AND column_name = 'target_buy_quantity'),
  'SELECT 1',
  'ALTER TABLE stock_listing_auto_account_config ADD COLUMN target_buy_quantity BIGINT NOT NULL DEFAULT 0 AFTER price_offset_ticks'
);
PREPARE listing_target_buy_add_stmt FROM @listing_target_buy_add_sql;
EXECUTE listing_target_buy_add_stmt;
DEALLOCATE PREPARE listing_target_buy_add_stmt;

SET @listing_target_sell_add_sql := IF(
  EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'stock_listing_auto_account_config' AND column_name = 'target_sell_quantity'),
  'SELECT 1',
  'ALTER TABLE stock_listing_auto_account_config ADD COLUMN target_sell_quantity BIGINT NOT NULL DEFAULT 0 AFTER target_buy_quantity'
);
PREPARE listing_target_sell_add_stmt FROM @listing_target_sell_add_sql;
EXECUTE listing_target_sell_add_stmt;
DEALLOCATE PREPARE listing_target_sell_add_stmt;

SET @listing_target_holding_add_sql := IF(
  EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'stock_listing_auto_account_config' AND column_name = 'target_holding_quantity'),
  'SELECT 1',
  'ALTER TABLE stock_listing_auto_account_config ADD COLUMN target_holding_quantity BIGINT NOT NULL DEFAULT 0 AFTER target_sell_quantity'
);
PREPARE listing_target_holding_add_stmt FROM @listing_target_holding_add_sql;
EXECUTE listing_target_holding_add_stmt;
DEALLOCATE PREPARE listing_target_holding_add_stmt;

SET @listing_buy_direction_add_sql := IF(
  EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'stock_listing_auto_account_config' AND column_name = 'buy_price_offset_direction'),
  'SELECT 1',
  "ALTER TABLE stock_listing_auto_account_config ADD COLUMN buy_price_offset_direction VARCHAR(10) NOT NULL DEFAULT 'DOWN' AFTER target_sell_quantity"
);
PREPARE listing_buy_direction_add_stmt FROM @listing_buy_direction_add_sql;
EXECUTE listing_buy_direction_add_stmt;
DEALLOCATE PREPARE listing_buy_direction_add_stmt;

SET @listing_sell_direction_add_sql := IF(
  EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'stock_listing_auto_account_config' AND column_name = 'sell_price_offset_direction'),
  'SELECT 1',
  "ALTER TABLE stock_listing_auto_account_config ADD COLUMN sell_price_offset_direction VARCHAR(10) NOT NULL DEFAULT 'UP' AFTER buy_price_offset_direction"
);
PREPARE listing_sell_direction_add_stmt FROM @listing_sell_direction_add_sql;
EXECUTE listing_sell_direction_add_stmt;
DEALLOCATE PREPARE listing_sell_direction_add_stmt;

UPDATE stock_listing_auto_account_config
   SET target_buy_quantity = CASE WHEN position_side = 'BUY_ONLY' THEN max_order_quantity ELSE target_buy_quantity END,
       target_sell_quantity = CASE WHEN position_side = 'SELL_ONLY' THEN max_order_quantity ELSE target_sell_quantity END
 WHERE target_buy_quantity = 0
   AND target_sell_quantity = 0;

ALTER TABLE stock_listing_auto_account_config
  ALTER COLUMN target_buy_quantity DROP DEFAULT,
  ALTER COLUMN target_sell_quantity DROP DEFAULT,
  ALTER COLUMN target_holding_quantity DROP DEFAULT,
  ALTER COLUMN buy_price_offset_direction DROP DEFAULT,
  ALTER COLUMN sell_price_offset_direction DROP DEFAULT;

SET @listing_position_check_drop_sql := IF(
  EXISTS (SELECT 1 FROM information_schema.check_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_stock_listing_auto_account_position'),
  'ALTER TABLE stock_listing_auto_account_config DROP CHECK chk_stock_listing_auto_account_position',
  'SELECT 1'
);
PREPARE listing_position_check_drop_stmt FROM @listing_position_check_drop_sql;
EXECUTE listing_position_check_drop_stmt;
DEALLOCATE PREPARE listing_position_check_drop_stmt;

SET @listing_target_buy_check_drop_sql := IF(
  EXISTS (SELECT 1 FROM information_schema.check_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_stock_listing_auto_account_target_buy'),
  'ALTER TABLE stock_listing_auto_account_config DROP CHECK chk_stock_listing_auto_account_target_buy',
  'SELECT 1'
);
PREPARE listing_target_buy_check_drop_stmt FROM @listing_target_buy_check_drop_sql;
EXECUTE listing_target_buy_check_drop_stmt;
DEALLOCATE PREPARE listing_target_buy_check_drop_stmt;

SET @listing_target_sell_check_drop_sql := IF(
  EXISTS (SELECT 1 FROM information_schema.check_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_stock_listing_auto_account_target_sell'),
  'ALTER TABLE stock_listing_auto_account_config DROP CHECK chk_stock_listing_auto_account_target_sell',
  'SELECT 1'
);
PREPARE listing_target_sell_check_drop_stmt FROM @listing_target_sell_check_drop_sql;
EXECUTE listing_target_sell_check_drop_stmt;
DEALLOCATE PREPARE listing_target_sell_check_drop_stmt;

SET @listing_target_holding_check_drop_sql := IF(
  EXISTS (SELECT 1 FROM information_schema.check_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_stock_listing_auto_account_target_holding'),
  'ALTER TABLE stock_listing_auto_account_config DROP CHECK chk_stock_listing_auto_account_target_holding',
  'SELECT 1'
);
PREPARE listing_target_holding_check_drop_stmt FROM @listing_target_holding_check_drop_sql;
EXECUTE listing_target_holding_check_drop_stmt;
DEALLOCATE PREPARE listing_target_holding_check_drop_stmt;

SET @listing_buy_direction_check_drop_sql := IF(
  EXISTS (SELECT 1 FROM information_schema.check_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_stock_listing_auto_account_buy_direction'),
  'ALTER TABLE stock_listing_auto_account_config DROP CHECK chk_stock_listing_auto_account_buy_direction',
  'SELECT 1'
);
PREPARE listing_buy_direction_check_drop_stmt FROM @listing_buy_direction_check_drop_sql;
EXECUTE listing_buy_direction_check_drop_stmt;
DEALLOCATE PREPARE listing_buy_direction_check_drop_stmt;

SET @listing_sell_direction_check_drop_sql := IF(
  EXISTS (SELECT 1 FROM information_schema.check_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_stock_listing_auto_account_sell_direction'),
  'ALTER TABLE stock_listing_auto_account_config DROP CHECK chk_stock_listing_auto_account_sell_direction',
  'SELECT 1'
);
PREPARE listing_sell_direction_check_drop_stmt FROM @listing_sell_direction_check_drop_sql;
EXECUTE listing_sell_direction_check_drop_stmt;
DEALLOCATE PREPARE listing_sell_direction_check_drop_stmt;

ALTER TABLE stock_listing_auto_account_config
  ADD CONSTRAINT chk_stock_listing_auto_account_position CHECK (position_side IN ('SELL_ONLY', 'BUY_ONLY', 'TWO_SIDED')),
  ADD CONSTRAINT chk_stock_listing_auto_account_target_buy CHECK (target_buy_quantity >= 0),
  ADD CONSTRAINT chk_stock_listing_auto_account_target_sell CHECK (target_sell_quantity >= 0),
  ADD CONSTRAINT chk_stock_listing_auto_account_target_holding CHECK (target_holding_quantity >= 0),
  ADD CONSTRAINT chk_stock_listing_auto_account_buy_direction CHECK (buy_price_offset_direction IN ('UP', 'DOWN', 'RANDOM')),
  ADD CONSTRAINT chk_stock_listing_auto_account_sell_direction CHECK (sell_price_offset_direction IN ('UP', 'DOWN', 'RANDOM'));
