USE STOCK_SERVICE;

CREATE TABLE IF NOT EXISTS stock_auto_participant_event_profile_config (
  profile_type VARCHAR(40) NOT NULL,
  shareholder_subscription_rate DECIMAL(8,4) NOT NULL DEFAULT 0.4500,
  public_offering_subscription_rate DECIMAL(8,4) NOT NULL DEFAULT 0.2000,
  max_cash_allocation_rate DECIMAL(8,4) NOT NULL DEFAULT 0.2000,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (profile_type),
  CONSTRAINT chk_stock_auto_event_profile_type CHECK (profile_type <> ''),
  CONSTRAINT chk_stock_auto_event_profile_shareholder_rate CHECK (shareholder_subscription_rate >= 0 AND shareholder_subscription_rate <= 1),
  CONSTRAINT chk_stock_auto_event_profile_public_rate CHECK (public_offering_subscription_rate >= 0 AND public_offering_subscription_rate <= 1),
  CONSTRAINT chk_stock_auto_event_profile_cash_rate CHECK (max_cash_allocation_rate >= 0 AND max_cash_allocation_rate <= 1)
);
