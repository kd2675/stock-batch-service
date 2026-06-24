USE STOCK_SERVICE;

SET @stock_order_market_type_column_exists = (
  SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'STOCK_SERVICE'
    AND TABLE_NAME = 'stock_order'
    AND COLUMN_NAME = 'market_type'
);
SET @stock_order_market_type_column_sql = IF(
  @stock_order_market_type_column_exists = 0,
  'ALTER TABLE stock_order ADD COLUMN market_type VARCHAR(30) NOT NULL DEFAULT ''VIRTUAL_PRICE'' AFTER symbol',
  'SELECT 1'
);
PREPARE stock_order_market_type_column_stmt FROM @stock_order_market_type_column_sql;
EXECUTE stock_order_market_type_column_stmt;
DEALLOCATE PREPARE stock_order_market_type_column_stmt;

SET @stock_order_market_type_index_exists = (
  SELECT COUNT(*)
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = 'STOCK_SERVICE'
    AND TABLE_NAME = 'stock_order'
    AND INDEX_NAME = 'idx_stock_order_market_status_symbol'
);
SET @stock_order_market_type_index_sql = IF(
  @stock_order_market_type_index_exists = 0,
  'CREATE INDEX idx_stock_order_market_status_symbol ON stock_order(market_type, status, symbol)',
  'SELECT 1'
);
PREPARE stock_order_market_type_index_stmt FROM @stock_order_market_type_index_sql;
EXECUTE stock_order_market_type_index_stmt;
DEALLOCATE PREPARE stock_order_market_type_index_stmt;

SET @stock_execution_gross_column_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_execution' AND COLUMN_NAME = 'gross_amount') = 0,
  'ALTER TABLE stock_execution ADD COLUMN gross_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00 AFTER price',
  'SELECT 1'
);
PREPARE stock_execution_gross_column_stmt FROM @stock_execution_gross_column_sql;
EXECUTE stock_execution_gross_column_stmt;
DEALLOCATE PREPARE stock_execution_gross_column_stmt;

SET @stock_execution_fee_column_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_execution' AND COLUMN_NAME = 'fee_amount') = 0,
  'ALTER TABLE stock_execution ADD COLUMN fee_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00 AFTER gross_amount',
  'SELECT 1'
);
PREPARE stock_execution_fee_column_stmt FROM @stock_execution_fee_column_sql;
EXECUTE stock_execution_fee_column_stmt;
DEALLOCATE PREPARE stock_execution_fee_column_stmt;

SET @stock_execution_tax_column_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_execution' AND COLUMN_NAME = 'tax_amount') = 0,
  'ALTER TABLE stock_execution ADD COLUMN tax_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00 AFTER fee_amount',
  'SELECT 1'
);
PREPARE stock_execution_tax_column_stmt FROM @stock_execution_tax_column_sql;
EXECUTE stock_execution_tax_column_stmt;
DEALLOCATE PREPARE stock_execution_tax_column_stmt;

SET @stock_execution_net_column_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_execution' AND COLUMN_NAME = 'net_amount') = 0,
  'ALTER TABLE stock_execution ADD COLUMN net_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00 AFTER tax_amount',
  'SELECT 1'
);
PREPARE stock_execution_net_column_stmt FROM @stock_execution_net_column_sql;
EXECUTE stock_execution_net_column_stmt;
DEALLOCATE PREPARE stock_execution_net_column_stmt;

SET @stock_execution_realized_column_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_execution' AND COLUMN_NAME = 'realized_profit') = 0,
  'ALTER TABLE stock_execution ADD COLUMN realized_profit DECIMAL(19,2) NULL AFTER net_amount',
  'SELECT 1'
);
PREPARE stock_execution_realized_column_stmt FROM @stock_execution_realized_column_sql;
EXECUTE stock_execution_realized_column_stmt;
DEALLOCATE PREPARE stock_execution_realized_column_stmt;

CREATE TABLE IF NOT EXISTS stock_virtual_market_config (
  symbol VARCHAR(20) NOT NULL,
  enabled BIT NOT NULL,
  market_status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (symbol),
  KEY idx_stock_virtual_market_enabled (enabled, market_status, symbol)
);

CREATE TABLE IF NOT EXISTS stock_order_book_market_config (
  symbol VARCHAR(20) NOT NULL,
  enabled BIT NOT NULL,
  market_status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (symbol),
  KEY idx_stock_order_book_market_enabled (enabled, market_status, symbol)
);

SET @stock_virtual_market_status_column_exists = (
  SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'STOCK_SERVICE'
    AND TABLE_NAME = 'stock_virtual_market_config'
    AND COLUMN_NAME = 'market_status'
);
SET @stock_virtual_market_status_column_sql = IF(
  @stock_virtual_market_status_column_exists = 0,
  'ALTER TABLE stock_virtual_market_config ADD COLUMN market_status VARCHAR(20) NOT NULL DEFAULT ''OPEN'' AFTER enabled',
  'SELECT 1'
);
PREPARE stock_virtual_market_status_column_stmt FROM @stock_virtual_market_status_column_sql;
EXECUTE stock_virtual_market_status_column_stmt;
DEALLOCATE PREPARE stock_virtual_market_status_column_stmt;

SET @stock_order_book_market_status_column_exists = (
  SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'STOCK_SERVICE'
    AND TABLE_NAME = 'stock_order_book_market_config'
    AND COLUMN_NAME = 'market_status'
);
SET @stock_order_book_market_status_column_sql = IF(
  @stock_order_book_market_status_column_exists = 0,
  'ALTER TABLE stock_order_book_market_config ADD COLUMN market_status VARCHAR(20) NOT NULL DEFAULT ''OPEN'' AFTER enabled',
  'SELECT 1'
);
PREPARE stock_order_book_market_status_column_stmt FROM @stock_order_book_market_status_column_sql;
EXECUTE stock_order_book_market_status_column_stmt;
DEALLOCATE PREPARE stock_order_book_market_status_column_stmt;

CREATE TABLE IF NOT EXISTS stock_order_book_instrument (
  symbol VARCHAR(20) NOT NULL,
  name VARCHAR(120) NOT NULL,
  market VARCHAR(20) NOT NULL,
  initial_price DECIMAL(19,2) NOT NULL,
  issued_shares BIGINT NOT NULL DEFAULT 100000,
  tradable_shares BIGINT NOT NULL DEFAULT 100000,
  tick_size DECIMAL(19,2) NOT NULL DEFAULT 1.00,
  price_limit_rate DECIMAL(5,2) NOT NULL DEFAULT 30.00,
  enabled BIT NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (symbol),
  KEY idx_stock_order_book_instrument_enabled (enabled, symbol),
  CONSTRAINT chk_stock_order_book_instrument_initial_price CHECK (initial_price > 0),
  CONSTRAINT chk_stock_order_book_instrument_issued_shares CHECK (issued_shares > 0),
  CONSTRAINT chk_stock_order_book_instrument_tradable_shares CHECK (tradable_shares >= 0 AND tradable_shares <= issued_shares),
  CONSTRAINT chk_stock_order_book_instrument_tick_size CHECK (tick_size > 0),
  CONSTRAINT chk_stock_order_book_instrument_price_limit_rate CHECK (price_limit_rate > 0 AND price_limit_rate <= 100)
);

SET @stock_order_book_issued_shares_column_exists = (
  SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'STOCK_SERVICE'
    AND TABLE_NAME = 'stock_order_book_instrument'
    AND COLUMN_NAME = 'issued_shares'
);
SET @stock_order_book_issued_shares_column_sql = IF(
  @stock_order_book_issued_shares_column_exists = 0,
  'ALTER TABLE stock_order_book_instrument ADD COLUMN issued_shares BIGINT NOT NULL DEFAULT 100000 AFTER initial_price',
  'SELECT 1'
);
PREPARE stock_order_book_issued_shares_column_stmt FROM @stock_order_book_issued_shares_column_sql;
EXECUTE stock_order_book_issued_shares_column_stmt;
DEALLOCATE PREPARE stock_order_book_issued_shares_column_stmt;

