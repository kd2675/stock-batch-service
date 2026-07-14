USE STOCK_SERVICE;

SET @listing_inventory_band_add_sql := IF(
  EXISTS (
    SELECT 1
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_listing_auto_account_config'
       AND column_name = 'inventory_band_quantity'
  ),
  'SELECT 1',
  'ALTER TABLE stock_listing_auto_account_config ADD COLUMN inventory_band_quantity BIGINT NOT NULL DEFAULT 0 AFTER target_holding_quantity'
);
PREPARE listing_inventory_band_add_stmt FROM @listing_inventory_band_add_sql;
EXECUTE listing_inventory_band_add_stmt;
DEALLOCATE PREPARE listing_inventory_band_add_stmt;

UPDATE stock_listing_auto_account_config
   SET inventory_band_quantity = LEAST(
         GREATEST(target_buy_quantity, target_sell_quantity, max_order_quantity),
         target_holding_quantity
       )
 WHERE position_side = 'TWO_SIDED'
   AND inventory_band_quantity = 0;

ALTER TABLE stock_listing_auto_account_config
  ALTER COLUMN inventory_band_quantity DROP DEFAULT;

SET @listing_inventory_band_check_drop_sql := IF(
  EXISTS (
    SELECT 1
      FROM information_schema.check_constraints
     WHERE constraint_schema = DATABASE()
       AND constraint_name = 'chk_stock_listing_auto_account_inventory_band'
  ),
  'ALTER TABLE stock_listing_auto_account_config DROP CHECK chk_stock_listing_auto_account_inventory_band',
  'SELECT 1'
);
PREPARE listing_inventory_band_check_drop_stmt FROM @listing_inventory_band_check_drop_sql;
EXECUTE listing_inventory_band_check_drop_stmt;
DEALLOCATE PREPARE listing_inventory_band_check_drop_stmt;

ALTER TABLE stock_listing_auto_account_config
  ADD CONSTRAINT chk_stock_listing_auto_account_inventory_band CHECK (inventory_band_quantity >= 0);
