USE STOCK_SERVICE;

ALTER TABLE stock_auto_participant_profile_config
  ADD COLUMN order_ttl_multiplier DECIMAL(8,4) NOT NULL DEFAULT 1.0000 AFTER aggression_multiplier;

ALTER TABLE stock_auto_participant_profile_config
  ADD CONSTRAINT chk_stock_auto_profile_order_ttl_multiplier CHECK (order_ttl_multiplier >= 0.1 AND order_ttl_multiplier <= 10);