SET @stock_order_book_tradable_shares_column_exists = (
  SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'STOCK_SERVICE'
    AND TABLE_NAME = 'stock_order_book_instrument'
    AND COLUMN_NAME = 'tradable_shares'
);
SET @stock_order_book_tradable_shares_column_sql = IF(
  @stock_order_book_tradable_shares_column_exists = 0,
  'ALTER TABLE stock_order_book_instrument ADD COLUMN tradable_shares BIGINT NOT NULL DEFAULT 100000 AFTER issued_shares',
  'SELECT 1'
);
PREPARE stock_order_book_tradable_shares_column_stmt FROM @stock_order_book_tradable_shares_column_sql;
EXECUTE stock_order_book_tradable_shares_column_stmt;
DEALLOCATE PREPARE stock_order_book_tradable_shares_column_stmt;

SET @stock_order_book_tick_size_column_exists = (
  SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'STOCK_SERVICE'
    AND TABLE_NAME = 'stock_order_book_instrument'
    AND COLUMN_NAME = 'tick_size'
);
SET @stock_order_book_tick_size_column_sql = IF(
  @stock_order_book_tick_size_column_exists = 0,
  'ALTER TABLE stock_order_book_instrument ADD COLUMN tick_size DECIMAL(19,2) NOT NULL DEFAULT 1.00 AFTER tradable_shares',
  'SELECT 1'
);
PREPARE stock_order_book_tick_size_column_stmt FROM @stock_order_book_tick_size_column_sql;
EXECUTE stock_order_book_tick_size_column_stmt;
DEALLOCATE PREPARE stock_order_book_tick_size_column_stmt;

SET @stock_order_book_price_limit_rate_column_exists = (
  SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'STOCK_SERVICE'
    AND TABLE_NAME = 'stock_order_book_instrument'
    AND COLUMN_NAME = 'price_limit_rate'
);
SET @stock_order_book_price_limit_rate_column_sql = IF(
  @stock_order_book_price_limit_rate_column_exists = 0,
  'ALTER TABLE stock_order_book_instrument ADD COLUMN price_limit_rate DECIMAL(5,2) NOT NULL DEFAULT 30.00 AFTER tick_size',
  'SELECT 1'
);
PREPARE stock_order_book_price_limit_rate_column_stmt FROM @stock_order_book_price_limit_rate_column_sql;
EXECUTE stock_order_book_price_limit_rate_column_stmt;
DEALLOCATE PREPARE stock_order_book_price_limit_rate_column_stmt;

CREATE TABLE IF NOT EXISTS stock_corporate_action (
  id BIGINT NOT NULL AUTO_INCREMENT,
  symbol VARCHAR(20) NOT NULL,
  action_type VARCHAR(40) NOT NULL,
  share_quantity BIGINT NULL,
  issue_price DECIMAL(19,2) NULL,
  dividend_amount DECIMAL(19,2) NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'ANNOUNCED',
  base_price DECIMAL(19,2) NULL,
  theoretical_ex_rights_price DECIMAL(19,2) NULL,
  ex_rights_date DATE NULL,
  payment_date DATE NULL,
  listing_date DATE NULL,
  applied_at DATETIME NULL,
  paid_at DATETIME NULL,
  listed_at DATETIME NULL,
  split_from INT NULL,
  split_to INT NULL,
  description VARCHAR(255) NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_stock_corporate_action_symbol_created (symbol, created_at),
  KEY idx_stock_corporate_action_status_dates (status, ex_rights_date, payment_date, listing_date),
  CONSTRAINT chk_stock_corporate_action_type_valid CHECK (CASE `action_type` WHEN 'INITIAL_ISSUE' THEN 1 WHEN 'PAID_IN_CAPITAL_INCREASE' THEN 1 WHEN 'ADDITIONAL_ISSUE' THEN 1 WHEN 'STOCK_SPLIT' THEN 1 WHEN 'CASH_DIVIDEND' THEN 1 WHEN 'BONUS_ISSUE' THEN 1 WHEN 'STOCK_DIVIDEND' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_corporate_action_status_valid CHECK (CASE `status` WHEN 'ANNOUNCED' THEN 1 WHEN 'EX_RIGHTS_APPLIED' THEN 1 WHEN 'PAID' THEN 1 WHEN 'LISTED' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_corporate_action_share_quantity CHECK (share_quantity IS NULL OR share_quantity > 0),
  CONSTRAINT chk_stock_corporate_action_issue_price CHECK (issue_price IS NULL OR issue_price > 0),
  CONSTRAINT chk_stock_corporate_action_dividend_amount CHECK (dividend_amount IS NULL OR dividend_amount > 0),
  CONSTRAINT chk_stock_corporate_action_base_price CHECK (base_price IS NULL OR base_price > 0),
  CONSTRAINT chk_stock_corporate_action_ex_rights_price CHECK (theoretical_ex_rights_price IS NULL OR theoretical_ex_rights_price > 0),
  CONSTRAINT chk_stock_corporate_action_paid_dates CHECK (ex_rights_date IS NULL OR payment_date IS NULL OR payment_date >= ex_rights_date),
  CONSTRAINT chk_stock_corporate_action_listing_dates CHECK (payment_date IS NULL OR listing_date IS NULL OR listing_date >= payment_date),
  CONSTRAINT chk_stock_corporate_action_split_from CHECK (split_from IS NULL OR split_from > 0),
  CONSTRAINT chk_stock_corporate_action_split_to CHECK (split_to IS NULL OR split_to > 0),
  CONSTRAINT chk_stock_corporate_action_issue_required CHECK (
    action_type NOT IN ('INITIAL_ISSUE', 'PAID_IN_CAPITAL_INCREASE', 'ADDITIONAL_ISSUE')
    OR (share_quantity IS NOT NULL AND issue_price IS NOT NULL)
  ),
  CONSTRAINT chk_stock_corporate_action_paid_schedule_required CHECK (
    action_type <> 'PAID_IN_CAPITAL_INCREASE'
    OR (
      base_price IS NOT NULL
      AND theoretical_ex_rights_price IS NOT NULL
      AND ex_rights_date IS NOT NULL
      AND payment_date IS NOT NULL
      AND listing_date IS NOT NULL
    )
  ),
  CONSTRAINT chk_stock_corporate_action_additional_listing_required CHECK (
    action_type <> 'ADDITIONAL_ISSUE' OR listing_date IS NOT NULL
  ),
  CONSTRAINT chk_stock_corporate_action_split_required CHECK (
    action_type <> 'STOCK_SPLIT'
    OR (
      split_from IS NOT NULL
      AND split_to IS NOT NULL
      AND split_to > split_from
      AND MOD(split_to, split_from) = 0
    )
  ),
  CONSTRAINT chk_stock_corporate_action_dividend_required CHECK (
    action_type <> 'CASH_DIVIDEND'
    OR (
      dividend_amount IS NOT NULL
      AND base_price IS NOT NULL
      AND theoretical_ex_rights_price IS NOT NULL
      AND ex_rights_date IS NOT NULL
      AND payment_date IS NOT NULL
    )
  ),
  CONSTRAINT chk_stock_corporate_action_free_share_required CHECK (
    action_type NOT IN ('BONUS_ISSUE', 'STOCK_DIVIDEND')
    OR (
      share_quantity IS NOT NULL
      AND base_price IS NOT NULL
      AND theoretical_ex_rights_price IS NOT NULL
      AND ex_rights_date IS NOT NULL
      AND listing_date IS NOT NULL
    )
  ),
  CONSTRAINT chk_stock_corporate_action_field_scope CHECK (
    (action_type IN ('INITIAL_ISSUE', 'PAID_IN_CAPITAL_INCREASE', 'ADDITIONAL_ISSUE', 'BONUS_ISSUE', 'STOCK_DIVIDEND') OR share_quantity IS NULL)
    AND (action_type IN ('INITIAL_ISSUE', 'PAID_IN_CAPITAL_INCREASE', 'ADDITIONAL_ISSUE') OR issue_price IS NULL)
    AND (action_type = 'CASH_DIVIDEND' OR dividend_amount IS NULL)
    AND (action_type IN ('PAID_IN_CAPITAL_INCREASE', 'CASH_DIVIDEND', 'BONUS_ISSUE', 'STOCK_DIVIDEND') OR base_price IS NULL)
    AND (action_type IN ('PAID_IN_CAPITAL_INCREASE', 'CASH_DIVIDEND', 'BONUS_ISSUE', 'STOCK_DIVIDEND') OR theoretical_ex_rights_price IS NULL)
    AND (action_type IN ('PAID_IN_CAPITAL_INCREASE', 'CASH_DIVIDEND', 'BONUS_ISSUE', 'STOCK_DIVIDEND') OR ex_rights_date IS NULL)
    AND (action_type IN ('PAID_IN_CAPITAL_INCREASE', 'CASH_DIVIDEND') OR payment_date IS NULL)
    AND (action_type IN ('PAID_IN_CAPITAL_INCREASE', 'ADDITIONAL_ISSUE', 'STOCK_SPLIT', 'BONUS_ISSUE', 'STOCK_DIVIDEND') OR listing_date IS NULL)
    AND (action_type = 'STOCK_SPLIT' OR (split_from IS NULL AND split_to IS NULL))
  ),
  CONSTRAINT chk_stock_corporate_action_initial_listed CHECK (
    action_type <> 'INITIAL_ISSUE'
    OR (
      status = 'LISTED'
      AND listed_at IS NOT NULL
      AND applied_at IS NULL
      AND paid_at IS NULL
    )
  )
);

CREATE TABLE IF NOT EXISTS stock_corporate_action_entitlement (
  id BIGINT NOT NULL AUTO_INCREMENT,
  action_id BIGINT NOT NULL,
  user_key VARCHAR(64) NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  quantity BIGINT NOT NULL,
  share_quantity BIGINT NULL,
  cash_amount DECIMAL(19,2) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ANNOUNCED',
  created_at DATETIME NOT NULL,
  paid_at DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_corporate_action_entitlement_action_user (action_id, user_key),
  KEY idx_stock_corporate_action_entitlement_user_created (user_key, created_at),
  KEY idx_stock_corporate_action_entitlement_status (status, action_id),
  CONSTRAINT chk_stock_corporate_action_entitlement_quantity CHECK (quantity > 0),
  CONSTRAINT chk_stock_corporate_action_entitlement_share CHECK (share_quantity IS NULL OR share_quantity > 0),
  CONSTRAINT chk_stock_corporate_action_entitlement_cash CHECK (cash_amount IS NULL OR cash_amount > 0),
  CONSTRAINT chk_stock_corporate_action_entitlement_value CHECK (cash_amount IS NOT NULL OR share_quantity IS NOT NULL),
  CONSTRAINT chk_stock_corporate_action_entitlement_status CHECK (CASE `status` WHEN 'ANNOUNCED' THEN 1 WHEN 'PAID' THEN 1 ELSE 0 END = 1)
);

SET @stock_corporate_action_entitlement_user_index_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action_entitlement' AND INDEX_NAME = 'idx_stock_corporate_action_entitlement_user_created') = 0,
  'CREATE INDEX idx_stock_corporate_action_entitlement_user_created ON stock_corporate_action_entitlement(user_key, created_at)',
  'SELECT 1'
);
PREPARE stock_corporate_action_entitlement_user_index_stmt FROM @stock_corporate_action_entitlement_user_index_sql;
EXECUTE stock_corporate_action_entitlement_user_index_stmt;
DEALLOCATE PREPARE stock_corporate_action_entitlement_user_index_stmt;

SET @stock_corporate_action_entitlement_share_column_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action_entitlement' AND COLUMN_NAME = 'share_quantity') = 0,
  'ALTER TABLE stock_corporate_action_entitlement ADD COLUMN share_quantity BIGINT NULL AFTER quantity',
  'SELECT 1'
);
PREPARE stock_corporate_action_entitlement_share_column_stmt FROM @stock_corporate_action_entitlement_share_column_sql;
EXECUTE stock_corporate_action_entitlement_share_column_stmt;
DEALLOCATE PREPARE stock_corporate_action_entitlement_share_column_stmt;

