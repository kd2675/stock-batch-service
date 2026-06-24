-- Auto participant profile and listing auto account support.
-- Apply once to an existing MySQL stock schema before deploying the listing-auto-account feature.

ALTER TABLE stock_auto_participant
  ADD COLUMN profile_type VARCHAR(40) NOT NULL DEFAULT 'NOISE_TRADER' AFTER enabled;

ALTER TABLE stock_auto_participant
  ADD CONSTRAINT chk_stock_auto_participant_profile_type CHECK (
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
      WHEN 'PANIC_SELLER' THEN 1
      WHEN 'DIP_BUYER' THEN 1
      WHEN 'LIQUIDITY_AVOIDANT' THEN 1
      WHEN 'WHALE' THEN 1
      WHEN 'SMALL_DIVERSIFIER' THEN 1
      WHEN 'OBSERVER' THEN 1
      ELSE 0
    END = 1
  );

CREATE TABLE IF NOT EXISTS stock_listing_auto_account_config (
  symbol VARCHAR(20) NOT NULL,
  user_key VARCHAR(64) NOT NULL,
  display_name VARCHAR(80) NOT NULL,
  enabled BIT NOT NULL,
  position_side VARCHAR(20) NOT NULL,
  max_order_quantity INT NOT NULL,
  order_ttl_seconds INT NOT NULL,
  price_offset_ticks INT NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (symbol),
  UNIQUE KEY uk_stock_listing_auto_account_user_key (user_key),
  KEY idx_stock_listing_auto_account_enabled (enabled, symbol),
  CONSTRAINT chk_stock_listing_auto_account_position CHECK (CASE `position_side` WHEN 'SELL_ONLY' THEN 1 WHEN 'BUY_ONLY' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_listing_auto_account_max_order_quantity CHECK (max_order_quantity > 0),
  CONSTRAINT chk_stock_listing_auto_account_order_ttl_seconds CHECK (order_ttl_seconds > 0),
  CONSTRAINT chk_stock_listing_auto_account_price_offset CHECK (price_offset_ticks >= 0)
);
