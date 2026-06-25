USE STOCK_SERVICE;

ALTER TABLE stock_auto_participant_profile_config
  ADD COLUMN profit_taking_weight DECIMAL(8,4) NOT NULL DEFAULT 0.0000 AFTER deep_loss_hold_weight;

ALTER TABLE stock_auto_participant_profile_config
  ADD CONSTRAINT chk_stock_auto_profile_profit_taking CHECK (profit_taking_weight >= 0 AND profit_taking_weight <= 1);