ALTER TABLE stock_corporate_action_entitlement
  MODIFY COLUMN cash_amount DECIMAL(19,2) NULL;

SET @stock_corporate_action_status_column_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action' AND COLUMN_NAME = 'status') = 0,
  'ALTER TABLE stock_corporate_action ADD COLUMN status VARCHAR(30) NOT NULL DEFAULT ''ANNOUNCED'' AFTER issue_price',
  'SELECT 1'
);
PREPARE stock_corporate_action_status_column_stmt FROM @stock_corporate_action_status_column_sql;
EXECUTE stock_corporate_action_status_column_stmt;
DEALLOCATE PREPARE stock_corporate_action_status_column_stmt;

SET @stock_corporate_action_dividend_amount_column_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action' AND COLUMN_NAME = 'dividend_amount') = 0,
  'ALTER TABLE stock_corporate_action ADD COLUMN dividend_amount DECIMAL(19,2) NULL AFTER issue_price',
  'SELECT 1'
);
PREPARE stock_corporate_action_dividend_amount_column_stmt FROM @stock_corporate_action_dividend_amount_column_sql;
EXECUTE stock_corporate_action_dividend_amount_column_stmt;
DEALLOCATE PREPARE stock_corporate_action_dividend_amount_column_stmt;

SET @stock_corporate_action_base_price_column_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action' AND COLUMN_NAME = 'base_price') = 0,
  'ALTER TABLE stock_corporate_action ADD COLUMN base_price DECIMAL(19,2) NULL AFTER status',
  'SELECT 1'
);
PREPARE stock_corporate_action_base_price_column_stmt FROM @stock_corporate_action_base_price_column_sql;
EXECUTE stock_corporate_action_base_price_column_stmt;
DEALLOCATE PREPARE stock_corporate_action_base_price_column_stmt;

SET @stock_corporate_action_terp_column_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action' AND COLUMN_NAME = 'theoretical_ex_rights_price') = 0,
  'ALTER TABLE stock_corporate_action ADD COLUMN theoretical_ex_rights_price DECIMAL(19,2) NULL AFTER base_price',
  'SELECT 1'
);
PREPARE stock_corporate_action_terp_column_stmt FROM @stock_corporate_action_terp_column_sql;
EXECUTE stock_corporate_action_terp_column_stmt;
DEALLOCATE PREPARE stock_corporate_action_terp_column_stmt;

SET @stock_corporate_action_ex_rights_date_column_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action' AND COLUMN_NAME = 'ex_rights_date') = 0,
  'ALTER TABLE stock_corporate_action ADD COLUMN ex_rights_date DATE NULL AFTER theoretical_ex_rights_price',
  'SELECT 1'
);
PREPARE stock_corporate_action_ex_rights_date_column_stmt FROM @stock_corporate_action_ex_rights_date_column_sql;
EXECUTE stock_corporate_action_ex_rights_date_column_stmt;
DEALLOCATE PREPARE stock_corporate_action_ex_rights_date_column_stmt;

