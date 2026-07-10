USE STOCK_SERVICE;

SET @stock_corporate_action_offering_type_column_exists := (
  SELECT COUNT(*)
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_corporate_action'
     AND column_name = 'offering_type'
);

SET @stock_corporate_action_offering_type_column_sql := IF(
  @stock_corporate_action_offering_type_column_exists = 0,
  'ALTER TABLE stock_corporate_action ADD COLUMN offering_type VARCHAR(40) NULL AFTER delisting_date',
  'SELECT 1'
);

PREPARE stock_corporate_action_offering_type_column_stmt FROM @stock_corporate_action_offering_type_column_sql;
EXECUTE stock_corporate_action_offering_type_column_stmt;
DEALLOCATE PREPARE stock_corporate_action_offering_type_column_stmt;

SET @stock_corporate_action_subscription_start_column_exists := (
  SELECT COUNT(*)
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_corporate_action'
     AND column_name = 'subscription_start_date'
);

SET @stock_corporate_action_subscription_start_column_sql := IF(
  @stock_corporate_action_subscription_start_column_exists = 0,
  'ALTER TABLE stock_corporate_action ADD COLUMN subscription_start_date DATE NULL AFTER offering_type',
  'SELECT 1'
);

PREPARE stock_corporate_action_subscription_start_column_stmt FROM @stock_corporate_action_subscription_start_column_sql;
EXECUTE stock_corporate_action_subscription_start_column_stmt;
DEALLOCATE PREPARE stock_corporate_action_subscription_start_column_stmt;

SET @stock_corporate_action_subscription_end_column_exists := (
  SELECT COUNT(*)
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_corporate_action'
     AND column_name = 'subscription_end_date'
);

SET @stock_corporate_action_subscription_end_column_sql := IF(
  @stock_corporate_action_subscription_end_column_exists = 0,
  'ALTER TABLE stock_corporate_action ADD COLUMN subscription_end_date DATE NULL AFTER subscription_start_date',
  'SELECT 1'
);

PREPARE stock_corporate_action_subscription_end_column_stmt FROM @stock_corporate_action_subscription_end_column_sql;
EXECUTE stock_corporate_action_subscription_end_column_stmt;
DEALLOCATE PREPARE stock_corporate_action_subscription_end_column_stmt;

SET @stock_legacy_paid_in_unsafe_count := (
  SELECT COUNT(*)
    FROM stock_corporate_action
   WHERE action_type = 'PAID_IN_CAPITAL_INCREASE'
     AND offering_type IS NULL
     AND (
       status NOT IN ('ANNOUNCED', 'LISTED')
       OR subscription_start_date IS NOT NULL
       OR subscription_end_date IS NOT NULL
       OR ex_rights_date IS NULL
       OR payment_date IS NULL
       OR listing_date IS NULL
       OR base_price IS NULL
       OR theoretical_ex_rights_price IS NULL
       OR DATE_ADD(ex_rights_date, INTERVAL 1 DAY) > DATE_SUB(payment_date, INTERVAL 1 DAY)
       OR listing_date <= payment_date
     )
);

-- PREPARE cannot execute SIGNAL; select a descriptive non-existent table to abort an unsafe migration.
SET @stock_legacy_paid_in_guard_sql := IF(
  @stock_legacy_paid_in_unsafe_count > 0,
  'SELECT 1 FROM stock_migration_required_legacy_paid_in_entitlements',
  'SELECT 1'
);

PREPARE stock_legacy_paid_in_guard_stmt FROM @stock_legacy_paid_in_guard_sql;
EXECUTE stock_legacy_paid_in_guard_stmt;
DEALLOCATE PREPARE stock_legacy_paid_in_guard_stmt;

