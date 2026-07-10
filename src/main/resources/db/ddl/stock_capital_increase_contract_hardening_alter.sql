USE STOCK_SERVICE;

SET @stock_corporate_action_created_index_exists := (
  SELECT COUNT(*)
    FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_corporate_action'
     AND index_name = 'idx_stock_corporate_action_created'
);

SET @stock_corporate_action_created_index_sql := IF(
  @stock_corporate_action_created_index_exists = 0,
  'ALTER TABLE stock_corporate_action ADD INDEX idx_stock_corporate_action_created (created_at, id)',
  'SELECT 1'
);

PREPARE stock_corporate_action_created_index_stmt FROM @stock_corporate_action_created_index_sql;
EXECUTE stock_corporate_action_created_index_stmt;
DEALLOCATE PREPARE stock_corporate_action_created_index_stmt;

SET @stock_corporate_action_type_created_index_exists := (
  SELECT COUNT(*)
    FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_corporate_action'
     AND index_name = 'idx_stock_corporate_action_type_created'
);

SET @stock_corporate_action_type_created_index_sql := IF(
  @stock_corporate_action_type_created_index_exists = 0,
  'ALTER TABLE stock_corporate_action ADD INDEX idx_stock_corporate_action_type_created (action_type, created_at, id)',
  'SELECT 1'
);

PREPARE stock_corporate_action_type_created_index_stmt FROM @stock_corporate_action_type_created_index_sql;
EXECUTE stock_corporate_action_type_created_index_stmt;
DEALLOCATE PREPARE stock_corporate_action_type_created_index_stmt;

SET @stock_paid_in_invalid_schedule_count := (
  SELECT COUNT(*)
    FROM stock_corporate_action
   WHERE action_type = 'PAID_IN_CAPITAL_INCREASE'
     AND (
       offering_type IS NULL
       OR subscription_start_date IS NULL
       OR subscription_end_date IS NULL
       OR payment_date IS NULL
       OR listing_date IS NULL
       OR subscription_end_date < subscription_start_date
       OR payment_date <= subscription_end_date
       OR listing_date <= payment_date
       OR (
         offering_type = 'SHAREHOLDER_ALLOCATION'
         AND (ex_rights_date IS NULL OR subscription_start_date <= ex_rights_date)
       )
     )
);

-- PREPARE cannot execute SIGNAL; select a descriptive non-existent table to abort an unsafe migration.
SET @stock_paid_in_invalid_schedule_guard_sql := IF(
  @stock_paid_in_invalid_schedule_count > 0,
  'SELECT 1 FROM stock_migration_required_paid_in_schedule',
  'SELECT 1'
);

PREPARE stock_paid_in_invalid_schedule_guard_stmt FROM @stock_paid_in_invalid_schedule_guard_sql;
EXECUTE stock_paid_in_invalid_schedule_guard_stmt;
DEALLOCATE PREPARE stock_paid_in_invalid_schedule_guard_stmt;

SET @stock_corporate_action_paid_date_order_check_exists := (
  SELECT COUNT(*)
    FROM information_schema.check_constraints
   WHERE constraint_schema = DATABASE()
     AND constraint_name = 'chk_stock_corporate_action_paid_date_order'
);

SET @stock_corporate_action_paid_date_order_check_drop_sql := IF(
  @stock_corporate_action_paid_date_order_check_exists > 0,
  'ALTER TABLE stock_corporate_action DROP CHECK chk_stock_corporate_action_paid_date_order',
  'SELECT 1'
);

PREPARE stock_corporate_action_paid_date_order_check_drop_stmt FROM @stock_corporate_action_paid_date_order_check_drop_sql;
EXECUTE stock_corporate_action_paid_date_order_check_drop_stmt;
DEALLOCATE PREPARE stock_corporate_action_paid_date_order_check_drop_stmt;

ALTER TABLE stock_corporate_action
  ADD CONSTRAINT chk_stock_corporate_action_paid_date_order CHECK (
    action_type <> 'PAID_IN_CAPITAL_INCREASE'
    OR (
      subscription_end_date >= subscription_start_date
      AND payment_date > subscription_end_date
      AND listing_date > payment_date
      AND (offering_type <> 'SHAREHOLDER_ALLOCATION' OR subscription_start_date > ex_rights_date)
    )
  );

SET @stock_entitlement_subscribed_share_limit_violation_count := (
  SELECT COUNT(*)
    FROM stock_corporate_action_entitlement
   WHERE subscribed_share_quantity IS NOT NULL
     AND share_quantity IS NOT NULL
     AND subscribed_share_quantity > share_quantity
);