SET @stock_corporate_action_payment_date_column_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action' AND COLUMN_NAME = 'payment_date') = 0,
  'ALTER TABLE stock_corporate_action ADD COLUMN payment_date DATE NULL AFTER ex_rights_date',
  'SELECT 1'
);
PREPARE stock_corporate_action_payment_date_column_stmt FROM @stock_corporate_action_payment_date_column_sql;
EXECUTE stock_corporate_action_payment_date_column_stmt;
DEALLOCATE PREPARE stock_corporate_action_payment_date_column_stmt;

SET @stock_corporate_action_listing_date_column_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action' AND COLUMN_NAME = 'listing_date') = 0,
  'ALTER TABLE stock_corporate_action ADD COLUMN listing_date DATE NULL AFTER payment_date',
  'SELECT 1'
);
PREPARE stock_corporate_action_listing_date_column_stmt FROM @stock_corporate_action_listing_date_column_sql;
EXECUTE stock_corporate_action_listing_date_column_stmt;
DEALLOCATE PREPARE stock_corporate_action_listing_date_column_stmt;

SET @stock_corporate_action_applied_at_column_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action' AND COLUMN_NAME = 'applied_at') = 0,
  'ALTER TABLE stock_corporate_action ADD COLUMN applied_at DATETIME NULL AFTER listing_date',
  'SELECT 1'
);
PREPARE stock_corporate_action_applied_at_column_stmt FROM @stock_corporate_action_applied_at_column_sql;
EXECUTE stock_corporate_action_applied_at_column_stmt;
DEALLOCATE PREPARE stock_corporate_action_applied_at_column_stmt;

SET @stock_corporate_action_paid_at_column_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action' AND COLUMN_NAME = 'paid_at') = 0,
  'ALTER TABLE stock_corporate_action ADD COLUMN paid_at DATETIME NULL AFTER applied_at',
  'SELECT 1'
);
PREPARE stock_corporate_action_paid_at_column_stmt FROM @stock_corporate_action_paid_at_column_sql;
EXECUTE stock_corporate_action_paid_at_column_stmt;
DEALLOCATE PREPARE stock_corporate_action_paid_at_column_stmt;

SET @stock_corporate_action_listed_at_column_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action' AND COLUMN_NAME = 'listed_at') = 0,
  'ALTER TABLE stock_corporate_action ADD COLUMN listed_at DATETIME NULL AFTER paid_at',
  'SELECT 1'
);
PREPARE stock_corporate_action_listed_at_column_stmt FROM @stock_corporate_action_listed_at_column_sql;
EXECUTE stock_corporate_action_listed_at_column_stmt;
DEALLOCATE PREPARE stock_corporate_action_listed_at_column_stmt;

UPDATE stock_corporate_action
   SET status = 'LISTED',
       listed_at = COALESCE(listed_at, created_at),
       applied_at = NULL,
       paid_at = NULL
 WHERE action_type = 'INITIAL_ISSUE'
   AND (
     status <> 'LISTED'
     OR listed_at IS NULL
     OR applied_at IS NOT NULL
     OR paid_at IS NOT NULL
   );

SET @stock_corporate_action_status_dates_index_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action' AND INDEX_NAME = 'idx_stock_corporate_action_status_dates') = 0,
  'CREATE INDEX idx_stock_corporate_action_status_dates ON stock_corporate_action(status, ex_rights_date, payment_date, listing_date)',
  'SELECT 1'
);
PREPARE stock_corporate_action_status_dates_index_stmt FROM @stock_corporate_action_status_dates_index_sql;
EXECUTE stock_corporate_action_status_dates_index_stmt;
DEALLOCATE PREPARE stock_corporate_action_status_dates_index_stmt;

SET @stock_corporate_action_type_valid_drop_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action' AND CONSTRAINT_NAME = 'chk_stock_corporate_action_type_valid') > 0,
  'ALTER TABLE stock_corporate_action DROP CHECK chk_stock_corporate_action_type_valid',
  'SELECT 1'
);
PREPARE stock_corporate_action_type_valid_drop_stmt FROM @stock_corporate_action_type_valid_drop_sql;
EXECUTE stock_corporate_action_type_valid_drop_stmt;
DEALLOCATE PREPARE stock_corporate_action_type_valid_drop_stmt;

ALTER TABLE stock_corporate_action
  ADD CONSTRAINT chk_stock_corporate_action_type_valid
  CHECK (CASE `action_type` WHEN 'INITIAL_ISSUE' THEN 1 WHEN 'PAID_IN_CAPITAL_INCREASE' THEN 1 WHEN 'ADDITIONAL_ISSUE' THEN 1 WHEN 'STOCK_SPLIT' THEN 1 WHEN 'CASH_DIVIDEND' THEN 1 WHEN 'BONUS_ISSUE' THEN 1 WHEN 'STOCK_DIVIDEND' THEN 1 ELSE 0 END = 1);

SET @stock_corporate_action_status_valid_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action' AND CONSTRAINT_NAME = 'chk_stock_corporate_action_status_valid') = 0,
  'ALTER TABLE stock_corporate_action ADD CONSTRAINT chk_stock_corporate_action_status_valid CHECK (CASE `status` WHEN ''ANNOUNCED'' THEN 1 WHEN ''EX_RIGHTS_APPLIED'' THEN 1 WHEN ''PAID'' THEN 1 WHEN ''LISTED'' THEN 1 ELSE 0 END = 1)',
  'SELECT 1'
);
PREPARE stock_corporate_action_status_valid_stmt FROM @stock_corporate_action_status_valid_sql;
EXECUTE stock_corporate_action_status_valid_stmt;
DEALLOCATE PREPARE stock_corporate_action_status_valid_stmt;

SET @stock_corporate_action_share_quantity_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action' AND CONSTRAINT_NAME = 'chk_stock_corporate_action_share_quantity') = 0,
  'ALTER TABLE stock_corporate_action ADD CONSTRAINT chk_stock_corporate_action_share_quantity CHECK (share_quantity IS NULL OR share_quantity > 0)',
  'SELECT 1'
);
PREPARE stock_corporate_action_share_quantity_stmt FROM @stock_corporate_action_share_quantity_sql;
EXECUTE stock_corporate_action_share_quantity_stmt;
DEALLOCATE PREPARE stock_corporate_action_share_quantity_stmt;

SET @stock_corporate_action_issue_price_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action' AND CONSTRAINT_NAME = 'chk_stock_corporate_action_issue_price') = 0,
  'ALTER TABLE stock_corporate_action ADD CONSTRAINT chk_stock_corporate_action_issue_price CHECK (issue_price IS NULL OR issue_price > 0)',
  'SELECT 1'
);
PREPARE stock_corporate_action_issue_price_stmt FROM @stock_corporate_action_issue_price_sql;
EXECUTE stock_corporate_action_issue_price_stmt;
DEALLOCATE PREPARE stock_corporate_action_issue_price_stmt;

SET @stock_corporate_action_dividend_amount_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action' AND CONSTRAINT_NAME = 'chk_stock_corporate_action_dividend_amount') = 0,
  'ALTER TABLE stock_corporate_action ADD CONSTRAINT chk_stock_corporate_action_dividend_amount CHECK (dividend_amount IS NULL OR dividend_amount > 0)',
  'SELECT 1'
);
PREPARE stock_corporate_action_dividend_amount_stmt FROM @stock_corporate_action_dividend_amount_sql;
EXECUTE stock_corporate_action_dividend_amount_stmt;
DEALLOCATE PREPARE stock_corporate_action_dividend_amount_stmt;

