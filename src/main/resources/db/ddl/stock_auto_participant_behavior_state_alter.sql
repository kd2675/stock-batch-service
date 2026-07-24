USE STOCK_SERVICE;

CREATE TABLE IF NOT EXISTS stock_auto_participant_position_state (
  account_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  position_opened_business_date DATE NOT NULL,
  holding_trading_days INT NOT NULL,
  average_down_rounds INT NOT NULL DEFAULT 0,
  last_average_down_business_date DATE NULL,
  peak_close_price DECIMAL(19,2) NOT NULL,
  last_seen_business_date DATE NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (account_id, symbol),
  KEY idx_stock_auto_position_symbol_account (symbol, account_id),
  KEY idx_stock_auto_position_last_seen (last_seen_business_date, account_id, symbol),
  CONSTRAINT chk_stock_auto_position_holding_days CHECK (holding_trading_days > 0),
  CONSTRAINT chk_stock_auto_position_average_down_rounds CHECK (average_down_rounds >= 0),
  CONSTRAINT chk_stock_auto_position_peak_price CHECK (peak_close_price >= 0)
);

CREATE TABLE IF NOT EXISTS stock_auto_participant_performance_state (
  account_id BIGINT NOT NULL,
  recent_profitable_trading_days INT NOT NULL DEFAULT 0,
  recent_closed_trading_days INT NOT NULL DEFAULT 0,
  last_seen_business_date DATE NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (account_id),
  KEY idx_stock_auto_performance_last_seen (last_seen_business_date, account_id),
  CONSTRAINT chk_stock_auto_performance_recent_days CHECK (
    recent_profitable_trading_days >= 0
    AND recent_closed_trading_days >= 0
    AND recent_closed_trading_days <= 20
    AND recent_profitable_trading_days <= recent_closed_trading_days
  )
);

CREATE TABLE IF NOT EXISTS stock_auto_participant_funding_budget (
  id BIGINT NOT NULL AUTO_INCREMENT,
  account_id BIGINT NOT NULL,
  budget_type VARCHAR(20) NOT NULL,
  source_key VARCHAR(160) NOT NULL,
  source_symbol VARCHAR(20) NULL,
  corporate_action_id BIGINT NULL,
  corporate_action_entitlement_id BIGINT NULL,
  granted_amount DECIMAL(19,2) NOT NULL,
  available_amount DECIMAL(19,2) NOT NULL,
  reserved_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  spent_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  expires_business_date DATE NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_auto_funding_budget_source (account_id, budget_type, source_key),
  KEY idx_stock_auto_funding_budget_eligible (account_id, budget_type, status, expires_business_date, id),
  KEY idx_stock_auto_funding_budget_symbol (account_id, budget_type, source_symbol, status, id),
  KEY idx_stock_auto_funding_budget_action (corporate_action_id, corporate_action_entitlement_id),
  CONSTRAINT chk_stock_auto_funding_budget_type CHECK (budget_type IN ('PAYDAY', 'DIVIDEND')),
  CONSTRAINT chk_stock_auto_funding_budget_status CHECK (status IN ('ACTIVE', 'EXHAUSTED', 'EXPIRED')),
  CONSTRAINT chk_stock_auto_funding_budget_amounts CHECK (
    granted_amount > 0
    AND available_amount >= 0
    AND reserved_amount >= 0
    AND spent_amount >= 0
    AND granted_amount = available_amount + reserved_amount + spent_amount
  ),
  CONSTRAINT chk_stock_auto_funding_budget_source CHECK (
    (budget_type = 'PAYDAY' AND corporate_action_id IS NULL AND corporate_action_entitlement_id IS NULL)
    OR (budget_type = 'DIVIDEND' AND source_symbol IS NOT NULL AND corporate_action_id IS NOT NULL AND corporate_action_entitlement_id IS NOT NULL)
  )
);

CREATE TABLE IF NOT EXISTS stock_auto_participant_order_budget (
  order_id BIGINT NOT NULL,
  budget_id BIGINT NOT NULL,
  allocated_amount DECIMAL(19,2) NOT NULL,
  remaining_reserved_amount DECIMAL(19,2) NOT NULL,
  spent_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  released_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (order_id, budget_id),
  KEY idx_stock_auto_order_budget_budget (budget_id, order_id),
  CONSTRAINT chk_stock_auto_order_budget_amounts CHECK (
    allocated_amount > 0
    AND remaining_reserved_amount >= 0
    AND spent_amount >= 0
    AND released_amount >= 0
    AND allocated_amount = remaining_reserved_amount + spent_amount + released_amount
  )
);

SET @stock_order_funding_budget_type_sql := IF(
    EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = DATABASE()
           AND table_name = 'stock_order'
           AND column_name = 'funding_budget_type'
    ),
    'SELECT 1',
    'ALTER TABLE stock_order ADD COLUMN funding_budget_type VARCHAR(20) NULL AFTER reserved_cash, ALGORITHM=INSTANT'
);
PREPARE stock_order_funding_budget_type_stmt FROM @stock_order_funding_budget_type_sql;
EXECUTE stock_order_funding_budget_type_stmt;
DEALLOCATE PREPARE stock_order_funding_budget_type_stmt;

-- The hot-ledger CHECK is added together with the V2 order snapshot checks by
-- stock_auto_market_reprice_index_alter.sql. Keeping the CHECK clauses in one
-- ALTER prevents repeated full stock_order rebuilds during an upgrade.

-- Existing holdings predate the behavioral-memory table. Seed only missing rows so the
-- first V2 decision can enforce holding-period and average-down rules without waiting for
-- the next EOD report. The historical opening date cannot be proven from stock_holding, so
-- the active business date is used as a conservative one-day starting point.
INSERT IGNORE INTO stock_auto_participant_position_state(
    account_id, symbol, position_opened_business_date, holding_trading_days,
    average_down_rounds, last_average_down_business_date, peak_close_price,
    last_seen_business_date, updated_at
)
SELECT h.account_id,
       h.symbol,
       COALESCE(bs.active_business_date, CURRENT_DATE()),
       1,
       0,
       NULL,
       h.average_price,
       COALESCE(bs.active_business_date, CURRENT_DATE()),
       CURRENT_TIMESTAMP
  FROM stock_holding h
  JOIN stock_account a
    ON a.id = h.account_id
   AND a.status = 'ACTIVE'
  JOIN stock_auto_participant p
    ON p.user_key = a.user_key
   AND p.withdrawn_at IS NULL
  LEFT JOIN stock_market_business_state bs
    ON bs.state_id = 'DEFAULT'
 WHERE h.quantity > 0;