-- PREPARE cannot execute SIGNAL; select a descriptive non-existent table to abort an unsafe migration.
SET @stock_entitlement_subscribed_share_limit_guard_sql := IF(
  @stock_entitlement_subscribed_share_limit_violation_count > 0,
  'SELECT 1 FROM stock_migration_required_entitlement_share_limit',
  'SELECT 1'
);

PREPARE stock_entitlement_subscribed_share_limit_guard_stmt FROM @stock_entitlement_subscribed_share_limit_guard_sql;
EXECUTE stock_entitlement_subscribed_share_limit_guard_stmt;
DEALLOCATE PREPARE stock_entitlement_subscribed_share_limit_guard_stmt;

SET @stock_entitlement_subscribed_share_limit_check_exists := (
  SELECT COUNT(*)
    FROM information_schema.check_constraints
   WHERE constraint_schema = DATABASE()
     AND constraint_name = 'chk_stock_corporate_action_entitlement_subscribed_share_limit'
);

SET @stock_entitlement_subscribed_share_limit_check_drop_sql := IF(
  @stock_entitlement_subscribed_share_limit_check_exists > 0,
  'ALTER TABLE stock_corporate_action_entitlement DROP CHECK chk_stock_corporate_action_entitlement_subscribed_share_limit',
  'SELECT 1'
);

PREPARE stock_entitlement_subscribed_share_limit_check_drop_stmt FROM @stock_entitlement_subscribed_share_limit_check_drop_sql;
EXECUTE stock_entitlement_subscribed_share_limit_check_drop_stmt;
DEALLOCATE PREPARE stock_entitlement_subscribed_share_limit_check_drop_stmt;

ALTER TABLE stock_corporate_action_entitlement
  ADD CONSTRAINT chk_stock_corporate_action_entitlement_subscribed_share_limit CHECK (
    subscribed_share_quantity IS NULL
    OR share_quantity IS NULL
    OR subscribed_share_quantity <= share_quantity
  );

