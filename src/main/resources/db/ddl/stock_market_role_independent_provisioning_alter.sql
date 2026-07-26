USE STOCK_SERVICE;

-- Allows a role-separated initial issue to stage its tradable allocation in
-- SYSTEM_CUSTODY until an administrator creates the underwriter contract.
-- This changes only a small allocation-audit table and does not touch the hot
-- stock_order or stock_execution ledgers.

SET @has_independent_allocation_reason_check := (
  SELECT COUNT(*)
    FROM information_schema.table_constraints
   WHERE constraint_schema = DATABASE()
     AND table_name = 'stock_security_allocation_ledger'
     AND constraint_name = 'chk_stock_security_allocation_reason'
     AND constraint_type = 'CHECK'
);
SET @independent_allocation_reason_check_ready := (
  SELECT COUNT(*)
    FROM information_schema.table_constraints tc
    JOIN information_schema.check_constraints cc
      ON cc.constraint_schema = tc.constraint_schema
     AND cc.constraint_name = tc.constraint_name
   WHERE tc.constraint_schema = DATABASE()
     AND tc.table_name = 'stock_security_allocation_ledger'
     AND tc.constraint_name = 'chk_stock_security_allocation_reason'
     AND tc.constraint_type = 'CHECK'
     AND cc.check_clause LIKE '%INITIAL_FLOAT_CUSTODY%'
);
SET @drop_independent_allocation_reason_check := IF(
  @independent_allocation_reason_check_ready = 0
    AND @has_independent_allocation_reason_check > 0,
  'ALTER TABLE stock_security_allocation_ledger DROP CHECK chk_stock_security_allocation_reason',
  'SELECT 1'
);
PREPARE stock_independent_role_stmt
  FROM @drop_independent_allocation_reason_check;
EXECUTE stock_independent_role_stmt;
DEALLOCATE PREPARE stock_independent_role_stmt;

SET @add_independent_allocation_reason_check := IF(
  @independent_allocation_reason_check_ready = 0,
  'ALTER TABLE stock_security_allocation_ledger ADD CONSTRAINT chk_stock_security_allocation_reason CHECK (CASE allocation_reason WHEN ''INITIAL_FLOAT_CUSTODY'' THEN 1 WHEN ''INITIAL_FLOAT_UNDERWRITER'' THEN 1 WHEN ''INITIAL_LOCKED_CUSTODY'' THEN 1 WHEN ''PUBLIC_ALLOCATION'' THEN 1 WHEN ''UNSOLD_UNDERWRITING'' THEN 1 WHEN ''CORPORATE_ACTION_ALLOCATION'' THEN 1 WHEN ''LOCK_RELEASE'' THEN 1 WHEN ''LIQUIDITY_SEED_TRANSFER'' THEN 1 ELSE 0 END = 1)',
  'SELECT 1'
);
PREPARE stock_independent_role_stmt
  FROM @add_independent_allocation_reason_check;
EXECUTE stock_independent_role_stmt;
DEALLOCATE PREPARE stock_independent_role_stmt;
