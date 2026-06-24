-- ZERO_VALUE delisting support.
-- Apply once to an existing MySQL stock schema before deploying the delisting feature.
-- This file contains only the schema changes required by DELISTING corporate actions.

ALTER TABLE stock_corporate_action
  ADD COLUMN delisting_date DATE NULL AFTER listing_date,
  ADD COLUMN delisting_treatment VARCHAR(30) NULL AFTER delisting_date;

ALTER TABLE stock_corporate_action
  DROP INDEX idx_stock_corporate_action_status_dates,
  ADD INDEX idx_stock_corporate_action_status_dates (
    status,
    ex_rights_date,
    payment_date,
    listing_date,
    delisting_date
  );

ALTER TABLE stock_corporate_action
  DROP CHECK chk_stock_corporate_action_type_valid,
  DROP CHECK chk_stock_corporate_action_status_valid,
  DROP CHECK chk_stock_corporate_action_field_scope;

ALTER TABLE stock_corporate_action
  ADD CONSTRAINT chk_stock_corporate_action_type_valid CHECK (
    CASE `action_type`
      WHEN 'INITIAL_ISSUE' THEN 1
      WHEN 'PAID_IN_CAPITAL_INCREASE' THEN 1
      WHEN 'ADDITIONAL_ISSUE' THEN 1
      WHEN 'STOCK_SPLIT' THEN 1
      WHEN 'CASH_DIVIDEND' THEN 1
      WHEN 'BONUS_ISSUE' THEN 1
      WHEN 'STOCK_DIVIDEND' THEN 1
      WHEN 'DELISTING' THEN 1
      ELSE 0
    END = 1
  ),
  ADD CONSTRAINT chk_stock_corporate_action_status_valid CHECK (
    CASE `status`
      WHEN 'ANNOUNCED' THEN 1
      WHEN 'EX_RIGHTS_APPLIED' THEN 1
      WHEN 'PAID' THEN 1
      WHEN 'LISTED' THEN 1
      WHEN 'DELISTED' THEN 1
      ELSE 0
    END = 1
  ),
  ADD CONSTRAINT chk_stock_corporate_action_delisting_treatment CHECK (
    delisting_treatment IS NULL OR delisting_treatment = 'ZERO_VALUE'
  ),
  ADD CONSTRAINT chk_stock_corporate_action_delisting_required CHECK (
    action_type <> 'DELISTING'
    OR (
      delisting_date IS NOT NULL
      AND delisting_treatment = 'ZERO_VALUE'
    )
  ),
  ADD CONSTRAINT chk_stock_corporate_action_field_scope CHECK (
    (action_type IN ('INITIAL_ISSUE', 'PAID_IN_CAPITAL_INCREASE', 'ADDITIONAL_ISSUE', 'BONUS_ISSUE', 'STOCK_DIVIDEND') OR share_quantity IS NULL)
    AND (action_type IN ('INITIAL_ISSUE', 'PAID_IN_CAPITAL_INCREASE', 'ADDITIONAL_ISSUE') OR issue_price IS NULL)
    AND (action_type IN ('PAID_IN_CAPITAL_INCREASE', 'STOCK_SPLIT', 'CASH_DIVIDEND', 'BONUS_ISSUE', 'STOCK_DIVIDEND') OR base_price IS NULL)
    AND (action_type IN ('PAID_IN_CAPITAL_INCREASE', 'STOCK_SPLIT', 'CASH_DIVIDEND', 'BONUS_ISSUE', 'STOCK_DIVIDEND') OR theoretical_ex_rights_price IS NULL)
    AND (action_type = 'CASH_DIVIDEND' OR dividend_amount IS NULL)
    AND (action_type IN ('PAID_IN_CAPITAL_INCREASE', 'CASH_DIVIDEND', 'BONUS_ISSUE', 'STOCK_DIVIDEND') OR ex_rights_date IS NULL)
    AND (action_type IN ('PAID_IN_CAPITAL_INCREASE', 'CASH_DIVIDEND') OR payment_date IS NULL)
    AND (action_type IN ('PAID_IN_CAPITAL_INCREASE', 'ADDITIONAL_ISSUE', 'STOCK_SPLIT', 'BONUS_ISSUE', 'STOCK_DIVIDEND') OR listing_date IS NULL)
    AND (action_type = 'DELISTING' OR delisting_date IS NULL)
    AND (action_type = 'DELISTING' OR delisting_treatment IS NULL)
    AND (action_type = 'STOCK_SPLIT' OR (split_from IS NULL AND split_to IS NULL))
  );

ALTER TABLE stock_price
  DROP CHECK chk_stock_price_current_positive,
  DROP CHECK chk_stock_price_previous_close_positive;

ALTER TABLE stock_price
  ADD CONSTRAINT chk_stock_price_current_non_negative CHECK (current_price >= 0),
  ADD CONSTRAINT chk_stock_price_previous_close_non_negative CHECK (previous_close >= 0);

ALTER TABLE stock_price_tick
  DROP CHECK chk_stock_price_tick_price_positive;

ALTER TABLE stock_price_tick
  ADD CONSTRAINT chk_stock_price_tick_price_non_negative CHECK (price >= 0);