SET @stock_corporate_action_base_price_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action' AND CONSTRAINT_NAME = 'chk_stock_corporate_action_base_price') = 0,
  'ALTER TABLE stock_corporate_action ADD CONSTRAINT chk_stock_corporate_action_base_price CHECK (base_price IS NULL OR base_price > 0)',
  'SELECT 1'
);
PREPARE stock_corporate_action_base_price_stmt FROM @stock_corporate_action_base_price_sql;
EXECUTE stock_corporate_action_base_price_stmt;
DEALLOCATE PREPARE stock_corporate_action_base_price_stmt;

SET @stock_corporate_action_ex_rights_price_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action' AND CONSTRAINT_NAME = 'chk_stock_corporate_action_ex_rights_price') = 0,
  'ALTER TABLE stock_corporate_action ADD CONSTRAINT chk_stock_corporate_action_ex_rights_price CHECK (theoretical_ex_rights_price IS NULL OR theoretical_ex_rights_price > 0)',
  'SELECT 1'
);
PREPARE stock_corporate_action_ex_rights_price_stmt FROM @stock_corporate_action_ex_rights_price_sql;
EXECUTE stock_corporate_action_ex_rights_price_stmt;
DEALLOCATE PREPARE stock_corporate_action_ex_rights_price_stmt;

SET @stock_corporate_action_paid_dates_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action' AND CONSTRAINT_NAME = 'chk_stock_corporate_action_paid_dates') = 0,
  'ALTER TABLE stock_corporate_action ADD CONSTRAINT chk_stock_corporate_action_paid_dates CHECK (ex_rights_date IS NULL OR payment_date IS NULL OR payment_date >= ex_rights_date)',
  'SELECT 1'
);
PREPARE stock_corporate_action_paid_dates_stmt FROM @stock_corporate_action_paid_dates_sql;
EXECUTE stock_corporate_action_paid_dates_stmt;
DEALLOCATE PREPARE stock_corporate_action_paid_dates_stmt;

SET @stock_corporate_action_listing_dates_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action' AND CONSTRAINT_NAME = 'chk_stock_corporate_action_listing_dates') = 0,
  'ALTER TABLE stock_corporate_action ADD CONSTRAINT chk_stock_corporate_action_listing_dates CHECK (payment_date IS NULL OR listing_date IS NULL OR listing_date >= payment_date)',
  'SELECT 1'
);
PREPARE stock_corporate_action_listing_dates_stmt FROM @stock_corporate_action_listing_dates_sql;
EXECUTE stock_corporate_action_listing_dates_stmt;
DEALLOCATE PREPARE stock_corporate_action_listing_dates_stmt;

SET @stock_corporate_action_split_from_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action' AND CONSTRAINT_NAME = 'chk_stock_corporate_action_split_from') = 0,
  'ALTER TABLE stock_corporate_action ADD CONSTRAINT chk_stock_corporate_action_split_from CHECK (split_from IS NULL OR split_from > 0)',
  'SELECT 1'
);
PREPARE stock_corporate_action_split_from_stmt FROM @stock_corporate_action_split_from_sql;
EXECUTE stock_corporate_action_split_from_stmt;
DEALLOCATE PREPARE stock_corporate_action_split_from_stmt;

SET @stock_corporate_action_split_to_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action' AND CONSTRAINT_NAME = 'chk_stock_corporate_action_split_to') = 0,
  'ALTER TABLE stock_corporate_action ADD CONSTRAINT chk_stock_corporate_action_split_to CHECK (split_to IS NULL OR split_to > 0)',
  'SELECT 1'
);
PREPARE stock_corporate_action_split_to_stmt FROM @stock_corporate_action_split_to_sql;
EXECUTE stock_corporate_action_split_to_stmt;
DEALLOCATE PREPARE stock_corporate_action_split_to_stmt;

SET @stock_corporate_action_issue_required_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action' AND CONSTRAINT_NAME = 'chk_stock_corporate_action_issue_required') = 0,
  'ALTER TABLE stock_corporate_action ADD CONSTRAINT chk_stock_corporate_action_issue_required CHECK (action_type NOT IN (''INITIAL_ISSUE'', ''PAID_IN_CAPITAL_INCREASE'', ''ADDITIONAL_ISSUE'') OR (share_quantity IS NOT NULL AND issue_price IS NOT NULL))',
  'SELECT 1'
);
PREPARE stock_corporate_action_issue_required_stmt FROM @stock_corporate_action_issue_required_sql;
EXECUTE stock_corporate_action_issue_required_stmt;
DEALLOCATE PREPARE stock_corporate_action_issue_required_stmt;

SET @stock_corporate_action_paid_schedule_required_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action' AND CONSTRAINT_NAME = 'chk_stock_corporate_action_paid_schedule_required') = 0,
  'ALTER TABLE stock_corporate_action ADD CONSTRAINT chk_stock_corporate_action_paid_schedule_required CHECK (action_type <> ''PAID_IN_CAPITAL_INCREASE'' OR (base_price IS NOT NULL AND theoretical_ex_rights_price IS NOT NULL AND ex_rights_date IS NOT NULL AND payment_date IS NOT NULL AND listing_date IS NOT NULL))',
  'SELECT 1'
);
PREPARE stock_corporate_action_paid_schedule_required_stmt FROM @stock_corporate_action_paid_schedule_required_sql;
EXECUTE stock_corporate_action_paid_schedule_required_stmt;
DEALLOCATE PREPARE stock_corporate_action_paid_schedule_required_stmt;

SET @stock_corporate_action_additional_listing_required_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action' AND CONSTRAINT_NAME = 'chk_stock_corporate_action_additional_listing_required') = 0,
  'ALTER TABLE stock_corporate_action ADD CONSTRAINT chk_stock_corporate_action_additional_listing_required CHECK (action_type <> ''ADDITIONAL_ISSUE'' OR listing_date IS NOT NULL)',
  'SELECT 1'
);
PREPARE stock_corporate_action_additional_listing_required_stmt FROM @stock_corporate_action_additional_listing_required_sql;
EXECUTE stock_corporate_action_additional_listing_required_stmt;
DEALLOCATE PREPARE stock_corporate_action_additional_listing_required_stmt;

SET @stock_corporate_action_split_required_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action' AND CONSTRAINT_NAME = 'chk_stock_corporate_action_split_required') = 0,
  'ALTER TABLE stock_corporate_action ADD CONSTRAINT chk_stock_corporate_action_split_required CHECK (action_type <> ''STOCK_SPLIT'' OR (split_from IS NOT NULL AND split_to IS NOT NULL AND split_to > split_from AND MOD(split_to, split_from) = 0))',
  'SELECT 1'
);
PREPARE stock_corporate_action_split_required_stmt FROM @stock_corporate_action_split_required_sql;
EXECUTE stock_corporate_action_split_required_stmt;
DEALLOCATE PREPARE stock_corporate_action_split_required_stmt;

SET @stock_corporate_action_dividend_required_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action' AND CONSTRAINT_NAME = 'chk_stock_corporate_action_dividend_required') = 0,
  'ALTER TABLE stock_corporate_action ADD CONSTRAINT chk_stock_corporate_action_dividend_required CHECK (action_type <> ''CASH_DIVIDEND'' OR (dividend_amount IS NOT NULL AND base_price IS NOT NULL AND theoretical_ex_rights_price IS NOT NULL AND ex_rights_date IS NOT NULL AND payment_date IS NOT NULL))',
  'SELECT 1'
);
PREPARE stock_corporate_action_dividend_required_stmt FROM @stock_corporate_action_dividend_required_sql;
EXECUTE stock_corporate_action_dividend_required_stmt;
DEALLOCATE PREPARE stock_corporate_action_dividend_required_stmt;

