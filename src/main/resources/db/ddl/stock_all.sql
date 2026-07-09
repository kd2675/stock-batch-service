CREATE DATABASE IF NOT EXISTS STOCK_SERVICE
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE STOCK_SERVICE;

CREATE TABLE IF NOT EXISTS stock_batch_job_control (
  job_name VARCHAR(100) NOT NULL,
  runtime_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  scheduler_configured BOOLEAN NOT NULL DEFAULT TRUE,
  updated_by VARCHAR(64) NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (job_name),
  CONSTRAINT chk_stock_batch_job_control_name CHECK (job_name <> '')
);

CREATE TABLE IF NOT EXISTS stock_batch_job_lock (
  job_name VARCHAR(100) NOT NULL,
  lock_owner VARCHAR(128) NOT NULL,
  locked_until DATETIME NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (job_name),
  KEY idx_stock_batch_job_lock_until (locked_until),
  CONSTRAINT chk_stock_batch_job_lock_name CHECK (job_name <> ''),
  CONSTRAINT chk_stock_batch_job_lock_owner CHECK (lock_owner <> '')
);

CREATE TABLE IF NOT EXISTS stock_batch_job_signal (
  id BIGINT NOT NULL AUTO_INCREMENT,
  signal_type VARCHAR(60) NOT NULL,
  job_name VARCHAR(100) NOT NULL,
  execution_mode VARCHAR(120) NOT NULL,
  symbol VARCHAR(20) NULL,
  payload_json TEXT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  requested_by VARCHAR(64) NULL,
  requested_at DATETIME NOT NULL,
  picked_at DATETIME NULL,
  completed_at DATETIME NULL,
  processed_count INT NULL,
  message VARCHAR(500) NULL,
  error_message VARCHAR(1000) NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_stock_batch_job_signal_status_time (status, requested_at, id),
  KEY idx_stock_batch_job_signal_job_status (job_name, status, requested_at),
  CONSTRAINT chk_stock_batch_job_signal_type CHECK (signal_type <> ''),
  CONSTRAINT chk_stock_batch_job_signal_job CHECK (job_name <> ''),
  CONSTRAINT chk_stock_batch_job_signal_mode CHECK (execution_mode <> ''),
  CONSTRAINT chk_stock_batch_job_signal_status CHECK (
    CASE `status`
      WHEN 'PENDING' THEN 1
      WHEN 'PROCESSING' THEN 1
      WHEN 'COMPLETED' THEN 1
      WHEN 'FAILED' THEN 1
      ELSE 0
    END = 1
  )
);

CREATE TABLE IF NOT EXISTS stock_simulation_clock (
  clock_id VARCHAR(40) NOT NULL,
  base_simulation_date DATE NOT NULL,
  real_seconds_per_simulation_day INT NOT NULL,
  accumulated_real_seconds BIGINT NOT NULL DEFAULT 0,
  running BOOLEAN NOT NULL DEFAULT FALSE,
  last_started_at DATETIME NULL,
  last_heartbeat_at DATETIME NULL,
  timezone VARCHAR(50) NOT NULL DEFAULT 'Asia/Seoul',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (clock_id),
  CONSTRAINT chk_stock_simulation_clock_id CHECK (clock_id <> ''),
  CONSTRAINT chk_stock_simulation_clock_day_seconds CHECK (real_seconds_per_simulation_day > 0),
  CONSTRAINT chk_stock_simulation_clock_accumulated CHECK (accumulated_real_seconds >= 0),
  CONSTRAINT chk_stock_simulation_clock_running_dates CHECK (
    running = FALSE OR (last_started_at IS NOT NULL AND last_heartbeat_at IS NOT NULL)
  )
);

