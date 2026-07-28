USE STOCK_SERVICE;

SET @lp_status_check_sql := IF(
    EXISTS (
        SELECT 1
          FROM information_schema.table_constraints
         WHERE constraint_schema = DATABASE()
           AND table_name = 'stock_liquidity_mandate'
           AND constraint_name = 'chk_stock_liquidity_mandate_status'
    ),
    'ALTER TABLE stock_liquidity_mandate DROP CHECK chk_stock_liquidity_mandate_status',
    'SELECT 1'
);
PREPARE lp_status_check_stmt FROM @lp_status_check_sql;
EXECUTE lp_status_check_stmt;
DEALLOCATE PREPARE lp_status_check_stmt;

ALTER TABLE stock_liquidity_mandate
  ADD CONSTRAINT chk_stock_liquidity_mandate_status
  CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'EXPIRED'));

SET @lp_transition_stage_check_sql := IF(
    EXISTS (
        SELECT 1
          FROM information_schema.table_constraints
         WHERE constraint_schema = DATABASE()
           AND table_name = 'stock_liquidity_transition'
           AND constraint_name = 'chk_stock_liquidity_transition_stage'
    ),
    'ALTER TABLE stock_liquidity_transition DROP CHECK chk_stock_liquidity_transition_stage',
    'SELECT 1'
);
PREPARE lp_transition_stage_check_stmt FROM @lp_transition_stage_check_sql;
EXECUTE lp_transition_stage_check_stmt;
DEALLOCATE PREPARE lp_transition_stage_check_stmt;

ALTER TABLE stock_liquidity_transition
  ADD CONSTRAINT chk_stock_liquidity_transition_stage
  CHECK (stage IN ('PENDING_ACTIVATION', 'LIVE_ACTIVE', 'SUSPENDED'));

SET @lp_transition_activation_check_sql := IF(
    EXISTS (
        SELECT 1
          FROM information_schema.table_constraints
         WHERE constraint_schema = DATABASE()
           AND table_name = 'stock_liquidity_transition'
           AND constraint_name = 'chk_stock_liquidity_transition_activation'
    ),
    'ALTER TABLE stock_liquidity_transition DROP CHECK chk_stock_liquidity_transition_activation',
    'SELECT 1'
);
PREPARE lp_transition_activation_check_stmt FROM @lp_transition_activation_check_sql;
EXECUTE lp_transition_activation_check_stmt;
DEALLOCATE PREPARE lp_transition_activation_check_stmt;

ALTER TABLE stock_liquidity_transition
  ADD CONSTRAINT chk_stock_liquidity_transition_activation
  CHECK (
    (stage = 'PENDING_ACTIVATION' AND activated_at IS NULL)
    OR (stage IN ('LIVE_ACTIVE', 'SUSPENDED') AND activated_at IS NOT NULL)
  );