SET @stock_corporate_action_free_share_required_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action' AND CONSTRAINT_NAME = 'chk_stock_corporate_action_free_share_required') = 0,
  'ALTER TABLE stock_corporate_action ADD CONSTRAINT chk_stock_corporate_action_free_share_required CHECK (action_type NOT IN (''BONUS_ISSUE'', ''STOCK_DIVIDEND'') OR (share_quantity IS NOT NULL AND base_price IS NOT NULL AND theoretical_ex_rights_price IS NOT NULL AND ex_rights_date IS NOT NULL AND listing_date IS NOT NULL))',
  'SELECT 1'
);
PREPARE stock_corporate_action_free_share_required_stmt FROM @stock_corporate_action_free_share_required_sql;
EXECUTE stock_corporate_action_free_share_required_stmt;
DEALLOCATE PREPARE stock_corporate_action_free_share_required_stmt;

SET @stock_corporate_action_field_scope_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action' AND CONSTRAINT_NAME = 'chk_stock_corporate_action_field_scope') = 0,
  'ALTER TABLE stock_corporate_action ADD CONSTRAINT chk_stock_corporate_action_field_scope CHECK ((action_type IN (''INITIAL_ISSUE'', ''PAID_IN_CAPITAL_INCREASE'', ''ADDITIONAL_ISSUE'', ''BONUS_ISSUE'', ''STOCK_DIVIDEND'') OR share_quantity IS NULL) AND (action_type IN (''INITIAL_ISSUE'', ''PAID_IN_CAPITAL_INCREASE'', ''ADDITIONAL_ISSUE'') OR issue_price IS NULL) AND (action_type = ''CASH_DIVIDEND'' OR dividend_amount IS NULL) AND (action_type IN (''PAID_IN_CAPITAL_INCREASE'', ''CASH_DIVIDEND'', ''BONUS_ISSUE'', ''STOCK_DIVIDEND'') OR base_price IS NULL) AND (action_type IN (''PAID_IN_CAPITAL_INCREASE'', ''CASH_DIVIDEND'', ''BONUS_ISSUE'', ''STOCK_DIVIDEND'') OR theoretical_ex_rights_price IS NULL) AND (action_type IN (''PAID_IN_CAPITAL_INCREASE'', ''CASH_DIVIDEND'', ''BONUS_ISSUE'', ''STOCK_DIVIDEND'') OR ex_rights_date IS NULL) AND (action_type IN (''PAID_IN_CAPITAL_INCREASE'', ''CASH_DIVIDEND'') OR payment_date IS NULL) AND (action_type IN (''PAID_IN_CAPITAL_INCREASE'', ''ADDITIONAL_ISSUE'', ''STOCK_SPLIT'', ''BONUS_ISSUE'', ''STOCK_DIVIDEND'') OR listing_date IS NULL) AND (action_type = ''STOCK_SPLIT'' OR (split_from IS NULL AND split_to IS NULL)))',
  'SELECT 1'
);
PREPARE stock_corporate_action_field_scope_stmt FROM @stock_corporate_action_field_scope_sql;
EXECUTE stock_corporate_action_field_scope_stmt;
DEALLOCATE PREPARE stock_corporate_action_field_scope_stmt;

SET @stock_corporate_action_initial_listed_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action' AND CONSTRAINT_NAME = 'chk_stock_corporate_action_initial_listed') = 0,
  'ALTER TABLE stock_corporate_action ADD CONSTRAINT chk_stock_corporate_action_initial_listed CHECK (action_type <> ''INITIAL_ISSUE'' OR (status = ''LISTED'' AND listed_at IS NOT NULL AND applied_at IS NULL AND paid_at IS NULL))',
  'SELECT 1'
);
PREPARE stock_corporate_action_initial_listed_stmt FROM @stock_corporate_action_initial_listed_sql;
EXECUTE stock_corporate_action_initial_listed_stmt;
DEALLOCATE PREPARE stock_corporate_action_initial_listed_stmt;

SET @stock_entitlement_share_constraint_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action_entitlement' AND CONSTRAINT_NAME = 'chk_stock_corporate_action_entitlement_share') = 0,
  'ALTER TABLE stock_corporate_action_entitlement ADD CONSTRAINT chk_stock_corporate_action_entitlement_share CHECK (share_quantity IS NULL OR share_quantity > 0)',
  'SELECT 1'
);
PREPARE stock_entitlement_share_constraint_stmt FROM @stock_entitlement_share_constraint_sql;
EXECUTE stock_entitlement_share_constraint_stmt;
DEALLOCATE PREPARE stock_entitlement_share_constraint_stmt;

SET @stock_entitlement_value_constraint_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action_entitlement' AND CONSTRAINT_NAME = 'chk_stock_corporate_action_entitlement_value') = 0,
  'ALTER TABLE stock_corporate_action_entitlement ADD CONSTRAINT chk_stock_corporate_action_entitlement_value CHECK (cash_amount IS NOT NULL OR share_quantity IS NOT NULL)',
  'SELECT 1'
);
PREPARE stock_entitlement_value_constraint_stmt FROM @stock_entitlement_value_constraint_sql;
EXECUTE stock_entitlement_value_constraint_stmt;
DEALLOCATE PREPARE stock_entitlement_value_constraint_stmt;

CREATE TABLE IF NOT EXISTS stock_auto_participant (
  user_key VARCHAR(64) NOT NULL,
  display_name VARCHAR(80) NOT NULL,
  enabled BIT NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  withdrawn_at DATETIME NULL,
  PRIMARY KEY (user_key)
);

SET @stock_auto_participant_initial_shares_check_drop_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'chk_stock_auto_participant_initial_shares') > 0,
  'ALTER TABLE stock_auto_participant DROP CHECK chk_stock_auto_participant_initial_shares',
  'SELECT 1'
);
PREPARE stock_auto_participant_initial_shares_check_drop_stmt FROM @stock_auto_participant_initial_shares_check_drop_sql;
EXECUTE stock_auto_participant_initial_shares_check_drop_stmt;
DEALLOCATE PREPARE stock_auto_participant_initial_shares_check_drop_stmt;

SET @stock_auto_participant_initial_shares_drop_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_auto_participant' AND COLUMN_NAME = 'initial_shares') > 0,
  'ALTER TABLE stock_auto_participant DROP COLUMN initial_shares',
  'SELECT 1'
);
PREPARE stock_auto_participant_initial_shares_drop_stmt FROM @stock_auto_participant_initial_shares_drop_sql;
EXECUTE stock_auto_participant_initial_shares_drop_stmt;
DEALLOCATE PREPARE stock_auto_participant_initial_shares_drop_stmt;

SET @stock_auto_participant_updated_at_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_auto_participant' AND COLUMN_NAME = 'updated_at') = 0,
  'ALTER TABLE stock_auto_participant ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP',
  'SELECT 1'
);
PREPARE stock_auto_participant_updated_at_stmt FROM @stock_auto_participant_updated_at_sql;
EXECUTE stock_auto_participant_updated_at_stmt;
DEALLOCATE PREPARE stock_auto_participant_updated_at_stmt;

SET @stock_auto_participant_withdrawn_at_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_auto_participant' AND COLUMN_NAME = 'withdrawn_at') = 0,
  'ALTER TABLE stock_auto_participant ADD COLUMN withdrawn_at DATETIME NULL',
  'SELECT 1'
);
PREPARE stock_auto_participant_withdrawn_at_stmt FROM @stock_auto_participant_withdrawn_at_sql;
EXECUTE stock_auto_participant_withdrawn_at_stmt;
DEALLOCATE PREPARE stock_auto_participant_withdrawn_at_stmt;

CREATE TABLE IF NOT EXISTS stock_auto_market_config (
  symbol VARCHAR(20) NOT NULL,
  enabled BIT NOT NULL,
  intensity INT NOT NULL,
  max_order_quantity INT NOT NULL,
  order_ttl_seconds INT NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (symbol),
  KEY idx_stock_auto_market_enabled (enabled, symbol),
  CONSTRAINT chk_stock_auto_market_intensity CHECK (intensity between 1 and 10),
  CONSTRAINT chk_stock_auto_market_max_order_quantity CHECK (max_order_quantity > 0),
  CONSTRAINT chk_stock_auto_market_order_ttl_seconds CHECK (order_ttl_seconds > 0)
);

