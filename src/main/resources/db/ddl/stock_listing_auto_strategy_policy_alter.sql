USE STOCK_SERVICE;

ALTER TABLE stock_listing_auto_account_config
  ADD COLUMN operation_mode VARCHAR(30) NOT NULL DEFAULT 'HYBRID' AFTER position_side,
  ADD COLUMN strategy_profile VARCHAR(30) NOT NULL DEFAULT 'BALANCED' AFTER operation_mode,
  ADD COLUMN initial_inventory_quantity BIGINT NOT NULL DEFAULT 0 AFTER strategy_profile,
  ADD COLUMN initial_issue_price DECIMAL(19,2) NOT NULL DEFAULT 1.00 AFTER initial_inventory_quantity,
  ADD COLUMN target_spread_ticks INT NOT NULL DEFAULT 2 AFTER price_offset_ticks,
  ADD COLUMN inventory_skew_ticks INT NOT NULL DEFAULT 4 AFTER target_spread_ticks,
  ADD COLUMN minimum_profit_rate DECIMAL(8,4) NOT NULL DEFAULT 0.5000 AFTER inventory_skew_ticks,
  ADD COLUMN aggressive_unwind_threshold DECIMAL(8,4) NOT NULL DEFAULT 0.9000 AFTER minimum_profit_rate,
  ADD COLUMN aggressive_order_ratio DECIMAL(8,4) NOT NULL DEFAULT 0.1000 AFTER aggressive_unwind_threshold;

UPDATE stock_listing_auto_account_config config
JOIN stock_order_book_instrument instrument ON instrument.symbol = config.symbol
LEFT JOIN stock_corporate_action initial_issue
  ON initial_issue.id = (
    SELECT MIN(candidate.id)
      FROM stock_corporate_action candidate
     WHERE candidate.symbol = config.symbol
       AND candidate.action_type = 'INITIAL_ISSUE'
  )
SET config.initial_inventory_quantity = COALESCE(initial_issue.share_quantity, instrument.issued_shares),
    config.initial_issue_price = COALESCE(initial_issue.issue_price, instrument.initial_price),
    config.operation_mode = CASE
      WHEN config.position_side = 'TWO_SIDED' THEN 'HYBRID'
      ELSE 'UNDERWRITER_RETURN'
    END,
    config.strategy_profile = CASE
      WHEN config.position_side = 'TWO_SIDED' THEN 'BALANCED'
      ELSE 'RETURN_FIRST'
    END,
    config.target_spread_ticks = CASE WHEN config.position_side = 'TWO_SIDED' THEN 4 ELSE 8 END,
    config.inventory_skew_ticks = CASE WHEN config.position_side = 'TWO_SIDED' THEN 4 ELSE 3 END,
    config.minimum_profit_rate = CASE WHEN config.position_side = 'TWO_SIDED' THEN 0.5000 ELSE 1.0000 END,
    config.aggressive_unwind_threshold = CASE WHEN config.position_side = 'TWO_SIDED' THEN 0.9000 ELSE 1.0000 END,
    config.aggressive_order_ratio = CASE WHEN config.position_side = 'TWO_SIDED' THEN 0.1000 ELSE 0.0000 END;

ALTER TABLE stock_listing_auto_account_config
  ALTER COLUMN operation_mode DROP DEFAULT,
  ALTER COLUMN strategy_profile DROP DEFAULT,
  ALTER COLUMN initial_inventory_quantity DROP DEFAULT,
  ALTER COLUMN initial_issue_price DROP DEFAULT,
  ALTER COLUMN target_spread_ticks DROP DEFAULT,
  ALTER COLUMN inventory_skew_ticks DROP DEFAULT,
  ALTER COLUMN minimum_profit_rate DROP DEFAULT,
  ALTER COLUMN aggressive_unwind_threshold DROP DEFAULT,
  ALTER COLUMN aggressive_order_ratio DROP DEFAULT,
  DROP CHECK chk_stock_listing_auto_account_buy_direction,
  DROP CHECK chk_stock_listing_auto_account_sell_direction,
  DROP COLUMN buy_price_offset_direction,
  DROP COLUMN sell_price_offset_direction,
  ADD CONSTRAINT chk_stock_listing_auto_account_operation_mode CHECK (operation_mode IN ('UNDERWRITER_RETURN', 'LIQUIDITY_PROVIDER', 'HYBRID')),
  ADD CONSTRAINT chk_stock_listing_auto_account_strategy_profile CHECK (strategy_profile IN ('LIQUIDITY_FIRST', 'BALANCED', 'RETURN_FIRST')),
  ADD CONSTRAINT chk_stock_listing_auto_account_initial_quantity CHECK (initial_inventory_quantity > 0),
  ADD CONSTRAINT chk_stock_listing_auto_account_initial_price CHECK (initial_issue_price > 0),
  ADD CONSTRAINT chk_stock_listing_auto_account_target_spread CHECK (target_spread_ticks BETWEEN 1 AND 50),
  ADD CONSTRAINT chk_stock_listing_auto_account_inventory_skew CHECK (inventory_skew_ticks BETWEEN 0 AND 50),
  ADD CONSTRAINT chk_stock_listing_auto_account_minimum_profit CHECK (minimum_profit_rate BETWEEN 0 AND 100),
  ADD CONSTRAINT chk_stock_listing_auto_account_unwind_threshold CHECK (aggressive_unwind_threshold BETWEEN 0 AND 1),
  ADD CONSTRAINT chk_stock_listing_auto_account_aggressive_ratio CHECK (aggressive_order_ratio BETWEEN 0 AND 1);