CREATE TABLE IF NOT EXISTS stock_account (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_key VARCHAR(64) NULL,
  account_code VARCHAR(32) NULL,
  recovery_code_hash VARCHAR(128) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  cash_balance DECIMAL(19,2) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  detached_at DATETIME NULL,
  reconnected_at DATETIME NULL,
  recovery_expires_at DATETIME NULL,
  purge_after DATETIME NULL,
  previous_user_key_hash VARCHAR(128) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_account_user_key (user_key),
  UNIQUE KEY uk_stock_account_account_code (account_code),
  KEY idx_stock_account_status_purge (status, purge_after),
  KEY idx_stock_account_status_id (status, id),
  CONSTRAINT chk_stock_account_cash_non_negative CHECK (cash_balance >= 0),
  CONSTRAINT chk_stock_account_status_valid CHECK (CASE `status` WHEN 'ACTIVE' THEN 1 WHEN 'DETACHED' THEN 1 WHEN 'CLOSED' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_account_detached_user_scope CHECK (status <> 'DETACHED' OR user_key IS NULL),
  CONSTRAINT chk_stock_account_recovery_window CHECK (
    recovery_expires_at IS NULL OR purge_after IS NULL OR purge_after >= recovery_expires_at
  )
);

CREATE TABLE IF NOT EXISTS stock_account_cash_flow (
  id BIGINT NOT NULL AUTO_INCREMENT,
  account_id BIGINT NOT NULL,
  flow_type VARCHAR(20) NOT NULL,
  amount DECIMAL(19,2) NOT NULL,
  reason VARCHAR(40) NOT NULL,
  created_by VARCHAR(64) NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_stock_account_cash_flow_account_time (account_id, created_at, id),
  KEY idx_stock_account_cash_flow_account_reason_creator_time (account_id, reason, created_by, created_at, id),
  KEY idx_stock_account_cash_flow_account_type_reason_time (account_id, flow_type, reason, created_at, id),
  KEY idx_stock_account_cash_flow_time (created_at, id),
  CONSTRAINT chk_stock_account_cash_flow_type CHECK (CASE `flow_type` WHEN 'DEPOSIT' THEN 1 WHEN 'WITHDRAW' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_account_cash_flow_amount CHECK (amount > 0),
  CONSTRAINT chk_stock_account_cash_flow_reason CHECK (
    CASE `reason`
      WHEN 'OPENING_GRANT' THEN 1
      WHEN 'ADMIN_DEPOSIT' THEN 1
      WHEN 'ADMIN_WITHDRAW' THEN 1
      WHEN 'DIVIDEND_PAYMENT' THEN 1
      WHEN 'AUTO_PROFILE_RECURRING_DEPOSIT' THEN 1
      WHEN 'AUTO_PARTICIPANT_RECURRING_DEPOSIT' THEN 1
      ELSE 0
    END = 1
  )
);

CREATE TABLE IF NOT EXISTS stock_instrument (
  symbol VARCHAR(20) NOT NULL,
  name VARCHAR(120) NOT NULL,
  market VARCHAR(20) NOT NULL,
  enabled BIT NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (symbol)
);

CREATE TABLE IF NOT EXISTS stock_order_book_instrument (
  symbol VARCHAR(20) NOT NULL,
  name VARCHAR(120) NOT NULL,
  market VARCHAR(20) NOT NULL,
  initial_price DECIMAL(19,2) NOT NULL,
  issued_shares BIGINT NOT NULL,
  tradable_shares BIGINT NOT NULL,
  tick_size DECIMAL(19,2) NOT NULL DEFAULT 1.00,
  price_limit_rate DECIMAL(5,2) NOT NULL DEFAULT 30.00,
  enabled BIT NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (symbol),
  KEY idx_stock_order_book_instrument_enabled (enabled, symbol),
  CONSTRAINT chk_stock_order_book_instrument_initial_price CHECK (initial_price > 0),
  CONSTRAINT chk_stock_order_book_instrument_issued_shares CHECK (issued_shares > 0),
  CONSTRAINT chk_stock_order_book_instrument_tradable_shares CHECK (tradable_shares >= 0 AND tradable_shares <= issued_shares),
  CONSTRAINT chk_stock_order_book_instrument_tick_size CHECK (tick_size > 0),
  CONSTRAINT chk_stock_order_book_instrument_price_limit_rate CHECK (price_limit_rate > 0 AND price_limit_rate <= 100)
);

CREATE TABLE IF NOT EXISTS stock_corporate_action (
  id BIGINT NOT NULL AUTO_INCREMENT,
  symbol VARCHAR(20) NOT NULL,
  action_type VARCHAR(40) NOT NULL,
  share_quantity BIGINT NULL,
  issue_price DECIMAL(19,2) NULL,
  dividend_amount DECIMAL(19,2) NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'ANNOUNCED',
  base_price DECIMAL(19,2) NULL,
  theoretical_ex_rights_price DECIMAL(19,2) NULL,
  ex_rights_date DATE NULL,
  payment_date DATE NULL,
  listing_date DATE NULL,
  delisting_date DATE NULL,
  delisting_treatment VARCHAR(30) NULL,
  applied_at DATETIME NULL,
  paid_at DATETIME NULL,
  listed_at DATETIME NULL,
  split_from INT NULL,
  split_to INT NULL,
  description VARCHAR(255) NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_stock_corporate_action_symbol_created (symbol, created_at),
  KEY idx_stock_corporate_action_status_dates (status, ex_rights_date, payment_date, listing_date, delisting_date),
  KEY idx_stock_corporate_action_status_symbol (status, symbol),
  CONSTRAINT chk_stock_corporate_action_type_valid CHECK (CASE `action_type` WHEN 'INITIAL_ISSUE' THEN 1 WHEN 'PAID_IN_CAPITAL_INCREASE' THEN 1 WHEN 'STOCK_SPLIT' THEN 1 WHEN 'CASH_DIVIDEND' THEN 1 WHEN 'BONUS_ISSUE' THEN 1 WHEN 'STOCK_DIVIDEND' THEN 1 WHEN 'DELISTING' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_corporate_action_status_valid CHECK (CASE `status` WHEN 'ANNOUNCED' THEN 1 WHEN 'EX_RIGHTS_APPLIED' THEN 1 WHEN 'PAID' THEN 1 WHEN 'LISTED' THEN 1 WHEN 'DELISTED' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_corporate_action_delisting_treatment CHECK (delisting_treatment IS NULL OR delisting_treatment = 'ZERO_VALUE'),
  CONSTRAINT chk_stock_corporate_action_share_quantity CHECK (share_quantity IS NULL OR share_quantity > 0),
  CONSTRAINT chk_stock_corporate_action_issue_price CHECK (issue_price IS NULL OR issue_price > 0),
  CONSTRAINT chk_stock_corporate_action_dividend_amount CHECK (dividend_amount IS NULL OR dividend_amount > 0),
  CONSTRAINT chk_stock_corporate_action_base_price CHECK (base_price IS NULL OR base_price > 0),
  CONSTRAINT chk_stock_corporate_action_ex_rights_price CHECK (theoretical_ex_rights_price IS NULL OR theoretical_ex_rights_price > 0),
  CONSTRAINT chk_stock_corporate_action_paid_dates CHECK (ex_rights_date IS NULL OR payment_date IS NULL OR payment_date >= ex_rights_date),
  CONSTRAINT chk_stock_corporate_action_listing_dates CHECK (payment_date IS NULL OR listing_date IS NULL OR listing_date >= payment_date),
  CONSTRAINT chk_stock_corporate_action_split_from CHECK (split_from IS NULL OR split_from > 0),
  CONSTRAINT chk_stock_corporate_action_split_to CHECK (split_to IS NULL OR split_to > 0),
  CONSTRAINT chk_stock_corporate_action_issue_required CHECK (
    action_type NOT IN ('INITIAL_ISSUE', 'PAID_IN_CAPITAL_INCREASE')
    OR (share_quantity IS NOT NULL AND issue_price IS NOT NULL)
  ),
  CONSTRAINT chk_stock_corporate_action_paid_schedule_required CHECK (
    action_type <> 'PAID_IN_CAPITAL_INCREASE'
    OR (
      base_price IS NOT NULL
      AND theoretical_ex_rights_price IS NOT NULL
      AND ex_rights_date IS NOT NULL
      AND payment_date IS NOT NULL
      AND listing_date IS NOT NULL
    )
  ),
  CONSTRAINT chk_stock_corporate_action_split_required CHECK (
    action_type <> 'STOCK_SPLIT'
    OR (
      split_from IS NOT NULL
      AND split_to IS NOT NULL
      AND split_to > split_from
      AND MOD(split_to, split_from) = 0
    )
  ),
  CONSTRAINT chk_stock_corporate_action_dividend_required CHECK (
    action_type <> 'CASH_DIVIDEND'
    OR (
      dividend_amount IS NOT NULL
      AND base_price IS NOT NULL
      AND theoretical_ex_rights_price IS NOT NULL
      AND ex_rights_date IS NOT NULL
      AND payment_date IS NOT NULL
    )
  ),
  CONSTRAINT chk_stock_corporate_action_free_share_required CHECK (
    action_type NOT IN ('BONUS_ISSUE', 'STOCK_DIVIDEND')
    OR (
      share_quantity IS NOT NULL
      AND base_price IS NOT NULL
      AND theoretical_ex_rights_price IS NOT NULL
      AND ex_rights_date IS NOT NULL
      AND listing_date IS NOT NULL
    )
  ),
  CONSTRAINT chk_stock_corporate_action_delisting_required CHECK (
    action_type <> 'DELISTING'
    OR (
      delisting_date IS NOT NULL
      AND delisting_treatment = 'ZERO_VALUE'
    )
  ),
  CONSTRAINT chk_stock_corporate_action_field_scope CHECK (
    (action_type IN ('INITIAL_ISSUE', 'PAID_IN_CAPITAL_INCREASE', 'BONUS_ISSUE', 'STOCK_DIVIDEND') OR share_quantity IS NULL)
    AND (action_type IN ('INITIAL_ISSUE', 'PAID_IN_CAPITAL_INCREASE') OR issue_price IS NULL)
    AND (action_type = 'CASH_DIVIDEND' OR dividend_amount IS NULL)
    AND (action_type IN ('PAID_IN_CAPITAL_INCREASE', 'CASH_DIVIDEND', 'BONUS_ISSUE', 'STOCK_DIVIDEND') OR base_price IS NULL)
    AND (action_type IN ('PAID_IN_CAPITAL_INCREASE', 'CASH_DIVIDEND', 'BONUS_ISSUE', 'STOCK_DIVIDEND') OR theoretical_ex_rights_price IS NULL)
    AND (action_type IN ('PAID_IN_CAPITAL_INCREASE', 'CASH_DIVIDEND', 'BONUS_ISSUE', 'STOCK_DIVIDEND') OR ex_rights_date IS NULL)
    AND (action_type IN ('PAID_IN_CAPITAL_INCREASE', 'CASH_DIVIDEND') OR payment_date IS NULL)
    AND (action_type IN ('PAID_IN_CAPITAL_INCREASE', 'STOCK_SPLIT', 'BONUS_ISSUE', 'STOCK_DIVIDEND') OR listing_date IS NULL)
    AND (action_type = 'DELISTING' OR delisting_date IS NULL)
    AND (action_type = 'DELISTING' OR delisting_treatment IS NULL)
    AND (action_type = 'STOCK_SPLIT' OR (split_from IS NULL AND split_to IS NULL))
  ),
  CONSTRAINT chk_stock_corporate_action_initial_listed CHECK (
    action_type <> 'INITIAL_ISSUE'
    OR (
      status = 'LISTED'
      AND listed_at IS NOT NULL
      AND applied_at IS NULL
      AND paid_at IS NULL
    )
  )
);

CREATE TABLE IF NOT EXISTS stock_corporate_action_entitlement (
  id BIGINT NOT NULL AUTO_INCREMENT,
  action_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  quantity BIGINT NOT NULL,
  share_quantity BIGINT NULL,
  cash_amount DECIMAL(19,2) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ANNOUNCED',
  holding_snapshot_run_id BIGINT NULL,
  created_at DATETIME NOT NULL,
  paid_at DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_corporate_action_entitlement_action_account (action_id, account_id),
  KEY idx_stock_corporate_action_entitlement_account_created (account_id, created_at),
  KEY idx_stock_corporate_action_entitlement_status (status, action_id),
  KEY idx_stock_corporate_action_entitlement_snapshot_run (holding_snapshot_run_id),
  CONSTRAINT chk_stock_corporate_action_entitlement_quantity CHECK (quantity > 0),
  CONSTRAINT chk_stock_corporate_action_entitlement_share CHECK (share_quantity IS NULL OR share_quantity > 0),
  CONSTRAINT chk_stock_corporate_action_entitlement_cash CHECK (cash_amount IS NULL OR cash_amount > 0),
  CONSTRAINT chk_stock_corporate_action_entitlement_value CHECK (cash_amount IS NOT NULL OR share_quantity IS NOT NULL),
  CONSTRAINT chk_stock_corporate_action_entitlement_status CHECK (CASE `status` WHEN 'ANNOUNCED' THEN 1 WHEN 'PAID' THEN 1 ELSE 0 END = 1)
);

CREATE TABLE IF NOT EXISTS stock_price (
  symbol VARCHAR(20) NOT NULL,
  current_price DECIMAL(19,2) NOT NULL,
  previous_close DECIMAL(19,2) NOT NULL,
  price_time DATETIME NOT NULL,
  provider VARCHAR(40) NOT NULL,
  PRIMARY KEY (symbol),
  CONSTRAINT chk_stock_price_current_non_negative CHECK (current_price >= 0),
  CONSTRAINT chk_stock_price_previous_close_non_negative CHECK (previous_close >= 0)
);

CREATE TABLE IF NOT EXISTS stock_price_tick (
  id BIGINT NOT NULL AUTO_INCREMENT,
  symbol VARCHAR(20) NOT NULL,
  price DECIMAL(19,2) NOT NULL,
  provider VARCHAR(40) NOT NULL,
  price_time DATETIME NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_stock_price_tick_symbol_time (symbol, price_time),
  CONSTRAINT chk_stock_price_tick_price_non_negative CHECK (price >= 0)
);

CREATE TABLE IF NOT EXISTS stock_order (
  id BIGINT NOT NULL AUTO_INCREMENT,
  client_order_id VARCHAR(64) NOT NULL,
  account_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  market_type VARCHAR(30) NOT NULL DEFAULT 'VIRTUAL_PRICE',
  side VARCHAR(10) NOT NULL,
  order_type VARCHAR(10) NOT NULL,
  status VARCHAR(20) NOT NULL,
  limit_price DECIMAL(19,2) NULL,
  quantity BIGINT NOT NULL,
  filled_quantity BIGINT NOT NULL,
  average_fill_price DECIMAL(19,2) NULL,
  reserved_cash DECIMAL(19,2) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_order_client_order_id (client_order_id),
  KEY idx_stock_order_account_created (account_id, created_at),
  KEY idx_stock_order_account_status_created (account_id, status, created_at),
  KEY idx_stock_order_account_symbol_created (account_id, symbol, created_at),
  KEY idx_stock_order_market_status_symbol (market_type, status, symbol),
  KEY idx_stock_order_market_status_side (market_type, status, side),
  KEY idx_stock_order_market_status_account_time (market_type, status, account_id, created_at),
  KEY idx_stock_order_market_account_time (market_type, account_id, created_at),
  KEY idx_stock_order_market_account_symbol_time (market_type, account_id, symbol, created_at),
  KEY idx_stock_order_market_created_status (market_type, created_at, status),
  KEY idx_stock_order_side_status_account (side, status, account_id),
  KEY idx_stock_order_execution_scan (status, order_type, created_at, symbol),
  KEY idx_stock_order_order_book_match (market_type, symbol, side, status, order_type, limit_price, created_at, id),
  KEY idx_stock_order_order_book_expiry (market_type, symbol, created_at, id, status, account_id),
  CONSTRAINT chk_stock_order_market_type_valid CHECK (CASE `market_type` WHEN 'VIRTUAL_PRICE' THEN 1 WHEN 'ORDER_BOOK' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_order_side_valid CHECK (CASE `side` WHEN 'BUY' THEN 1 WHEN 'SELL' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_order_type_valid CHECK (CASE `order_type` WHEN 'LIMIT' THEN 1 WHEN 'MARKET' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_order_status_valid CHECK (CASE `status` WHEN 'PENDING' THEN 1 WHEN 'PARTIALLY_FILLED' THEN 1 WHEN 'FILLED' THEN 1 WHEN 'CANCELLED' THEN 1 WHEN 'REJECTED' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_order_limit_price_positive CHECK (limit_price IS NULL OR limit_price > 0),
  CONSTRAINT chk_stock_order_quantity_positive CHECK (quantity > 0),
  CONSTRAINT chk_stock_order_filled_quantity_valid CHECK (filled_quantity >= 0 AND filled_quantity <= quantity),
  CONSTRAINT chk_stock_order_average_fill_price_positive CHECK (average_fill_price IS NULL OR average_fill_price > 0),
  CONSTRAINT chk_stock_order_reserved_cash_non_negative CHECK (reserved_cash >= 0),
  CONSTRAINT chk_stock_order_terminal_reserved_cash_zero CHECK ((status <> 'FILLED' AND status <> 'CANCELLED' AND status <> 'REJECTED') OR reserved_cash = 0)
);

CREATE TABLE IF NOT EXISTS stock_execution (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  side VARCHAR(10) NOT NULL,
  quantity BIGINT NOT NULL,
  price DECIMAL(19,2) NOT NULL,
  gross_amount DECIMAL(19,2) NOT NULL,
  fee_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  tax_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  net_amount DECIMAL(19,2) NOT NULL,
  realized_profit DECIMAL(19,2) NULL,
  source VARCHAR(30) NOT NULL,
  executed_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_stock_execution_account_time (account_id, executed_at),
  KEY idx_stock_execution_account_symbol_time (account_id, symbol, executed_at),
  KEY idx_stock_execution_time_account (executed_at, account_id),
  KEY idx_stock_execution_source_account_time (source, account_id, executed_at),
  KEY idx_stock_execution_source_account_symbol_time (source, account_id, symbol, executed_at),
  KEY idx_stock_execution_source_time_account (source, executed_at, account_id),
  KEY idx_stock_execution_source_symbol_time (source, symbol, executed_at),
  KEY idx_stock_execution_source_time (source, executed_at),
  KEY idx_stock_execution_order (order_id),
  CONSTRAINT chk_stock_execution_side_valid CHECK (CASE `side` WHEN 'BUY' THEN 1 WHEN 'SELL' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_execution_source_valid CHECK (CASE `source` WHEN 'VIRTUAL_MARKET_PRICE' THEN 1 WHEN 'INTERNAL_ORDER_BOOK' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_execution_quantity_positive CHECK (quantity > 0),
  CONSTRAINT chk_stock_execution_price_positive CHECK (price > 0),
  CONSTRAINT chk_stock_execution_gross_non_negative CHECK (gross_amount >= 0),
  CONSTRAINT chk_stock_execution_fee_non_negative CHECK (fee_amount >= 0),
  CONSTRAINT chk_stock_execution_tax_non_negative CHECK (tax_amount >= 0),
  CONSTRAINT chk_stock_execution_net_non_negative CHECK (net_amount >= 0)
);

CREATE TABLE IF NOT EXISTS stock_holding (
  id BIGINT NOT NULL AUTO_INCREMENT,
  account_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  quantity BIGINT NOT NULL,
  reserved_quantity BIGINT NOT NULL DEFAULT 0,
  average_price DECIMAL(19,2) NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_holding_account_symbol (account_id, symbol),
  KEY idx_stock_holding_symbol_account (symbol, account_id),
  KEY idx_stock_holding_empty_cleanup (quantity, reserved_quantity, updated_at),
  CONSTRAINT chk_stock_holding_quantity_non_negative CHECK (quantity >= 0),
  CONSTRAINT chk_stock_holding_reserved_quantity_valid CHECK (reserved_quantity >= 0 AND reserved_quantity <= quantity),
  CONSTRAINT chk_stock_holding_average_price_positive CHECK (average_price > 0)
);

CREATE TABLE IF NOT EXISTS stock_market_close_run (
  id BIGINT NOT NULL AUTO_INCREMENT,
  symbol VARCHAR(20) NULL,
  business_date DATE NOT NULL,
  closed_at DATETIME NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
  cancelled_order_count INT NOT NULL DEFAULT 0,
  holding_snapshot_count INT NOT NULL DEFAULT 0,
  price_rollover_count INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  completed_at DATETIME NULL,
  PRIMARY KEY (id),
  KEY idx_stock_market_close_run_symbol_time (symbol, closed_at, id),
  KEY idx_stock_market_close_run_date_time (business_date, closed_at, id),
  CONSTRAINT chk_stock_market_close_run_status CHECK (CASE `status` WHEN 'STARTED' THEN 1 WHEN 'COMPLETED' THEN 1 WHEN 'FAILED' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_market_close_run_cancelled_non_negative CHECK (cancelled_order_count >= 0),
  CONSTRAINT chk_stock_market_close_run_snapshot_non_negative CHECK (holding_snapshot_count >= 0),
  CONSTRAINT chk_stock_market_close_run_price_non_negative CHECK (price_rollover_count >= 0)
);

CREATE TABLE IF NOT EXISTS stock_holding_snapshot (
  id BIGINT NOT NULL AUTO_INCREMENT,
  close_run_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  quantity BIGINT NOT NULL,
  reserved_quantity BIGINT NOT NULL DEFAULT 0,
  average_price DECIMAL(19,2) NOT NULL,
  snapshot_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_holding_snapshot_run_account_symbol (close_run_id, account_id, symbol),
  KEY idx_stock_holding_snapshot_symbol_run (symbol, close_run_id, account_id),
  KEY idx_stock_holding_snapshot_account_time (account_id, snapshot_at, id),
  CONSTRAINT chk_stock_holding_snapshot_quantity_non_negative CHECK (quantity >= 0),
  CONSTRAINT chk_stock_holding_snapshot_reserved_valid CHECK (reserved_quantity >= 0 AND reserved_quantity <= quantity),
  CONSTRAINT chk_stock_holding_snapshot_average_price_positive CHECK (average_price > 0)
);

CREATE TABLE IF NOT EXISTS stock_order_book_daily_snapshot (
  id BIGINT NOT NULL AUTO_INCREMENT,
  close_run_id BIGINT NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  simulation_trade_date DATE NOT NULL,
  snapshot_at DATETIME NOT NULL,
  name VARCHAR(120) NOT NULL,
  market VARCHAR(20) NOT NULL,
  enabled BIT NOT NULL,
  market_enabled BIT NOT NULL,
  market_status VARCHAR(20) NOT NULL,
  issued_shares BIGINT NOT NULL,
  tradable_shares BIGINT NOT NULL,
  initial_price DECIMAL(19,2) NOT NULL,
  tick_size DECIMAL(19,2) NOT NULL,
  price_limit_rate DECIMAL(5,2) NOT NULL,
  close_price DECIMAL(19,2) NOT NULL,
  previous_close DECIMAL(19,2) NOT NULL,
  change_rate DECIMAL(9,4) NOT NULL DEFAULT 0.0000,
  price_time DATETIME NULL,
  price_provider VARCHAR(40) NULL,
  execution_count BIGINT NOT NULL DEFAULT 0,
  execution_quantity BIGINT NOT NULL DEFAULT 0,
  turnover_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  buy_quantity BIGINT NOT NULL DEFAULT 0,
  sell_quantity BIGINT NOT NULL DEFAULT 0,
  buy_net_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  sell_net_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  open_order_count BIGINT NOT NULL DEFAULT 0,
  open_buy_order_count BIGINT NOT NULL DEFAULT 0,
  open_sell_order_count BIGINT NOT NULL DEFAULT 0,
  reserved_buy_cash DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  holder_count BIGINT NOT NULL DEFAULT 0,
  holding_quantity BIGINT NOT NULL DEFAULT 0,
  pending_corporate_action_count BIGINT NOT NULL DEFAULT 0,
  last_executed_at DATETIME NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_order_book_daily_snapshot_run_symbol (close_run_id, symbol),
  KEY idx_stock_order_book_daily_snapshot_date_symbol (simulation_trade_date, symbol, close_run_id),
  KEY idx_stock_order_book_daily_snapshot_symbol_date (symbol, simulation_trade_date, close_run_id),
  CONSTRAINT chk_stock_order_book_daily_snapshot_issued CHECK (issued_shares > 0),
  CONSTRAINT chk_stock_order_book_daily_snapshot_tradable CHECK (tradable_shares >= 0 AND tradable_shares <= issued_shares),
  CONSTRAINT chk_stock_order_book_daily_snapshot_price CHECK (close_price >= 0 AND previous_close >= 0 AND initial_price > 0),
  CONSTRAINT chk_stock_order_book_daily_snapshot_flow CHECK (execution_count >= 0 AND execution_quantity >= 0 AND turnover_amount >= 0 AND buy_quantity >= 0 AND sell_quantity >= 0 AND buy_net_amount >= 0 AND sell_net_amount >= 0),
  CONSTRAINT chk_stock_order_book_daily_snapshot_open_order CHECK (open_order_count >= 0 AND open_buy_order_count >= 0 AND open_sell_order_count >= 0 AND reserved_buy_cash >= 0),
  CONSTRAINT chk_stock_order_book_daily_snapshot_holding CHECK (holder_count >= 0 AND holding_quantity >= 0 AND pending_corporate_action_count >= 0),
  CONSTRAINT chk_stock_order_book_daily_snapshot_tick CHECK (tick_size > 0),
  CONSTRAINT chk_stock_order_book_daily_snapshot_limit CHECK (price_limit_rate > 0 AND price_limit_rate <= 100)
);

CREATE TABLE IF NOT EXISTS stock_auto_participant (
  user_key VARCHAR(64) NOT NULL,
  display_name VARCHAR(80) NOT NULL,
  enabled BIT NOT NULL,
  profile_type VARCHAR(40) NOT NULL DEFAULT 'NOISE_TRADER',
  recurring_cash_amount DECIMAL(19,2) NULL,
  recurring_cash_interval_value DECIMAL(12,4) NULL,
  recurring_cash_interval_unit VARCHAR(20) NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  withdrawn_at DATETIME NULL,
  PRIMARY KEY (user_key),
  KEY idx_stock_auto_participant_active (withdrawn_at, enabled, user_key),
  KEY idx_stock_auto_participant_profile_active (withdrawn_at, profile_type, enabled, user_key),
  CONSTRAINT chk_stock_auto_participant_profile_type CHECK (
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
      WHEN 'DAY_TRADER' THEN 1
      WHEN 'SWING_TRADER' THEN 1
      WHEN 'LONG_TERM_HOLDER' THEN 1
      WHEN 'PAYDAY_ACCUMULATOR' THEN 1
      WHEN 'DIVIDEND_REINVESTOR' THEN 1
      WHEN 'LIMIT_DOWN_TRAPPED' THEN 1
      WHEN 'AVERAGE_DOWN_BUYER' THEN 1
      WHEN 'STOP_LOSS_TRADER' THEN 1
      WHEN 'FOMO_BUYER' THEN 1
      WHEN 'PANIC_SELLER' THEN 1
      WHEN 'DIP_BUYER' THEN 1
      WHEN 'PROFIT_LOCKER' THEN 1
      WHEN 'LIQUIDITY_AVOIDANT' THEN 1
      WHEN 'CASH_DEFENSIVE' THEN 1
      WHEN 'WHALE' THEN 1
      WHEN 'SMALL_DIVERSIFIER' THEN 1
      WHEN 'OBSERVER' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_auto_participant_recurring_cash_amount CHECK (recurring_cash_amount IS NULL OR recurring_cash_amount >= 0),
  CONSTRAINT chk_stock_auto_participant_recurring_cash_interval CHECK (recurring_cash_interval_value IS NULL OR (recurring_cash_interval_value >= 0 AND recurring_cash_interval_value <= 1000)),
  CONSTRAINT chk_stock_auto_participant_recurring_cash_unit CHECK (
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
  CONSTRAINT chk_stock_auto_participant_recurring_cash_complete CHECK (
    recurring_cash_amount IS NULL
    OR recurring_cash_amount = 0
    OR (recurring_cash_interval_value IS NOT NULL AND recurring_cash_interval_value > 0 AND recurring_cash_interval_unit IS NOT NULL)
  )
);

CREATE TABLE IF NOT EXISTS stock_auto_participant_profile_config (
  profile_type VARCHAR(40) NOT NULL,
  news_weight DECIMAL(8,4) DEFAULT NULL,
  momentum_weight DECIMAL(8,4) DEFAULT NULL,
  contrarian_weight DECIMAL(8,4) DEFAULT NULL,
  loss_aversion_weight DECIMAL(8,4) DEFAULT NULL,
  herding_weight DECIMAL(8,4) DEFAULT NULL,
  market_making_weight DECIMAL(8,4) DEFAULT NULL,
  overconfidence_weight DECIMAL(8,4) DEFAULT NULL,
  noise_weight DECIMAL(8,4) DEFAULT NULL,
  panic_sell_weight DECIMAL(8,4) DEFAULT NULL,
  dip_buy_weight DECIMAL(8,4) DEFAULT NULL,
  order_multiplier DECIMAL(8,4) NOT NULL,
  aggression_multiplier DECIMAL(8,4) NOT NULL,
  order_ttl_multiplier DECIMAL(8,4) NOT NULL DEFAULT 1.0000,
  quantity_multiplier DECIMAL(8,4) NOT NULL,
  holding_patience_weight DECIMAL(8,4) NOT NULL,
  deep_loss_hold_weight DECIMAL(8,4) NOT NULL,
  profit_taking_weight DECIMAL(8,4) NOT NULL DEFAULT 0.0000,
  recurring_deposit_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
  recurring_deposit_interval_days INT NOT NULL DEFAULT 30,
  recurring_deposit_interval_value DECIMAL(12,4) NOT NULL DEFAULT 30.0000,
  recurring_deposit_interval_unit VARCHAR(20) NOT NULL DEFAULT 'DAY',
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (profile_type),
  CONSTRAINT chk_stock_auto_profile_config_type CHECK (
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
      WHEN 'DAY_TRADER' THEN 1
      WHEN 'SWING_TRADER' THEN 1
      WHEN 'LONG_TERM_HOLDER' THEN 1
      WHEN 'PAYDAY_ACCUMULATOR' THEN 1
      WHEN 'DIVIDEND_REINVESTOR' THEN 1
      WHEN 'LIMIT_DOWN_TRAPPED' THEN 1
      WHEN 'AVERAGE_DOWN_BUYER' THEN 1
      WHEN 'STOP_LOSS_TRADER' THEN 1
      WHEN 'FOMO_BUYER' THEN 1
      WHEN 'PANIC_SELLER' THEN 1
      WHEN 'DIP_BUYER' THEN 1
      WHEN 'PROFIT_LOCKER' THEN 1
      WHEN 'LIQUIDITY_AVOIDANT' THEN 1
      WHEN 'CASH_DEFENSIVE' THEN 1
      WHEN 'WHALE' THEN 1
      WHEN 'SMALL_DIVERSIFIER' THEN 1
      WHEN 'OBSERVER' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_auto_profile_news_weight CHECK (news_weight IS NULL OR (news_weight >= 0 AND news_weight <= 1)),
  CONSTRAINT chk_stock_auto_profile_momentum_weight CHECK (momentum_weight IS NULL OR (momentum_weight >= 0 AND momentum_weight <= 1)),
  CONSTRAINT chk_stock_auto_profile_contrarian_weight CHECK (contrarian_weight IS NULL OR (contrarian_weight >= 0 AND contrarian_weight <= 1)),
  CONSTRAINT chk_stock_auto_profile_loss_aversion_weight CHECK (loss_aversion_weight IS NULL OR (loss_aversion_weight >= 0 AND loss_aversion_weight <= 1)),
  CONSTRAINT chk_stock_auto_profile_herding_weight CHECK (herding_weight IS NULL OR (herding_weight >= 0 AND herding_weight <= 1)),
  CONSTRAINT chk_stock_auto_profile_market_making_weight CHECK (market_making_weight IS NULL OR (market_making_weight >= 0 AND market_making_weight <= 1)),
  CONSTRAINT chk_stock_auto_profile_overconfidence_weight CHECK (overconfidence_weight IS NULL OR (overconfidence_weight >= 0 AND overconfidence_weight <= 1)),
  CONSTRAINT chk_stock_auto_profile_noise_weight CHECK (noise_weight IS NULL OR (noise_weight >= 0 AND noise_weight <= 1)),
  CONSTRAINT chk_stock_auto_profile_panic_sell_weight CHECK (panic_sell_weight IS NULL OR (panic_sell_weight >= 0 AND panic_sell_weight <= 1)),
  CONSTRAINT chk_stock_auto_profile_dip_buy_weight CHECK (dip_buy_weight IS NULL OR (dip_buy_weight >= 0 AND dip_buy_weight <= 1)),
  CONSTRAINT chk_stock_auto_profile_order_multiplier CHECK (order_multiplier >= 0 AND order_multiplier <= 5),
  CONSTRAINT chk_stock_auto_profile_aggression_multiplier CHECK (aggression_multiplier >= 0 AND aggression_multiplier <= 5),
  CONSTRAINT chk_stock_auto_profile_order_ttl_multiplier CHECK (order_ttl_multiplier >= 0.1 AND order_ttl_multiplier <= 10),
  CONSTRAINT chk_stock_auto_profile_quantity_multiplier CHECK (quantity_multiplier >= 0 AND quantity_multiplier <= 5),
  CONSTRAINT chk_stock_auto_profile_holding_patience CHECK (holding_patience_weight >= 0 AND holding_patience_weight <= 1),
  CONSTRAINT chk_stock_auto_profile_deep_loss_hold CHECK (deep_loss_hold_weight >= 0 AND deep_loss_hold_weight <= 1),
  CONSTRAINT chk_stock_auto_profile_profit_taking CHECK (profit_taking_weight >= 0 AND profit_taking_weight <= 1),
  CONSTRAINT chk_stock_auto_profile_recurring_deposit CHECK (recurring_deposit_amount >= 0),
  CONSTRAINT chk_stock_auto_profile_recurring_interval CHECK (recurring_deposit_interval_days >= 1),
  CONSTRAINT chk_stock_auto_profile_recurring_interval_value CHECK (recurring_deposit_interval_value >= 0 AND recurring_deposit_interval_value <= 1000),
  CONSTRAINT chk_stock_auto_profile_recurring_interval_unit CHECK (
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
  CONSTRAINT chk_stock_auto_profile_recurring_interval_complete CHECK (
    recurring_deposit_amount = 0
    OR (recurring_deposit_interval_value > 0 AND recurring_deposit_interval_unit IS NOT NULL)
  )
);

CREATE TABLE IF NOT EXISTS stock_virtual_market_config (
  symbol VARCHAR(20) NOT NULL,
  enabled BIT NOT NULL,
  market_status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (symbol),
  KEY idx_stock_virtual_market_enabled (enabled, market_status, symbol),
  CONSTRAINT chk_stock_virtual_market_status CHECK (CASE `market_status` WHEN 'OPEN' THEN 1 WHEN 'CLOSED' THEN 1 WHEN 'HALTED' THEN 1 WHEN 'CIRCUIT_BREAKER' THEN 1 ELSE 0 END = 1)
);

CREATE TABLE IF NOT EXISTS stock_order_book_market_config (
  symbol VARCHAR(20) NOT NULL,
  enabled BIT NOT NULL,
  market_status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (symbol),
  KEY idx_stock_order_book_market_enabled (enabled, market_status, symbol),
  CONSTRAINT chk_stock_order_book_market_status CHECK (CASE `market_status` WHEN 'OPEN' THEN 1 WHEN 'CLOSED' THEN 1 WHEN 'HALTED' THEN 1 WHEN 'CIRCUIT_BREAKER' THEN 1 ELSE 0 END = 1)
);

CREATE TABLE IF NOT EXISTS stock_auto_market_config (
  symbol VARCHAR(20) NOT NULL,
  enabled BIT NOT NULL,
  intensity INT NOT NULL,
  max_order_quantity INT NOT NULL,
  order_ttl_seconds INT NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (symbol),
  KEY idx_stock_auto_market_enabled (enabled, symbol),
  CONSTRAINT chk_stock_auto_market_intensity CHECK (intensity between 1 and 10),
  CONSTRAINT chk_stock_auto_market_max_order_quantity CHECK (max_order_quantity > 0),
  CONSTRAINT chk_stock_auto_market_order_ttl_seconds CHECK (order_ttl_seconds > 0)
);

CREATE TABLE IF NOT EXISTS stock_order_book_daily_regime (
  symbol VARCHAR(20) NOT NULL,
  simulation_trade_date DATE NOT NULL,
  regime_phase VARCHAR(20) NOT NULL,
  price_direction VARCHAR(10) NOT NULL,
  asset_preference VARCHAR(10) NOT NULL,
  direction_intensity INT NOT NULL,
  volatility_level INT NOT NULL,
  liquidity_level INT NOT NULL,
  execution_aggression_level INT NOT NULL DEFAULT 5,
  seed BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (symbol, simulation_trade_date, regime_phase),
  KEY idx_stock_order_book_daily_regime_date (simulation_trade_date, regime_phase, symbol),
  CONSTRAINT chk_stock_order_book_daily_regime_phase CHECK (CASE `regime_phase` WHEN 'OPENING' THEN 1 WHEN 'MIDDAY' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_order_book_daily_regime_price_direction CHECK (CASE `price_direction` WHEN 'UP' THEN 1 WHEN 'DOWN' THEN 1 WHEN 'NEUTRAL' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_order_book_daily_regime_asset_preference CHECK (CASE `asset_preference` WHEN 'STOCK' THEN 1 WHEN 'CASH' THEN 1 WHEN 'BALANCED' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_order_book_daily_regime_intensity CHECK (direction_intensity between 1 and 10),
  CONSTRAINT chk_stock_order_book_daily_regime_volatility CHECK (volatility_level between 1 and 10),
  CONSTRAINT chk_stock_order_book_daily_regime_liquidity CHECK (liquidity_level between 1 and 10),
  CONSTRAINT chk_stock_order_book_daily_regime_execution_aggression CHECK (execution_aggression_level between 1 and 10)
);

CREATE TABLE IF NOT EXISTS stock_order_book_regime_modifier (
  symbol VARCHAR(20) NOT NULL,
  simulation_trade_date DATE NOT NULL,
  regime_phase VARCHAR(20) NOT NULL,
  modifier_window_start_at DATETIME NOT NULL,
  price_direction_modifier INT NOT NULL,
  asset_preference_modifier INT NOT NULL,
  direction_intensity_modifier INT NOT NULL,
  volatility_modifier INT NOT NULL,
  liquidity_modifier INT NOT NULL,
  execution_aggression_modifier INT NOT NULL,
  seed BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (symbol, simulation_trade_date, regime_phase, modifier_window_start_at),
  KEY idx_stock_order_book_regime_modifier_window (simulation_trade_date, regime_phase, modifier_window_start_at, symbol),
  CONSTRAINT chk_stock_order_book_regime_modifier_phase CHECK (CASE `regime_phase` WHEN 'OPENING' THEN 1 WHEN 'MIDDAY' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_order_book_regime_modifier_price_direction CHECK (price_direction_modifier between -10 and 10),
  CONSTRAINT chk_stock_order_book_regime_modifier_asset_preference CHECK (asset_preference_modifier between -10 and 10),
  CONSTRAINT chk_stock_order_book_regime_modifier_intensity CHECK (direction_intensity_modifier between -10 and 10),
  CONSTRAINT chk_stock_order_book_regime_modifier_volatility CHECK (volatility_modifier between -10 and 10),
  CONSTRAINT chk_stock_order_book_regime_modifier_liquidity CHECK (liquidity_modifier between -10 and 10),
  CONSTRAINT chk_stock_order_book_regime_modifier_execution_aggression CHECK (execution_aggression_modifier between -10 and 10)
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

CREATE TABLE IF NOT EXISTS stock_auto_participant_symbol_config (
  user_key VARCHAR(64) NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  enabled BIT NOT NULL,
  intensity INT NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (user_key, symbol),
  KEY idx_stock_auto_participant_symbol_enabled (enabled, symbol, user_key),
  KEY idx_stock_auto_participant_symbol_lookup (symbol, user_key),
  KEY idx_stock_auto_participant_symbol_user_enabled (user_key, enabled, symbol),
  CONSTRAINT chk_stock_auto_participant_symbol_intensity CHECK (intensity between 1 and 10)
);

CREATE TABLE IF NOT EXISTS stock_auto_participant_order_schedule (
  user_key VARCHAR(64) NOT NULL,
  profile_type VARCHAR(40) NOT NULL,
  next_run_at DATETIME NOT NULL,
  last_run_at DATETIME NULL,
  lease_until DATETIME NULL,
  lease_owner VARCHAR(80) NULL,
  run_interval_seconds INT NOT NULL,
  priority INT NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (user_key),
  KEY idx_stock_auto_order_schedule_due (next_run_at, lease_until, priority, user_key),
  KEY idx_stock_auto_order_schedule_profile_due (profile_type, next_run_at, user_key),
  CONSTRAINT chk_stock_auto_order_schedule_interval CHECK (run_interval_seconds > 0),
  CONSTRAINT chk_stock_auto_order_schedule_priority CHECK (priority between 1 and 100)
);

CREATE TABLE IF NOT EXISTS stock_instrument_report_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  symbol VARCHAR(20) NOT NULL,
  event_type VARCHAR(20) NOT NULL,
  title VARCHAR(120) NULL,
  summary VARCHAR(1000) NULL,
  score INT NULL,
  rise_reason VARCHAR(500) NULL,
  fall_reason VARCHAR(500) NULL,
  delete_reason VARCHAR(255) NULL,
  created_by VARCHAR(64) NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_stock_report_symbol_time (symbol, created_at, id),
  CONSTRAINT chk_stock_report_event_type CHECK (CASE `event_type` WHEN 'PUBLISH' THEN 1 WHEN 'UPDATE' THEN 1 WHEN 'DELETE' THEN 1 ELSE 0 END = 1),
  CONSTRAINT chk_stock_report_score CHECK (score IS NULL OR score between 1 and 10),
  CONSTRAINT chk_stock_report_content_scope CHECK ((event_type = 'DELETE' AND title IS NULL AND summary IS NULL AND score IS NULL AND rise_reason IS NULL AND fall_reason IS NULL) OR (event_type IN ('PUBLISH', 'UPDATE') AND title IS NOT NULL AND summary IS NOT NULL AND score IS NOT NULL))
);

CREATE TABLE IF NOT EXISTS portfolio_snapshot (
  id BIGINT NOT NULL AUTO_INCREMENT,
  account_id BIGINT NOT NULL,
  snapshot_date DATE NOT NULL,
  total_asset DECIMAL(19,2) NOT NULL,
  cash_balance DECIMAL(19,2) NOT NULL,
  market_value DECIMAL(19,2) NOT NULL,
  return_rate DECIMAL(9,4) NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_portfolio_snapshot_account_date (account_id, snapshot_date),
  KEY idx_portfolio_snapshot_date_return (snapshot_date, return_rate),
  CONSTRAINT chk_portfolio_snapshot_total_asset_non_negative CHECK (total_asset >= 0),
  CONSTRAINT chk_portfolio_snapshot_cash_balance_non_negative CHECK (cash_balance >= 0),
  CONSTRAINT chk_portfolio_snapshot_market_value_non_negative CHECK (market_value >= 0)
);
