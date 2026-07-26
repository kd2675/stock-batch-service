-- Adds market-role ownership and order provenance.
-- Provenance columns are instant additions. The nullable hot-ledger columns deliberately avoid
-- new CHECK constraints because MySQL rebuilds the multi-million-row stock_order table to add
-- them. Application enums, strategy-origin checks, and startup column readiness remain enforced.
-- Existing orders keep NULL provenance and are treated as ACCOUNT:<account_id> by the matcher.

USE STOCK_SERVICE;

SET @stock_market_role_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE stock_account ADD COLUMN self_trade_group_id VARCHAR(80) NULL AFTER participant_category, ALGORITHM=INSTANT',
        'SELECT 1'
    )
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_account'
       AND column_name = 'self_trade_group_id'
);
PREPARE stock_market_role_statement FROM @stock_market_role_sql;
EXECUTE stock_market_role_statement;
DEALLOCATE PREPARE stock_market_role_statement;

SET @stock_market_role_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE stock_order ADD COLUMN origin_type VARCHAR(40) NULL AFTER account_id, ALGORITHM=INSTANT',
        'SELECT 1'
    )
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_order'
       AND column_name = 'origin_type'
);
PREPARE stock_market_role_statement FROM @stock_market_role_sql;
EXECUTE stock_market_role_statement;
DEALLOCATE PREPARE stock_market_role_statement;

SET @stock_market_role_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE stock_order ADD COLUMN self_trade_group_id VARCHAR(80) NULL AFTER origin_type, ALGORITHM=INSTANT',
        'SELECT 1'
    )
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_order'
       AND column_name = 'self_trade_group_id'
);
PREPARE stock_market_role_statement FROM @stock_market_role_sql;
EXECUTE stock_market_role_statement;
DEALLOCATE PREPARE stock_market_role_statement;

SET @stock_account_category_check_ready = (
    SELECT COUNT(*)
      FROM information_schema.table_constraints tc
      JOIN information_schema.check_constraints cc
        ON cc.constraint_schema = tc.constraint_schema
       AND cc.constraint_name = tc.constraint_name
     WHERE tc.table_schema = DATABASE()
       AND tc.table_name = 'stock_account'
       AND tc.constraint_name = 'chk_stock_account_participant_category'
       AND cc.check_clause LIKE '%INSTITUTIONAL_INVESTOR%'
       AND cc.check_clause LIKE '%LIQUIDITY_PROVIDER%'
       AND cc.check_clause LIKE '%ISSUE_UNDERWRITER%'
       AND cc.check_clause LIKE '%SYSTEM_CUSTODY%'
);
SET @stock_market_role_sql = (
    SELECT IF(
        @stock_account_category_check_ready = 0 AND COUNT(*) > 0,
        'ALTER TABLE stock_account DROP CHECK chk_stock_account_participant_category',
        'SELECT 1'
    )
      FROM information_schema.table_constraints
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_account'
       AND constraint_name = 'chk_stock_account_participant_category'
);
PREPARE stock_market_role_statement FROM @stock_market_role_sql;
EXECUTE stock_market_role_statement;
DEALLOCATE PREPARE stock_market_role_statement;

SET @stock_market_role_sql = IF(
    @stock_account_category_check_ready = 0,
    'ALTER TABLE stock_account ADD CONSTRAINT chk_stock_account_participant_category CHECK (CASE participant_category WHEN ''MANUAL_PARTICIPANT'' THEN 1 WHEN ''AUTO_PARTICIPANT'' THEN 1 WHEN ''LISTING_UNDERWRITER'' THEN 1 WHEN ''INSTITUTIONAL_INVESTOR'' THEN 1 WHEN ''LIQUIDITY_PROVIDER'' THEN 1 WHEN ''ISSUE_UNDERWRITER'' THEN 1 WHEN ''SYSTEM_CUSTODY'' THEN 1 ELSE 0 END = 1)',
    'SELECT 1'
);
PREPARE stock_market_role_statement FROM @stock_market_role_sql;
EXECUTE stock_market_role_statement;
DEALLOCATE PREPARE stock_market_role_statement;

