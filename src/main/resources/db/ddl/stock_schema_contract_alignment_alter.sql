USE STOCK_SERVICE;

SET @stock_schema_contract_alignment_violation_count := (
  (SELECT COUNT(*)
     FROM stock_order_book_daily_snapshot
    WHERE execution_count < 0
       OR execution_quantity < 0
       OR turnover_amount < 0
       OR buy_quantity < 0
       OR sell_quantity < 0
       OR buy_net_amount < 0
       OR sell_net_amount < 0
       OR open_order_count < 0
       OR open_buy_order_count < 0
       OR open_sell_order_count < 0
       OR reserved_buy_cash < 0
       OR holder_count < 0
       OR holding_quantity < 0
       OR pending_corporate_action_count < 0)
  +
  (SELECT COUNT(*)
     FROM stock_order_book_daily_regime
    WHERE regime_phase NOT IN ('OPENING', 'MIDDAY')
       OR execution_aggression_level NOT BETWEEN 1 AND 10)
  +
  (SELECT COUNT(*)
     FROM stock_corporate_action
    WHERE action_type IN ('INITIAL_ISSUE', 'PAID_IN_CAPITAL_INCREASE')
      AND (share_quantity IS NULL OR issue_price IS NULL))
);

-- PREPARE cannot execute SIGNAL; select a descriptive non-existent table to abort an unsafe migration.
SET @stock_schema_contract_alignment_guard_sql := IF(
  @stock_schema_contract_alignment_violation_count > 0,
  'SELECT 1 FROM stock_migration_required_schema_contract_alignment',
  'SELECT 1'
);

PREPARE stock_schema_contract_alignment_guard_stmt FROM @stock_schema_contract_alignment_guard_sql;
EXECUTE stock_schema_contract_alignment_guard_stmt;
DEALLOCATE PREPARE stock_schema_contract_alignment_guard_stmt;

SET @stock_daily_snapshot_market_enabled_default_drop_sql := IF(
  EXISTS (
    SELECT 1
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_order_book_daily_snapshot'
       AND column_name = 'market_enabled'
       AND column_default IS NOT NULL
  ),
  'ALTER TABLE stock_order_book_daily_snapshot ALTER COLUMN market_enabled DROP DEFAULT',
  'SELECT 1'
);
PREPARE stock_daily_snapshot_market_enabled_default_drop_stmt
  FROM @stock_daily_snapshot_market_enabled_default_drop_sql;
EXECUTE stock_daily_snapshot_market_enabled_default_drop_stmt;
DEALLOCATE PREPARE stock_daily_snapshot_market_enabled_default_drop_stmt;

SET @stock_daily_regime_phase_default_drop_sql := IF(
  EXISTS (
    SELECT 1
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'stock_order_book_daily_regime'
       AND column_name = 'regime_phase'
       AND column_default IS NOT NULL
  ),
  'ALTER TABLE stock_order_book_daily_regime ALTER COLUMN regime_phase DROP DEFAULT',
  'SELECT 1'
);
PREPARE stock_daily_regime_phase_default_drop_stmt FROM @stock_daily_regime_phase_default_drop_sql;
EXECUTE stock_daily_regime_phase_default_drop_stmt;
DEALLOCATE PREPARE stock_daily_regime_phase_default_drop_stmt;

SET @stock_daily_snapshot_flow_check_drop_sql := IF(
  EXISTS (
    SELECT 1
      FROM information_schema.check_constraints
     WHERE constraint_schema = DATABASE()
       AND constraint_name = 'chk_stock_order_book_daily_snapshot_flow'
  ),
  'ALTER TABLE stock_order_book_daily_snapshot DROP CHECK chk_stock_order_book_daily_snapshot_flow',
  'SELECT 1'
);
PREPARE stock_daily_snapshot_flow_check_drop_stmt FROM @stock_daily_snapshot_flow_check_drop_sql;
EXECUTE stock_daily_snapshot_flow_check_drop_stmt;
DEALLOCATE PREPARE stock_daily_snapshot_flow_check_drop_stmt;

SET @stock_daily_snapshot_open_order_check_drop_sql := IF(
  EXISTS (
    SELECT 1
      FROM information_schema.check_constraints
     WHERE constraint_schema = DATABASE()
       AND constraint_name = 'chk_stock_order_book_daily_snapshot_open_order'
  ),
  'ALTER TABLE stock_order_book_daily_snapshot DROP CHECK chk_stock_order_book_daily_snapshot_open_order',
  'SELECT 1'
);
PREPARE stock_daily_snapshot_open_order_check_drop_stmt FROM @stock_daily_snapshot_open_order_check_drop_sql;
EXECUTE stock_daily_snapshot_open_order_check_drop_stmt;
DEALLOCATE PREPARE stock_daily_snapshot_open_order_check_drop_stmt;