CREATE TABLE IF NOT EXISTS stock_auto_participant_event_profile_config (
  profile_type VARCHAR(40) NOT NULL,
  shareholder_subscription_rate DECIMAL(8,4) NOT NULL DEFAULT 0.4500,
  public_offering_subscription_rate DECIMAL(8,4) NOT NULL DEFAULT 0.2000,
  max_cash_allocation_rate DECIMAL(8,4) NOT NULL DEFAULT 0.2000,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (profile_type),
  CONSTRAINT chk_stock_auto_event_profile_type CHECK (
    CASE `profile_type`
      WHEN 'NEWS_REACTIVE' THEN 1
      WHEN 'MOMENTUM_FOLLOWER' THEN 1
      WHEN 'CONTRARIAN' THEN 1
      WHEN 'LOSS_AVERSE' THEN 1
      WHEN 'OVERCONFIDENT' THEN 1
      WHEN 'HERD_FOLLOWER' THEN 1
      WHEN 'MARKET_MAKER' THEN 1
      WHEN 'NOISE_TRADER' THEN 1
      WHEN 'VALUE_ANCHOR' THEN 1
      WHEN 'SCALPER' THEN 1
      WHEN 'DAY_TRADER' THEN 1
      WHEN 'SWING_TRADER' THEN 1
      WHEN 'LONG_TERM_HOLDER' THEN 1
      WHEN 'PAYDAY_ACCUMULATOR' THEN 1
      WHEN 'DIVIDEND_REINVESTOR' THEN 1
      WHEN 'LIMIT_DOWN_TRAPPED' THEN 1
      WHEN 'AVERAGE_DOWN_BUYER' THEN 1
      WHEN 'STOP_LOSS_TRADER' THEN 1
      WHEN 'FOMO_BUYER' THEN 1
      WHEN 'PANIC_SELLER' THEN 1
      WHEN 'DIP_BUYER' THEN 1
      WHEN 'PROFIT_LOCKER' THEN 1
      WHEN 'LIQUIDITY_AVOIDANT' THEN 1
      WHEN 'CASH_DEFENSIVE' THEN 1
      WHEN 'WHALE' THEN 1
      WHEN 'SMALL_DIVERSIFIER' THEN 1
      WHEN 'OBSERVER' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_auto_event_profile_shareholder_rate CHECK (shareholder_subscription_rate >= 0 AND shareholder_subscription_rate <= 1),
  CONSTRAINT chk_stock_auto_event_profile_public_rate CHECK (public_offering_subscription_rate >= 0 AND public_offering_subscription_rate <= 1),
  CONSTRAINT chk_stock_auto_event_profile_cash_rate CHECK (max_cash_allocation_rate >= 0 AND max_cash_allocation_rate <= 1)
);

SET @stock_auto_event_profile_invalid_type_count := (
  SELECT COUNT(*)
    FROM stock_auto_participant_event_profile_config
   WHERE profile_type NOT IN (
     'NEWS_REACTIVE', 'MOMENTUM_FOLLOWER', 'CONTRARIAN', 'LOSS_AVERSE',
     'OVERCONFIDENT', 'HERD_FOLLOWER', 'MARKET_MAKER', 'NOISE_TRADER',
     'VALUE_ANCHOR', 'SCALPER', 'DAY_TRADER', 'SWING_TRADER',
     'LONG_TERM_HOLDER', 'PAYDAY_ACCUMULATOR', 'DIVIDEND_REINVESTOR',
     'LIMIT_DOWN_TRAPPED', 'AVERAGE_DOWN_BUYER', 'STOP_LOSS_TRADER',
     'FOMO_BUYER', 'PANIC_SELLER', 'DIP_BUYER', 'PROFIT_LOCKER',
     'LIQUIDITY_AVOIDANT', 'CASH_DEFENSIVE', 'WHALE', 'SMALL_DIVERSIFIER', 'OBSERVER'
   )
);

-- PREPARE cannot execute SIGNAL; select a descriptive non-existent table to abort an unsafe migration.
SET @stock_auto_event_profile_invalid_type_guard_sql := IF(
  @stock_auto_event_profile_invalid_type_count > 0,
  'SELECT 1 FROM stock_migration_required_event_profile_type',
  'SELECT 1'
);

PREPARE stock_auto_event_profile_invalid_type_guard_stmt FROM @stock_auto_event_profile_invalid_type_guard_sql;
EXECUTE stock_auto_event_profile_invalid_type_guard_stmt;
DEALLOCATE PREPARE stock_auto_event_profile_invalid_type_guard_stmt;

SET @stock_auto_event_profile_type_check_exists := (
  SELECT COUNT(*)
    FROM information_schema.check_constraints
   WHERE constraint_schema = DATABASE()
     AND constraint_name = 'chk_stock_auto_event_profile_type'
);

SET @stock_auto_event_profile_type_check_drop_sql := IF(
  @stock_auto_event_profile_type_check_exists > 0,
  'ALTER TABLE stock_auto_participant_event_profile_config DROP CHECK chk_stock_auto_event_profile_type',
  'SELECT 1'
);

PREPARE stock_auto_event_profile_type_check_drop_stmt FROM @stock_auto_event_profile_type_check_drop_sql;
EXECUTE stock_auto_event_profile_type_check_drop_stmt;
DEALLOCATE PREPARE stock_auto_event_profile_type_check_drop_stmt;

ALTER TABLE stock_auto_participant_event_profile_config
  ADD CONSTRAINT chk_stock_auto_event_profile_type CHECK (
    CASE `profile_type`
      WHEN 'NEWS_REACTIVE' THEN 1
      WHEN 'MOMENTUM_FOLLOWER' THEN 1
      WHEN 'CONTRARIAN' THEN 1
      WHEN 'LOSS_AVERSE' THEN 1
      WHEN 'OVERCONFIDENT' THEN 1
      WHEN 'HERD_FOLLOWER' THEN 1
      WHEN 'MARKET_MAKER' THEN 1
      WHEN 'NOISE_TRADER' THEN 1
      WHEN 'VALUE_ANCHOR' THEN 1
      WHEN 'SCALPER' THEN 1
      WHEN 'DAY_TRADER' THEN 1
      WHEN 'SWING_TRADER' THEN 1
      WHEN 'LONG_TERM_HOLDER' THEN 1
      WHEN 'PAYDAY_ACCUMULATOR' THEN 1
      WHEN 'DIVIDEND_REINVESTOR' THEN 1
      WHEN 'LIMIT_DOWN_TRAPPED' THEN 1
      WHEN 'AVERAGE_DOWN_BUYER' THEN 1
      WHEN 'STOP_LOSS_TRADER' THEN 1
      WHEN 'FOMO_BUYER' THEN 1
      WHEN 'PANIC_SELLER' THEN 1
      WHEN 'DIP_BUYER' THEN 1
      WHEN 'PROFIT_LOCKER' THEN 1
      WHEN 'LIQUIDITY_AVOIDANT' THEN 1
      WHEN 'CASH_DEFENSIVE' THEN 1
      WHEN 'WHALE' THEN 1
      WHEN 'SMALL_DIVERSIFIER' THEN 1
      WHEN 'OBSERVER' THEN 1
      ELSE 0
    END = 1
  );
