USE STOCK_SERVICE;

ALTER TABLE stock_auto_participant_profile_config
  ADD COLUMN news_weight DECIMAL(8,4) DEFAULT NULL AFTER profile_type,
  ADD COLUMN momentum_weight DECIMAL(8,4) DEFAULT NULL AFTER news_weight,
  ADD COLUMN contrarian_weight DECIMAL(8,4) DEFAULT NULL AFTER momentum_weight,
  ADD COLUMN loss_aversion_weight DECIMAL(8,4) DEFAULT NULL AFTER contrarian_weight,
  ADD COLUMN herding_weight DECIMAL(8,4) DEFAULT NULL AFTER loss_aversion_weight,
  ADD COLUMN market_making_weight DECIMAL(8,4) DEFAULT NULL AFTER herding_weight,
  ADD COLUMN overconfidence_weight DECIMAL(8,4) DEFAULT NULL AFTER market_making_weight,
  ADD COLUMN noise_weight DECIMAL(8,4) DEFAULT NULL AFTER overconfidence_weight,
  ADD COLUMN panic_sell_weight DECIMAL(8,4) DEFAULT NULL AFTER noise_weight,
  ADD COLUMN dip_buy_weight DECIMAL(8,4) DEFAULT NULL AFTER panic_sell_weight,
  ADD CONSTRAINT chk_stock_auto_profile_news_weight CHECK (news_weight IS NULL OR (news_weight >= 0 AND news_weight <= 1)),
  ADD CONSTRAINT chk_stock_auto_profile_momentum_weight CHECK (momentum_weight IS NULL OR (momentum_weight >= 0 AND momentum_weight <= 1)),
  ADD CONSTRAINT chk_stock_auto_profile_contrarian_weight CHECK (contrarian_weight IS NULL OR (contrarian_weight >= 0 AND contrarian_weight <= 1)),
  ADD CONSTRAINT chk_stock_auto_profile_loss_aversion_weight CHECK (loss_aversion_weight IS NULL OR (loss_aversion_weight >= 0 AND loss_aversion_weight <= 1)),
  ADD CONSTRAINT chk_stock_auto_profile_herding_weight CHECK (herding_weight IS NULL OR (herding_weight >= 0 AND herding_weight <= 1)),
  ADD CONSTRAINT chk_stock_auto_profile_market_making_weight CHECK (market_making_weight IS NULL OR (market_making_weight >= 0 AND market_making_weight <= 1)),
  ADD CONSTRAINT chk_stock_auto_profile_overconfidence_weight CHECK (overconfidence_weight IS NULL OR (overconfidence_weight >= 0 AND overconfidence_weight <= 1)),
  ADD CONSTRAINT chk_stock_auto_profile_noise_weight CHECK (noise_weight IS NULL OR (noise_weight >= 0 AND noise_weight <= 1)),
  ADD CONSTRAINT chk_stock_auto_profile_panic_sell_weight CHECK (panic_sell_weight IS NULL OR (panic_sell_weight >= 0 AND panic_sell_weight <= 1)),
  ADD CONSTRAINT chk_stock_auto_profile_dip_buy_weight CHECK (dip_buy_weight IS NULL OR (dip_buy_weight >= 0 AND dip_buy_weight <= 1));
