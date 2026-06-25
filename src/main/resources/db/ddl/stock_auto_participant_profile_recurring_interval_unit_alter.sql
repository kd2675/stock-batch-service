USE STOCK_SERVICE;

ALTER TABLE stock_auto_participant_profile_config
  ADD COLUMN recurring_deposit_interval_value DECIMAL(12,4) NOT NULL DEFAULT 30.0000 AFTER recurring_deposit_interval_days,
  ADD COLUMN recurring_deposit_interval_unit VARCHAR(20) NOT NULL DEFAULT 'DAY' AFTER recurring_deposit_interval_value;

UPDATE stock_auto_participant_profile_config
SET recurring_deposit_interval_value = recurring_deposit_interval_days,
    recurring_deposit_interval_unit = 'DAY';

ALTER TABLE stock_auto_participant_profile_config
  ADD CONSTRAINT chk_stock_auto_profile_recurring_interval_value CHECK (recurring_deposit_interval_value >= 0 AND recurring_deposit_interval_value <= 1000),
  ADD CONSTRAINT chk_stock_auto_profile_recurring_interval_unit CHECK (
    CASE `recurring_deposit_interval_unit`
      WHEN 'SECOND' THEN 1
      WHEN 'MINUTE' THEN 1
      WHEN 'HOUR' THEN 1
      WHEN 'DAY' THEN 1
      WHEN 'MONTH' THEN 1
      WHEN 'YEAR' THEN 1
      ELSE 0
    END = 1
  ),
  ADD CONSTRAINT chk_stock_auto_profile_recurring_interval_complete CHECK (
    recurring_deposit_amount = 0
    OR (recurring_deposit_interval_value > 0 AND recurring_deposit_interval_unit IS NOT NULL)
  );
