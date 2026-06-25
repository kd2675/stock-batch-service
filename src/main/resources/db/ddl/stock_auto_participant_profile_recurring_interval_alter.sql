USE STOCK_SERVICE;

ALTER TABLE stock_auto_participant_profile_config
  ADD COLUMN recurring_deposit_interval_days INT NOT NULL DEFAULT 30 AFTER recurring_deposit_amount;

ALTER TABLE stock_auto_participant_profile_config
  ADD CONSTRAINT chk_stock_auto_profile_recurring_interval CHECK (recurring_deposit_interval_days >= 1);
