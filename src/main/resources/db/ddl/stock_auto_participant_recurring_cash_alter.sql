USE STOCK_SERVICE;

ALTER TABLE stock_auto_participant
  ADD COLUMN recurring_cash_amount DECIMAL(19,2) NULL AFTER profile_type,
  ADD COLUMN recurring_cash_interval_value DECIMAL(12,4) NULL AFTER recurring_cash_amount,
  ADD COLUMN recurring_cash_interval_unit VARCHAR(20) NULL AFTER recurring_cash_interval_value;

ALTER TABLE stock_auto_participant
  ADD CONSTRAINT chk_stock_auto_participant_recurring_cash_amount CHECK (recurring_cash_amount IS NULL OR recurring_cash_amount >= 0),
  ADD CONSTRAINT chk_stock_auto_participant_recurring_cash_interval CHECK (recurring_cash_interval_value IS NULL OR (recurring_cash_interval_value >= 0 AND recurring_cash_interval_value <= 1000)),
  ADD CONSTRAINT chk_stock_auto_participant_recurring_cash_unit CHECK (
    recurring_cash_interval_unit IS NULL OR
    CASE `recurring_cash_interval_unit`
      WHEN 'SECOND' THEN 1
      WHEN 'MINUTE' THEN 1
      WHEN 'HOUR' THEN 1
      WHEN 'DAY' THEN 1
      WHEN 'MONTH' THEN 1
      WHEN 'YEAR' THEN 1
      ELSE 0
    END = 1
  ),
  ADD CONSTRAINT chk_stock_auto_participant_recurring_cash_complete CHECK (
    recurring_cash_amount IS NULL
    OR recurring_cash_amount = 0
    OR (recurring_cash_interval_value IS NOT NULL AND recurring_cash_interval_value > 0 AND recurring_cash_interval_unit IS NOT NULL)
  );

ALTER TABLE stock_account_cash_flow
  DROP CHECK chk_stock_account_cash_flow_reason;

ALTER TABLE stock_account_cash_flow
  ADD CONSTRAINT chk_stock_account_cash_flow_reason CHECK (
    CASE `reason`
      WHEN 'OPENING_GRANT' THEN 1
      WHEN 'ADMIN_DEPOSIT' THEN 1
      WHEN 'ADMIN_WITHDRAW' THEN 1
      WHEN 'AUTO_PROFILE_RECURRING_DEPOSIT' THEN 1
      WHEN 'AUTO_PARTICIPANT_RECURRING_DEPOSIT' THEN 1
      ELSE 0
    END = 1
  );