SET @stock_market_role_sql = (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE stock_account ADD CONSTRAINT chk_stock_account_self_trade_group CHECK (self_trade_group_id IS NULL OR self_trade_group_id <> '''')',
        'SELECT 1'
    )
      FROM information_schema.table_constraints
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_account'
       AND constraint_name = 'chk_stock_account_self_trade_group'
);
PREPARE stock_market_role_statement FROM @stock_market_role_sql;
EXECUTE stock_market_role_statement;
DEALLOCATE PREPARE stock_market_role_statement;

SET @stock_close_snapshot_category_check_ready = (
    SELECT COUNT(*)
      FROM information_schema.table_constraints tc
      JOIN information_schema.check_constraints cc
        ON cc.constraint_schema = tc.constraint_schema
       AND cc.constraint_name = tc.constraint_name
     WHERE tc.table_schema = DATABASE()
       AND tc.table_name = 'stock_close_account_snapshot'
       AND tc.constraint_name = 'chk_stock_close_account_snapshot_participant_category'
       AND cc.check_clause LIKE '%INSTITUTIONAL_INVESTOR%'
       AND cc.check_clause LIKE '%LIQUIDITY_PROVIDER%'
       AND cc.check_clause LIKE '%ISSUE_UNDERWRITER%'
       AND cc.check_clause LIKE '%SYSTEM_CUSTODY%'
);
SET @stock_market_role_sql = (
    SELECT IF(
        @stock_close_snapshot_category_check_ready = 0 AND COUNT(*) > 0,
        'ALTER TABLE stock_close_account_snapshot DROP CHECK chk_stock_close_account_snapshot_participant_category',
        'SELECT 1'
    )
      FROM information_schema.table_constraints
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_close_account_snapshot'
       AND constraint_name = 'chk_stock_close_account_snapshot_participant_category'
);
PREPARE stock_market_role_statement FROM @stock_market_role_sql;
EXECUTE stock_market_role_statement;
DEALLOCATE PREPARE stock_market_role_statement;

SET @stock_market_role_sql = IF(
    @stock_close_snapshot_category_check_ready = 0,
    'ALTER TABLE stock_close_account_snapshot ADD CONSTRAINT chk_stock_close_account_snapshot_participant_category CHECK (CASE participant_category WHEN ''MANUAL_PARTICIPANT'' THEN 1 WHEN ''AUTO_PARTICIPANT'' THEN 1 WHEN ''LISTING_UNDERWRITER'' THEN 1 WHEN ''INSTITUTIONAL_INVESTOR'' THEN 1 WHEN ''LIQUIDITY_PROVIDER'' THEN 1 WHEN ''ISSUE_UNDERWRITER'' THEN 1 WHEN ''SYSTEM_CUSTODY'' THEN 1 ELSE 0 END = 1)',
    'SELECT 1'
);
PREPARE stock_market_role_statement FROM @stock_market_role_sql;
EXECUTE stock_market_role_statement;
DEALLOCATE PREPARE stock_market_role_statement;

SET @stock_execution_snapshot_category_check_ready = (
    SELECT COUNT(*)
      FROM information_schema.table_constraints tc
      JOIN information_schema.check_constraints cc
        ON cc.constraint_schema = tc.constraint_schema
       AND cc.constraint_name = tc.constraint_name
     WHERE tc.table_schema = DATABASE()
       AND tc.table_name = 'stock_execution_daily_account_snapshot'
       AND tc.constraint_name = 'chk_stock_execution_daily_account_category'
       AND cc.check_clause LIKE '%INSTITUTIONAL_INVESTOR%'
       AND cc.check_clause LIKE '%LIQUIDITY_PROVIDER%'
       AND cc.check_clause LIKE '%ISSUE_UNDERWRITER%'
       AND cc.check_clause LIKE '%SYSTEM_CUSTODY%'
);
SET @stock_market_role_sql = (
    SELECT IF(
        @stock_execution_snapshot_category_check_ready = 0 AND COUNT(*) > 0,
        'ALTER TABLE stock_execution_daily_account_snapshot DROP CHECK chk_stock_execution_daily_account_category',
        'SELECT 1'
    )
      FROM information_schema.table_constraints
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_execution_daily_account_snapshot'
       AND constraint_name = 'chk_stock_execution_daily_account_category'
);
PREPARE stock_market_role_statement FROM @stock_market_role_sql;
EXECUTE stock_market_role_statement;
DEALLOCATE PREPARE stock_market_role_statement;

SET @stock_market_role_sql = IF(
    @stock_execution_snapshot_category_check_ready = 0,
    'ALTER TABLE stock_execution_daily_account_snapshot ADD CONSTRAINT chk_stock_execution_daily_account_category CHECK (CASE participant_category WHEN ''MANUAL_PARTICIPANT'' THEN 1 WHEN ''AUTO_PARTICIPANT'' THEN 1 WHEN ''LISTING_UNDERWRITER'' THEN 1 WHEN ''INSTITUTIONAL_INVESTOR'' THEN 1 WHEN ''LIQUIDITY_PROVIDER'' THEN 1 WHEN ''ISSUE_UNDERWRITER'' THEN 1 WHEN ''SYSTEM_CUSTODY'' THEN 1 ELSE 0 END = 1)',
    'SELECT 1'
);
PREPARE stock_market_role_statement FROM @stock_market_role_sql;
EXECUTE stock_market_role_statement;
DEALLOCATE PREPARE stock_market_role_statement;

CREATE TABLE IF NOT EXISTS stock_market_participant (
  id BIGINT NOT NULL AUTO_INCREMENT,
  participant_code VARCHAR(64) NOT NULL,
  display_name VARCHAR(120) NOT NULL,
  participant_type VARCHAR(40) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  self_trade_group_id VARCHAR(80) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_market_participant_code (participant_code),
  UNIQUE KEY uk_stock_market_participant_self_trade_group (self_trade_group_id),
  KEY idx_stock_market_participant_type_status (participant_type, status, id),
  CONSTRAINT chk_stock_market_participant_code CHECK (participant_code <> ''),
  CONSTRAINT chk_stock_market_participant_name CHECK (display_name <> ''),
  CONSTRAINT chk_stock_market_participant_type CHECK (
    CASE participant_type
      WHEN 'ISSUE_UNDERWRITER' THEN 1
      WHEN 'LIQUIDITY_PROVIDER' THEN 1
      WHEN 'INSTITUTIONAL_INVESTOR' THEN 1
      WHEN 'SYSTEM_CUSTODY' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_market_participant_status CHECK (
    CASE status WHEN 'ACTIVE' THEN 1 WHEN 'SUSPENDED' THEN 1 WHEN 'RETIRED' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_market_participant_self_trade_group CHECK (self_trade_group_id <> '')
);

CREATE TABLE IF NOT EXISTS stock_market_participant_account (
  id BIGINT NOT NULL AUTO_INCREMENT,
  participant_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  account_role VARCHAR(40) NOT NULL,
  desk_code VARCHAR(64) NOT NULL,
  effective_from DATE NOT NULL,
  effective_to DATE NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_market_participant_account (account_id),
  UNIQUE KEY uk_stock_market_participant_role_desk (participant_id, account_role, desk_code),
  KEY idx_stock_market_participant_account_lookup (
    participant_id, status, account_role, account_id
  ),
  CONSTRAINT chk_stock_market_participant_account_role CHECK (
    CASE account_role
      WHEN 'ISSUE_UNDERWRITER' THEN 1
      WHEN 'LIQUIDITY_PROVIDER' THEN 1
      WHEN 'INSTITUTIONAL_INVESTOR' THEN 1
      WHEN 'SYSTEM_CUSTODY' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_market_participant_account_status CHECK (
    CASE status WHEN 'ACTIVE' THEN 1 WHEN 'SUSPENDED' THEN 1 WHEN 'CLOSED' THEN 1 ELSE 0 END = 1
  ),
  CONSTRAINT chk_stock_market_participant_account_dates CHECK (
    effective_to IS NULL OR effective_to >= effective_from
  ),
  CONSTRAINT chk_stock_market_participant_account_desk CHECK (desk_code <> '')
);

CREATE TABLE IF NOT EXISTS stock_market_policy_version (
  id BIGINT NOT NULL AUTO_INCREMENT,
  policy_scope VARCHAR(40) NOT NULL,
  scope_key VARCHAR(80) NOT NULL,
  version_no BIGINT NOT NULL,
  effective_business_date DATE NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  config_json JSON NOT NULL,
  change_reason VARCHAR(500) NOT NULL,
  changed_by VARCHAR(64) NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_market_policy_version (policy_scope, scope_key, version_no),
  KEY idx_stock_market_policy_effective (
    status, effective_business_date, policy_scope, scope_key, version_no
  ),
  CONSTRAINT chk_stock_market_policy_scope CHECK (
    CASE policy_scope
      WHEN 'GLOBAL_RISK' THEN 1
      WHEN 'AUTO_PARTICIPANT' THEN 1
      WHEN 'INSTITUTIONAL_PORTFOLIO' THEN 1
      WHEN 'LIQUIDITY_MANDATE' THEN 1
      WHEN 'UNDERWRITING_CONTRACT' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_market_policy_status CHECK (
    CASE status
      WHEN 'DRAFT' THEN 1
      WHEN 'SCHEDULED' THEN 1
      WHEN 'ACTIVE' THEN 1
      WHEN 'RETIRED' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_market_policy_scope_key CHECK (scope_key <> ''),
  CONSTRAINT chk_stock_market_policy_version_no CHECK (version_no > 0),
  CONSTRAINT chk_stock_market_policy_reason CHECK (change_reason <> '')
);

CREATE TABLE IF NOT EXISTS stock_order_strategy_origin (
  order_id BIGINT NOT NULL,
  origin_type VARCHAR(40) NOT NULL,
  participant_id BIGINT NOT NULL,
  portfolio_id BIGINT NULL,
  decision_run_id BIGINT NULL,
  liquidity_mandate_id BIGINT NULL,
  underwriting_contract_id BIGINT NULL,
  policy_version BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (order_id),
  KEY idx_stock_order_strategy_participant (
    participant_id, origin_type, order_id
  ),
  KEY idx_stock_order_strategy_decision (
    decision_run_id, order_id
  ),
  KEY idx_stock_order_strategy_liquidity (
    liquidity_mandate_id, order_id
  ),
  KEY idx_stock_order_strategy_underwriting (
    underwriting_contract_id, order_id
  ),
  CONSTRAINT chk_stock_order_strategy_origin_type CHECK (
    CASE origin_type
      WHEN 'INSTITUTIONAL_INVESTOR' THEN 1
      WHEN 'LIQUIDITY_PROVIDER' THEN 1
      WHEN 'ISSUE_UNDERWRITER' THEN 1
      ELSE 0
    END = 1
  ),
  CONSTRAINT chk_stock_order_strategy_owner CHECK (
    (
      origin_type = 'INSTITUTIONAL_INVESTOR'
      AND portfolio_id IS NOT NULL
      AND decision_run_id IS NOT NULL
      AND liquidity_mandate_id IS NULL
      AND underwriting_contract_id IS NULL
    )
    OR (
      origin_type = 'LIQUIDITY_PROVIDER'
      AND portfolio_id IS NULL
      AND decision_run_id IS NULL
      AND liquidity_mandate_id IS NOT NULL
      AND underwriting_contract_id IS NULL
    )
    OR (
      origin_type = 'ISSUE_UNDERWRITER'
      AND portfolio_id IS NULL
      AND decision_run_id IS NULL
      AND liquidity_mandate_id IS NULL
      AND underwriting_contract_id IS NOT NULL
    )
  ),
  CONSTRAINT chk_stock_order_strategy_policy_version CHECK (policy_version > 0)
);
