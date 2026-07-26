-- Adds a non-refundable daily submission budget for finite, passive issue-underwriter supply.
-- Existing contracts remain ALLOCATED and do not create orders until explicitly activated.

USE STOCK_SERVICE;

CREATE TABLE IF NOT EXISTS stock_underwriting_daily_supply_state (
  simulation_trade_date DATE NOT NULL,
  underwriting_contract_id BIGINT NOT NULL,
  reference_daily_volume BIGINT NOT NULL,
  submission_quantity_limit BIGINT NOT NULL,
  submission_amount_limit DECIMAL(19,2) NOT NULL,
  submitted_quantity BIGINT NOT NULL DEFAULT 0,
  submitted_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  generated_order_count BIGINT NOT NULL DEFAULT 0,
  cancelled_order_count BIGINT NOT NULL DEFAULT 0,
  last_order_price DECIMAL(19,2) NULL,
  state_status VARCHAR(20) NOT NULL DEFAULT 'GATED',
  gate_reason VARCHAR(80) NOT NULL DEFAULT 'NOT_RUN',
  policy_version BIGINT NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (simulation_trade_date, underwriting_contract_id),
  KEY idx_stock_underwriting_supply_contract (
    underwriting_contract_id, simulation_trade_date
  ),
  KEY idx_stock_underwriting_supply_status (
    simulation_trade_date, state_status, underwriting_contract_id
  ),
  CONSTRAINT chk_stock_underwriting_supply_limits CHECK (
    reference_daily_volume >= 0
    AND submission_quantity_limit >= 0
    AND submission_amount_limit >= 0
  ),
  CONSTRAINT chk_stock_underwriting_supply_usage CHECK (
    submitted_quantity >= 0
    AND submitted_amount >= 0
    AND submitted_quantity <= submission_quantity_limit
    AND submitted_amount <= submission_amount_limit
    AND generated_order_count >= 0
    AND cancelled_order_count >= 0
  ),
  CONSTRAINT chk_stock_underwriting_supply_price CHECK (
    last_order_price IS NULL OR last_order_price > 0
  ),
  CONSTRAINT chk_stock_underwriting_supply_status CHECK (
    CASE state_status
      WHEN 'ACTIVE' THEN 1
      WHEN 'GATED' THEN 1
      WHEN 'COMPLETED' THEN 1
      WHEN 'SUSPENDED' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_underwriting_supply_version CHECK (
    policy_version > 0 AND version >= 0
  )
);