CREATE TABLE IF NOT EXISTS stock_auto_participant_symbol_config (
  user_key VARCHAR(64) NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  enabled BIT NOT NULL,
  intensity INT NOT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_key, symbol),
  KEY idx_stock_auto_participant_symbol_enabled (enabled, symbol, user_key),
  CONSTRAINT chk_stock_auto_participant_symbol_intensity CHECK (intensity between 1 and 10)
);

SET @stock_auto_participant_symbol_initial_shares_check_drop_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'chk_stock_auto_participant_symbol_initial_shares') > 0,
  'ALTER TABLE stock_auto_participant_symbol_config DROP CHECK chk_stock_auto_participant_symbol_initial_shares',
  'SELECT 1'
);
PREPARE stock_auto_participant_symbol_initial_shares_check_drop_stmt FROM @stock_auto_participant_symbol_initial_shares_check_drop_sql;
EXECUTE stock_auto_participant_symbol_initial_shares_check_drop_stmt;
DEALLOCATE PREPARE stock_auto_participant_symbol_initial_shares_check_drop_stmt;

SET @stock_auto_participant_symbol_initial_shares_drop_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_auto_participant_symbol_config' AND COLUMN_NAME = 'initial_shares') > 0,
  'ALTER TABLE stock_auto_participant_symbol_config DROP COLUMN initial_shares',
  'SELECT 1'
);
PREPARE stock_auto_participant_symbol_initial_shares_drop_stmt FROM @stock_auto_participant_symbol_initial_shares_drop_sql;
EXECUTE stock_auto_participant_symbol_initial_shares_drop_stmt;
DEALLOCATE PREPARE stock_auto_participant_symbol_initial_shares_drop_stmt;

CREATE TABLE IF NOT EXISTS stock_instrument_report_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  symbol VARCHAR(20) NOT NULL,
  event_type VARCHAR(20) NOT NULL,
  title VARCHAR(120) NULL,
  summary VARCHAR(1000) NULL,
  score INT NULL,
  rise_reason VARCHAR(500) NULL,
  fall_reason VARCHAR(500) NULL,
  delete_reason VARCHAR(255) NULL,
  created_by VARCHAR(64) NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_stock_report_symbol_time (symbol, created_at, id),
  CONSTRAINT chk_stock_report_event_type CHECK (CASE `event_type` WHEN 'PUBLISH' THEN 1 WHEN 'UPDATE' THEN 1 WHEN 'DELETE' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_report_score CHECK (score IS NULL OR score between 1 and 10),
  CONSTRAINT chk_stock_report_content_scope CHECK ((event_type = 'DELETE' AND title IS NULL AND summary IS NULL AND score IS NULL AND rise_reason IS NULL AND fall_reason IS NULL) OR (event_type IN ('PUBLISH', 'UPDATE') AND title IS NOT NULL AND summary IS NOT NULL AND score IS NOT NULL AND rise_reason IS NOT NULL AND fall_reason IS NOT NULL))
);

DELETE FROM stock_order_book_market_config
WHERE symbol NOT IN (SELECT symbol FROM stock_order_book_instrument);

DELETE FROM stock_auto_market_config
WHERE symbol NOT IN (SELECT symbol FROM stock_order_book_instrument);

DELETE FROM stock_auto_participant_symbol_config
WHERE symbol NOT IN (SELECT symbol FROM stock_order_book_instrument)
   OR user_key NOT IN (SELECT user_key FROM stock_auto_participant);

-- Account-led trading ledger migration.
-- Existing databases may still have user_key on ledger tables. Keep legacy columns during migration,
-- backfill account_id from stock_account, and let application code use account_id only.
SET @stock_order_account_id_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_order' AND COLUMN_NAME = 'account_id') = 0,
  'ALTER TABLE stock_order ADD COLUMN account_id BIGINT NULL AFTER client_order_id',
  'SELECT 1'
);
PREPARE stock_order_account_id_stmt FROM @stock_order_account_id_sql;
EXECUTE stock_order_account_id_stmt;
DEALLOCATE PREPARE stock_order_account_id_stmt;

SET @stock_order_account_backfill_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_order' AND COLUMN_NAME = 'account_id') = 1
  AND (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_order' AND COLUMN_NAME = 'user_key') = 1,
  'UPDATE stock_order o JOIN stock_account a ON a.user_key = o.user_key SET o.account_id = a.id WHERE o.account_id IS NULL',
  'SELECT 1'
);
PREPARE stock_order_account_backfill_stmt FROM @stock_order_account_backfill_sql;
EXECUTE stock_order_account_backfill_stmt;
DEALLOCATE PREPARE stock_order_account_backfill_stmt;

SET @stock_order_user_key_nullable_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_order' AND COLUMN_NAME = 'user_key' AND IS_NULLABLE = 'NO') = 1,
  'ALTER TABLE stock_order MODIFY COLUMN user_key VARCHAR(64) NULL',
  'SELECT 1'
);
PREPARE stock_order_user_key_nullable_stmt FROM @stock_order_user_key_nullable_sql;
EXECUTE stock_order_user_key_nullable_stmt;
DEALLOCATE PREPARE stock_order_user_key_nullable_stmt;

SET @stock_execution_account_id_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_execution' AND COLUMN_NAME = 'account_id') = 0,
  'ALTER TABLE stock_execution ADD COLUMN account_id BIGINT NULL AFTER order_id',
  'SELECT 1'
);
PREPARE stock_execution_account_id_stmt FROM @stock_execution_account_id_sql;
EXECUTE stock_execution_account_id_stmt;
DEALLOCATE PREPARE stock_execution_account_id_stmt;

SET @stock_execution_account_backfill_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_execution' AND COLUMN_NAME = 'account_id') = 1
  AND (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_execution' AND COLUMN_NAME = 'user_key') = 1,
  'UPDATE stock_execution e JOIN stock_account a ON a.user_key = e.user_key SET e.account_id = a.id WHERE e.account_id IS NULL',
  'SELECT 1'
);
PREPARE stock_execution_account_backfill_stmt FROM @stock_execution_account_backfill_sql;
EXECUTE stock_execution_account_backfill_stmt;
DEALLOCATE PREPARE stock_execution_account_backfill_stmt;

SET @stock_execution_user_key_nullable_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_execution' AND COLUMN_NAME = 'user_key' AND IS_NULLABLE = 'NO') = 1,
  'ALTER TABLE stock_execution MODIFY COLUMN user_key VARCHAR(64) NULL',
  'SELECT 1'
);
PREPARE stock_execution_user_key_nullable_stmt FROM @stock_execution_user_key_nullable_sql;
EXECUTE stock_execution_user_key_nullable_stmt;
DEALLOCATE PREPARE stock_execution_user_key_nullable_stmt;

SET @stock_holding_account_id_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_holding' AND COLUMN_NAME = 'account_id') = 0,
  'ALTER TABLE stock_holding ADD COLUMN account_id BIGINT NULL AFTER id',
  'SELECT 1'
);
PREPARE stock_holding_account_id_stmt FROM @stock_holding_account_id_sql;
EXECUTE stock_holding_account_id_stmt;
DEALLOCATE PREPARE stock_holding_account_id_stmt;