UPDATE stock_corporate_action
   SET offering_type = 'SHAREHOLDER_ALLOCATION',
       subscription_start_date = DATE_ADD(ex_rights_date, INTERVAL 1 DAY),
       subscription_end_date = DATE_SUB(payment_date, INTERVAL 1 DAY)
 WHERE action_type = 'PAID_IN_CAPITAL_INCREASE'
   AND offering_type IS NULL
   AND subscription_start_date IS NULL
   AND subscription_end_date IS NULL
   AND status IN ('ANNOUNCED', 'LISTED');

SET @stock_paid_in_incomplete_contract_count := (
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
SET @stock_paid_in_incomplete_contract_guard_sql := IF(
  @stock_paid_in_incomplete_contract_count > 0,
  'SELECT 1 FROM stock_migration_required_paid_in_schedule',
  'SELECT 1'
);

PREPARE stock_paid_in_incomplete_contract_guard_stmt FROM @stock_paid_in_incomplete_contract_guard_sql;
EXECUTE stock_paid_in_incomplete_contract_guard_stmt;
DEALLOCATE PREPARE stock_paid_in_incomplete_contract_guard_stmt;

SET @stock_corporate_action_type_valid_check_exists := (
  SELECT COUNT(*)
    FROM information_schema.check_constraints
   WHERE constraint_schema = DATABASE()
     AND constraint_name = 'chk_stock_corporate_action_type_valid'
);

SET @stock_corporate_action_type_valid_check_drop_sql := IF(
  @stock_corporate_action_type_valid_check_exists > 0,
  'ALTER TABLE stock_corporate_action DROP CHECK chk_stock_corporate_action_type_valid',
  'SELECT 1'
);

PREPARE stock_corporate_action_type_valid_check_drop_stmt FROM @stock_corporate_action_type_valid_check_drop_sql;
EXECUTE stock_corporate_action_type_valid_check_drop_stmt;
DEALLOCATE PREPARE stock_corporate_action_type_valid_check_drop_stmt;

ALTER TABLE stock_corporate_action
  ADD CONSTRAINT chk_stock_corporate_action_type_valid CHECK (
    CASE `action_type`
      WHEN 'INITIAL_ISSUE' THEN 1
      WHEN 'PAID_IN_CAPITAL_INCREASE' THEN 1
      WHEN 'STOCK_SPLIT' THEN 1
      WHEN 'CASH_DIVIDEND' THEN 1
      WHEN 'BONUS_ISSUE' THEN 1
      WHEN 'STOCK_DIVIDEND' THEN 1
      WHEN 'DELISTING' THEN 1
      ELSE 0
    END = 1
  );

SET @stock_corporate_action_additional_listing_check_exists := (
  SELECT COUNT(*)
    FROM information_schema.check_constraints
   WHERE constraint_schema = DATABASE()
     AND constraint_name = 'chk_stock_corporate_action_additional_listing_required'
);

SET @stock_corporate_action_additional_listing_check_drop_sql := IF(
  @stock_corporate_action_additional_listing_check_exists > 0,
  'ALTER TABLE stock_corporate_action DROP CHECK chk_stock_corporate_action_additional_listing_required',
  'SELECT 1'
);

PREPARE stock_corporate_action_additional_listing_check_drop_stmt FROM @stock_corporate_action_additional_listing_check_drop_sql;
EXECUTE stock_corporate_action_additional_listing_check_drop_stmt;
DEALLOCATE PREPARE stock_corporate_action_additional_listing_check_drop_stmt;

SET @stock_corporate_action_offering_type_check_exists := (
  SELECT COUNT(*)
    FROM information_schema.check_constraints
   WHERE constraint_schema = DATABASE()
     AND constraint_name = 'chk_stock_corporate_action_offering_type'
);

SET @stock_corporate_action_offering_type_check_drop_sql := IF(
  @stock_corporate_action_offering_type_check_exists > 0,
  'ALTER TABLE stock_corporate_action DROP CHECK chk_stock_corporate_action_offering_type',
  'SELECT 1'
);

PREPARE stock_corporate_action_offering_type_check_drop_stmt FROM @stock_corporate_action_offering_type_check_drop_sql;
EXECUTE stock_corporate_action_offering_type_check_drop_stmt;
DEALLOCATE PREPARE stock_corporate_action_offering_type_check_drop_stmt;

ALTER TABLE stock_corporate_action
  ADD CONSTRAINT chk_stock_corporate_action_offering_type CHECK (
    offering_type IS NULL
    OR CASE `offering_type`
      WHEN 'SHAREHOLDER_ALLOCATION' THEN 1
      WHEN 'PUBLIC_OFFERING' THEN 1
      ELSE 0
    END = 1
  );

SET @stock_corporate_action_subscription_dates_check_exists := (
  SELECT COUNT(*)
    FROM information_schema.check_constraints
   WHERE constraint_schema = DATABASE()
     AND constraint_name = 'chk_stock_corporate_action_subscription_dates'
);

SET @stock_corporate_action_subscription_dates_check_drop_sql := IF(
  @stock_corporate_action_subscription_dates_check_exists > 0,
  'ALTER TABLE stock_corporate_action DROP CHECK chk_stock_corporate_action_subscription_dates',
  'SELECT 1'
);

PREPARE stock_corporate_action_subscription_dates_check_drop_stmt FROM @stock_corporate_action_subscription_dates_check_drop_sql;
EXECUTE stock_corporate_action_subscription_dates_check_drop_stmt;
DEALLOCATE PREPARE stock_corporate_action_subscription_dates_check_drop_stmt;

ALTER TABLE stock_corporate_action
  ADD CONSTRAINT chk_stock_corporate_action_subscription_dates CHECK (
    subscription_start_date IS NULL
    OR subscription_end_date IS NULL
    OR subscription_end_date >= subscription_start_date
  );

SET @stock_corporate_action_paid_schedule_required_check_exists := (
  SELECT COUNT(*)
    FROM information_schema.check_constraints
   WHERE constraint_schema = DATABASE()
     AND constraint_name = 'chk_stock_corporate_action_paid_schedule_required'
);

SET @stock_corporate_action_paid_schedule_required_check_drop_sql := IF(
  @stock_corporate_action_paid_schedule_required_check_exists > 0,
  'ALTER TABLE stock_corporate_action DROP CHECK chk_stock_corporate_action_paid_schedule_required',
  'SELECT 1'
);

PREPARE stock_corporate_action_paid_schedule_required_check_drop_stmt FROM @stock_corporate_action_paid_schedule_required_check_drop_sql;
EXECUTE stock_corporate_action_paid_schedule_required_check_drop_stmt;
DEALLOCATE PREPARE stock_corporate_action_paid_schedule_required_check_drop_stmt;

ALTER TABLE stock_corporate_action
  ADD CONSTRAINT chk_stock_corporate_action_paid_schedule_required CHECK (
    action_type <> 'PAID_IN_CAPITAL_INCREASE'
    OR (
      offering_type IS NOT NULL
      AND subscription_start_date IS NOT NULL
      AND subscription_end_date IS NOT NULL
      AND payment_date IS NOT NULL
      AND listing_date IS NOT NULL
      AND (
        (
          offering_type = 'SHAREHOLDER_ALLOCATION'
          AND base_price IS NOT NULL
          AND theoretical_ex_rights_price IS NOT NULL
          AND ex_rights_date IS NOT NULL
        )
        OR (
          offering_type = 'PUBLIC_OFFERING'
          AND ex_rights_date IS NULL
          AND theoretical_ex_rights_price IS NULL
        )
      )
    )
  );

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

SET @stock_corporate_action_field_scope_check_exists := (
  SELECT COUNT(*)
    FROM information_schema.check_constraints
   WHERE constraint_schema = DATABASE()
     AND constraint_name = 'chk_stock_corporate_action_field_scope'
);

SET @stock_corporate_action_field_scope_check_drop_sql := IF(
  @stock_corporate_action_field_scope_check_exists > 0,
  'ALTER TABLE stock_corporate_action DROP CHECK chk_stock_corporate_action_field_scope',
  'SELECT 1'
);

PREPARE stock_corporate_action_field_scope_check_drop_stmt FROM @stock_corporate_action_field_scope_check_drop_sql;
EXECUTE stock_corporate_action_field_scope_check_drop_stmt;
DEALLOCATE PREPARE stock_corporate_action_field_scope_check_drop_stmt;

ALTER TABLE stock_corporate_action
  ADD CONSTRAINT chk_stock_corporate_action_field_scope CHECK (
    (action_type IN ('INITIAL_ISSUE', 'PAID_IN_CAPITAL_INCREASE', 'BONUS_ISSUE', 'STOCK_DIVIDEND') OR share_quantity IS NULL)
    AND (action_type IN ('INITIAL_ISSUE', 'PAID_IN_CAPITAL_INCREASE') OR issue_price IS NULL)
    AND (action_type = 'PAID_IN_CAPITAL_INCREASE' OR offering_type IS NULL)
    AND (action_type = 'PAID_IN_CAPITAL_INCREASE' OR subscription_start_date IS NULL)
    AND (action_type = 'PAID_IN_CAPITAL_INCREASE' OR subscription_end_date IS NULL)
    AND (action_type = 'CASH_DIVIDEND' OR dividend_amount IS NULL)
    AND (action_type IN ('PAID_IN_CAPITAL_INCREASE', 'CASH_DIVIDEND', 'BONUS_ISSUE', 'STOCK_DIVIDEND') OR base_price IS NULL)
    AND (action_type IN ('PAID_IN_CAPITAL_INCREASE', 'CASH_DIVIDEND', 'BONUS_ISSUE', 'STOCK_DIVIDEND') OR theoretical_ex_rights_price IS NULL)
    AND (action_type IN ('PAID_IN_CAPITAL_INCREASE', 'CASH_DIVIDEND', 'BONUS_ISSUE', 'STOCK_DIVIDEND') OR ex_rights_date IS NULL)
    AND (action_type IN ('PAID_IN_CAPITAL_INCREASE', 'CASH_DIVIDEND') OR payment_date IS NULL)
    AND (action_type IN ('PAID_IN_CAPITAL_INCREASE', 'STOCK_SPLIT', 'BONUS_ISSUE', 'STOCK_DIVIDEND') OR listing_date IS NULL)
    AND (action_type = 'DELISTING' OR delisting_date IS NULL)
    AND (action_type = 'DELISTING' OR delisting_treatment IS NULL)
    AND (action_type = 'STOCK_SPLIT' OR (split_from IS NULL AND split_to IS NULL))
  );

SET @stock_entitlement_subscribed_share_column_exists := (
  SELECT COUNT(*)
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_corporate_action_entitlement'
     AND column_name = 'subscribed_share_quantity'
);

SET @stock_entitlement_subscribed_share_column_sql := IF(
  @stock_entitlement_subscribed_share_column_exists = 0,
  'ALTER TABLE stock_corporate_action_entitlement ADD COLUMN subscribed_share_quantity BIGINT NULL AFTER cash_amount',
  'SELECT 1'
);

PREPARE stock_entitlement_subscribed_share_column_stmt FROM @stock_entitlement_subscribed_share_column_sql;
EXECUTE stock_entitlement_subscribed_share_column_stmt;
DEALLOCATE PREPARE stock_entitlement_subscribed_share_column_stmt;

SET @stock_entitlement_subscribed_cash_column_exists := (
  SELECT COUNT(*)
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_corporate_action_entitlement'
     AND column_name = 'subscribed_cash_amount'
);

SET @stock_entitlement_subscribed_cash_column_sql := IF(
  @stock_entitlement_subscribed_cash_column_exists = 0,
  'ALTER TABLE stock_corporate_action_entitlement ADD COLUMN subscribed_cash_amount DECIMAL(19,2) NULL AFTER subscribed_share_quantity',
  'SELECT 1'
);

PREPARE stock_entitlement_subscribed_cash_column_stmt FROM @stock_entitlement_subscribed_cash_column_sql;
EXECUTE stock_entitlement_subscribed_cash_column_stmt;
DEALLOCATE PREPARE stock_entitlement_subscribed_cash_column_stmt;

SET @stock_entitlement_subscribed_at_column_exists := (
  SELECT COUNT(*)
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_corporate_action_entitlement'
     AND column_name = 'subscribed_at'
);

SET @stock_entitlement_subscribed_at_column_sql := IF(
  @stock_entitlement_subscribed_at_column_exists = 0,
  'ALTER TABLE stock_corporate_action_entitlement ADD COLUMN subscribed_at DATETIME NULL AFTER created_at',
  'SELECT 1'
);

PREPARE stock_entitlement_subscribed_at_column_stmt FROM @stock_entitlement_subscribed_at_column_sql;
EXECUTE stock_entitlement_subscribed_at_column_stmt;
DEALLOCATE PREPARE stock_entitlement_subscribed_at_column_stmt;

SET @stock_entitlement_status_check_exists := (
  SELECT COUNT(*)
    FROM information_schema.check_constraints
   WHERE constraint_schema = DATABASE()
     AND constraint_name = 'chk_stock_corporate_action_entitlement_status'
);

SET @stock_entitlement_status_check_drop_sql := IF(
  @stock_entitlement_status_check_exists > 0,
  'ALTER TABLE stock_corporate_action_entitlement DROP CHECK chk_stock_corporate_action_entitlement_status',
  'SELECT 1'
);

PREPARE stock_entitlement_status_check_drop_stmt FROM @stock_entitlement_status_check_drop_sql;
EXECUTE stock_entitlement_status_check_drop_stmt;
DEALLOCATE PREPARE stock_entitlement_status_check_drop_stmt;

ALTER TABLE stock_corporate_action_entitlement
  ADD CONSTRAINT chk_stock_corporate_action_entitlement_status CHECK (
    CASE `status`
      WHEN 'ANNOUNCED' THEN 1
      WHEN 'SUBSCRIBED' THEN 1
      WHEN 'EXPIRED' THEN 1
      WHEN 'PAID' THEN 1
      ELSE 0
    END = 1
  );

SET @stock_entitlement_subscribed_share_check_exists := (
  SELECT COUNT(*)
    FROM information_schema.check_constraints
   WHERE constraint_schema = DATABASE()
     AND constraint_name = 'chk_stock_corporate_action_entitlement_subscribed_share'
);

SET @stock_entitlement_subscribed_share_check_sql := IF(
  @stock_entitlement_subscribed_share_check_exists = 0,
  'ALTER TABLE stock_corporate_action_entitlement ADD CONSTRAINT chk_stock_corporate_action_entitlement_subscribed_share CHECK (subscribed_share_quantity IS NULL OR subscribed_share_quantity > 0)',
  'SELECT 1'
);

PREPARE stock_entitlement_subscribed_share_check_stmt FROM @stock_entitlement_subscribed_share_check_sql;
EXECUTE stock_entitlement_subscribed_share_check_stmt;
DEALLOCATE PREPARE stock_entitlement_subscribed_share_check_stmt;

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

SET @stock_entitlement_subscribed_share_limit_check_sql := IF(
  @stock_entitlement_subscribed_share_limit_check_exists = 0,
  'ALTER TABLE stock_corporate_action_entitlement ADD CONSTRAINT chk_stock_corporate_action_entitlement_subscribed_share_limit CHECK (subscribed_share_quantity IS NULL OR share_quantity IS NULL OR subscribed_share_quantity <= share_quantity)',
  'SELECT 1'
);

PREPARE stock_entitlement_subscribed_share_limit_check_stmt FROM @stock_entitlement_subscribed_share_limit_check_sql;
EXECUTE stock_entitlement_subscribed_share_limit_check_stmt;
DEALLOCATE PREPARE stock_entitlement_subscribed_share_limit_check_stmt;

SET @stock_entitlement_subscribed_cash_check_exists := (
  SELECT COUNT(*)
    FROM information_schema.check_constraints
   WHERE constraint_schema = DATABASE()
     AND constraint_name = 'chk_stock_corporate_action_entitlement_subscribed_cash'
);

SET @stock_entitlement_subscribed_cash_check_sql := IF(
  @stock_entitlement_subscribed_cash_check_exists = 0,
  'ALTER TABLE stock_corporate_action_entitlement ADD CONSTRAINT chk_stock_corporate_action_entitlement_subscribed_cash CHECK (subscribed_cash_amount IS NULL OR subscribed_cash_amount > 0)',
  'SELECT 1'
);

PREPARE stock_entitlement_subscribed_cash_check_stmt FROM @stock_entitlement_subscribed_cash_check_sql;
EXECUTE stock_entitlement_subscribed_cash_check_stmt;
DEALLOCATE PREPARE stock_entitlement_subscribed_cash_check_stmt;

SET @stock_entitlement_subscription_complete_check_exists := (
  SELECT COUNT(*)
    FROM information_schema.check_constraints
   WHERE constraint_schema = DATABASE()
     AND constraint_name = 'chk_stock_corporate_action_entitlement_subscription_complete'
);

SET @stock_entitlement_subscription_complete_check_sql := IF(
  @stock_entitlement_subscription_complete_check_exists = 0,
  'ALTER TABLE stock_corporate_action_entitlement ADD CONSTRAINT chk_stock_corporate_action_entitlement_subscription_complete CHECK (status <> ''SUBSCRIBED'' OR (subscribed_share_quantity IS NOT NULL AND subscribed_cash_amount IS NOT NULL AND subscribed_at IS NOT NULL))',
  'SELECT 1'
);

PREPARE stock_entitlement_subscription_complete_check_stmt FROM @stock_entitlement_subscription_complete_check_sql;
EXECUTE stock_entitlement_subscription_complete_check_stmt;
DEALLOCATE PREPARE stock_entitlement_subscription_complete_check_stmt;

SET @stock_cash_flow_reason_check_exists := (
  SELECT COUNT(*)
    FROM information_schema.check_constraints
   WHERE constraint_schema = DATABASE()
     AND constraint_name = 'chk_stock_account_cash_flow_reason'
);

SET @stock_cash_flow_reason_check_drop_sql := IF(
  @stock_cash_flow_reason_check_exists > 0,
  'ALTER TABLE stock_account_cash_flow DROP CHECK chk_stock_account_cash_flow_reason',
  'SELECT 1'
);

PREPARE stock_cash_flow_reason_check_drop_stmt FROM @stock_cash_flow_reason_check_drop_sql;
EXECUTE stock_cash_flow_reason_check_drop_stmt;
DEALLOCATE PREPARE stock_cash_flow_reason_check_drop_stmt;

ALTER TABLE stock_account_cash_flow
  ADD CONSTRAINT chk_stock_account_cash_flow_reason CHECK (
    CASE `reason`
      WHEN 'OPENING_GRANT' THEN 1
      WHEN 'ADMIN_DEPOSIT' THEN 1
      WHEN 'ADMIN_WITHDRAW' THEN 1
      WHEN 'DIVIDEND_PAYMENT' THEN 1
      WHEN 'CAPITAL_INCREASE_SUBSCRIPTION' THEN 1
      WHEN 'AUTO_PROFILE_RECURRING_DEPOSIT' THEN 1
      WHEN 'AUTO_PARTICIPANT_RECURRING_DEPOSIT' THEN 1
      ELSE 0
    END = 1
  );