SET @stock_daily_snapshot_holding_check_drop_sql := IF(
  EXISTS (
    SELECT 1
      FROM information_schema.check_constraints
     WHERE constraint_schema = DATABASE()
       AND constraint_name = 'chk_stock_order_book_daily_snapshot_holding'
  ),
  'ALTER TABLE stock_order_book_daily_snapshot DROP CHECK chk_stock_order_book_daily_snapshot_holding',
  'SELECT 1'
);
PREPARE stock_daily_snapshot_holding_check_drop_stmt FROM @stock_daily_snapshot_holding_check_drop_sql;
EXECUTE stock_daily_snapshot_holding_check_drop_stmt;
DEALLOCATE PREPARE stock_daily_snapshot_holding_check_drop_stmt;

ALTER TABLE stock_order_book_daily_snapshot
  ADD CONSTRAINT chk_stock_order_book_daily_snapshot_flow CHECK (
    execution_count >= 0
    AND execution_quantity >= 0
    AND turnover_amount >= 0
    AND buy_quantity >= 0
    AND sell_quantity >= 0
    AND buy_net_amount >= 0
    AND sell_net_amount >= 0
  ),
  ADD CONSTRAINT chk_stock_order_book_daily_snapshot_open_order CHECK (
    open_order_count >= 0
    AND open_buy_order_count >= 0
    AND open_sell_order_count >= 0
    AND reserved_buy_cash >= 0
  ),
  ADD CONSTRAINT chk_stock_order_book_daily_snapshot_holding CHECK (
    holder_count >= 0
    AND holding_quantity >= 0
    AND pending_corporate_action_count >= 0
  );

SET @stock_daily_regime_phase_check_drop_sql := IF(
  EXISTS (
    SELECT 1
      FROM information_schema.check_constraints
     WHERE constraint_schema = DATABASE()
       AND constraint_name = 'chk_stock_order_book_daily_regime_phase'
  ),
  'ALTER TABLE stock_order_book_daily_regime DROP CHECK chk_stock_order_book_daily_regime_phase',
  'SELECT 1'
);
PREPARE stock_daily_regime_phase_check_drop_stmt FROM @stock_daily_regime_phase_check_drop_sql;
EXECUTE stock_daily_regime_phase_check_drop_stmt;
DEALLOCATE PREPARE stock_daily_regime_phase_check_drop_stmt;

SET @stock_daily_regime_execution_aggression_check_drop_sql := IF(
  EXISTS (
    SELECT 1
      FROM information_schema.check_constraints
     WHERE constraint_schema = DATABASE()
       AND constraint_name = 'chk_stock_order_book_daily_regime_execution_aggression'
  ),
  'ALTER TABLE stock_order_book_daily_regime DROP CHECK chk_stock_order_book_daily_regime_execution_aggression',
  'SELECT 1'
);
PREPARE stock_daily_regime_execution_aggression_check_drop_stmt FROM @stock_daily_regime_execution_aggression_check_drop_sql;
EXECUTE stock_daily_regime_execution_aggression_check_drop_stmt;
DEALLOCATE PREPARE stock_daily_regime_execution_aggression_check_drop_stmt;

ALTER TABLE stock_order_book_daily_regime
  ADD CONSTRAINT chk_stock_order_book_daily_regime_phase CHECK (
    CASE `regime_phase`
      WHEN 'OPENING' THEN 1
      WHEN 'MIDDAY' THEN 1
      ELSE 0
    END = 1
  ),
  ADD CONSTRAINT chk_stock_order_book_daily_regime_execution_aggression CHECK (
    execution_aggression_level BETWEEN 1 AND 10
  );

SET @stock_corporate_action_issue_required_check_drop_sql := IF(
  EXISTS (
    SELECT 1
      FROM information_schema.check_constraints
     WHERE constraint_schema = DATABASE()
       AND constraint_name = 'chk_stock_corporate_action_issue_required'
  ),
  'ALTER TABLE stock_corporate_action DROP CHECK chk_stock_corporate_action_issue_required',
  'SELECT 1'
);
PREPARE stock_corporate_action_issue_required_check_drop_stmt FROM @stock_corporate_action_issue_required_check_drop_sql;
EXECUTE stock_corporate_action_issue_required_check_drop_stmt;
DEALLOCATE PREPARE stock_corporate_action_issue_required_check_drop_stmt;

ALTER TABLE stock_corporate_action
  ADD CONSTRAINT chk_stock_corporate_action_issue_required CHECK (
    action_type NOT IN ('INITIAL_ISSUE', 'PAID_IN_CAPITAL_INCREASE')
    OR (share_quantity IS NOT NULL AND issue_price IS NOT NULL)
  );