SET @stock_holding_account_backfill_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_holding' AND COLUMN_NAME = 'account_id') = 1
  AND (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_holding' AND COLUMN_NAME = 'user_key') = 1,
  'UPDATE stock_holding h JOIN stock_account a ON a.user_key = h.user_key SET h.account_id = a.id WHERE h.account_id IS NULL',
  'SELECT 1'
);
PREPARE stock_holding_account_backfill_stmt FROM @stock_holding_account_backfill_sql;
EXECUTE stock_holding_account_backfill_stmt;
DEALLOCATE PREPARE stock_holding_account_backfill_stmt;

SET @stock_holding_user_key_nullable_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_holding' AND COLUMN_NAME = 'user_key' AND IS_NULLABLE = 'NO') = 1,
  'ALTER TABLE stock_holding MODIFY COLUMN user_key VARCHAR(64) NULL',
  'SELECT 1'
);
PREPARE stock_holding_user_key_nullable_stmt FROM @stock_holding_user_key_nullable_sql;
EXECUTE stock_holding_user_key_nullable_stmt;
DEALLOCATE PREPARE stock_holding_user_key_nullable_stmt;

SET @portfolio_snapshot_account_id_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'portfolio_snapshot' AND COLUMN_NAME = 'account_id') = 0,
  'ALTER TABLE portfolio_snapshot ADD COLUMN account_id BIGINT NULL AFTER id',
  'SELECT 1'
);
PREPARE portfolio_snapshot_account_id_stmt FROM @portfolio_snapshot_account_id_sql;
EXECUTE portfolio_snapshot_account_id_stmt;
DEALLOCATE PREPARE portfolio_snapshot_account_id_stmt;

SET @portfolio_snapshot_account_backfill_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'portfolio_snapshot' AND COLUMN_NAME = 'account_id') = 1
  AND (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'portfolio_snapshot' AND COLUMN_NAME = 'user_key') = 1,
  'UPDATE portfolio_snapshot ps JOIN stock_account a ON a.user_key = ps.user_key SET ps.account_id = a.id WHERE ps.account_id IS NULL',
  'SELECT 1'
);
PREPARE portfolio_snapshot_account_backfill_stmt FROM @portfolio_snapshot_account_backfill_sql;
EXECUTE portfolio_snapshot_account_backfill_stmt;
DEALLOCATE PREPARE portfolio_snapshot_account_backfill_stmt;

SET @portfolio_snapshot_user_key_nullable_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'portfolio_snapshot' AND COLUMN_NAME = 'user_key' AND IS_NULLABLE = 'NO') = 1,
  'ALTER TABLE portfolio_snapshot MODIFY COLUMN user_key VARCHAR(64) NULL',
  'SELECT 1'
);
PREPARE portfolio_snapshot_user_key_nullable_stmt FROM @portfolio_snapshot_user_key_nullable_sql;
EXECUTE portfolio_snapshot_user_key_nullable_stmt;
DEALLOCATE PREPARE portfolio_snapshot_user_key_nullable_stmt;

SET @stock_entitlement_account_id_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action_entitlement' AND COLUMN_NAME = 'account_id') = 0,
  'ALTER TABLE stock_corporate_action_entitlement ADD COLUMN account_id BIGINT NULL AFTER action_id',
  'SELECT 1'
);
PREPARE stock_entitlement_account_id_stmt FROM @stock_entitlement_account_id_sql;
EXECUTE stock_entitlement_account_id_stmt;
DEALLOCATE PREPARE stock_entitlement_account_id_stmt;

SET @stock_entitlement_account_backfill_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action_entitlement' AND COLUMN_NAME = 'account_id') = 1
  AND (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action_entitlement' AND COLUMN_NAME = 'user_key') = 1,
  'UPDATE stock_corporate_action_entitlement e JOIN stock_account a ON a.user_key = e.user_key SET e.account_id = a.id WHERE e.account_id IS NULL',
  'SELECT 1'
);
PREPARE stock_entitlement_account_backfill_stmt FROM @stock_entitlement_account_backfill_sql;
EXECUTE stock_entitlement_account_backfill_stmt;
DEALLOCATE PREPARE stock_entitlement_account_backfill_stmt;

SET @stock_entitlement_user_key_nullable_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action_entitlement' AND COLUMN_NAME = 'user_key' AND IS_NULLABLE = 'NO') = 1,
  'ALTER TABLE stock_corporate_action_entitlement MODIFY COLUMN user_key VARCHAR(64) NULL',
  'SELECT 1'
);
PREPARE stock_entitlement_user_key_nullable_stmt FROM @stock_entitlement_user_key_nullable_sql;
EXECUTE stock_entitlement_user_key_nullable_stmt;
DEALLOCATE PREPARE stock_entitlement_user_key_nullable_stmt;

SET @idx_stock_order_account_created_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_order' AND INDEX_NAME = 'idx_stock_order_account_created') = 0,
  'CREATE INDEX idx_stock_order_account_created ON stock_order(account_id, created_at)',
  'SELECT 1'
);
PREPARE idx_stock_order_account_created_stmt FROM @idx_stock_order_account_created_sql;
EXECUTE idx_stock_order_account_created_stmt;
DEALLOCATE PREPARE idx_stock_order_account_created_stmt;

SET @idx_stock_order_account_status_created_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_order' AND INDEX_NAME = 'idx_stock_order_account_status_created') = 0,
  'CREATE INDEX idx_stock_order_account_status_created ON stock_order(account_id, status, created_at)',
  'SELECT 1'
);
PREPARE idx_stock_order_account_status_created_stmt FROM @idx_stock_order_account_status_created_sql;
EXECUTE idx_stock_order_account_status_created_stmt;
DEALLOCATE PREPARE idx_stock_order_account_status_created_stmt;

SET @idx_stock_execution_account_time_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_execution' AND INDEX_NAME = 'idx_stock_execution_account_time') = 0,
  'CREATE INDEX idx_stock_execution_account_time ON stock_execution(account_id, executed_at)',
  'SELECT 1'
);
PREPARE idx_stock_execution_account_time_stmt FROM @idx_stock_execution_account_time_sql;
EXECUTE idx_stock_execution_account_time_stmt;
DEALLOCATE PREPARE idx_stock_execution_account_time_stmt;

SET @uk_stock_holding_account_symbol_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_holding' AND INDEX_NAME = 'uk_stock_holding_account_symbol') = 0,
  'CREATE UNIQUE INDEX uk_stock_holding_account_symbol ON stock_holding(account_id, symbol)',
  'SELECT 1'
);
PREPARE uk_stock_holding_account_symbol_stmt FROM @uk_stock_holding_account_symbol_sql;
EXECUTE uk_stock_holding_account_symbol_stmt;
DEALLOCATE PREPARE uk_stock_holding_account_symbol_stmt;

SET @uk_portfolio_snapshot_account_date_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'portfolio_snapshot' AND INDEX_NAME = 'uk_portfolio_snapshot_account_date') = 0,
  'CREATE UNIQUE INDEX uk_portfolio_snapshot_account_date ON portfolio_snapshot(account_id, snapshot_date)',
  'SELECT 1'
);
PREPARE uk_portfolio_snapshot_account_date_stmt FROM @uk_portfolio_snapshot_account_date_sql;
EXECUTE uk_portfolio_snapshot_account_date_stmt;
DEALLOCATE PREPARE uk_portfolio_snapshot_account_date_stmt;

SET @idx_stock_entitlement_account_created_sql := IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_corporate_action_entitlement' AND INDEX_NAME = 'idx_stock_corporate_action_entitlement_account_created') = 0,
  'CREATE INDEX idx_stock_corporate_action_entitlement_account_created ON stock_corporate_action_entitlement(account_id, created_at)',
  'SELECT 1'
);
PREPARE idx_stock_entitlement_account_created_stmt FROM @idx_stock_entitlement_account_created_sql;
EXECUTE idx_stock_entitlement_account_created_stmt;
DEALLOCATE PREPARE idx_stock_entitlement_account_created_stmt;
