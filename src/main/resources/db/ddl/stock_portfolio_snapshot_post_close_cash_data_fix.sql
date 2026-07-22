USE STOCK_SERVICE;

-- Idempotent portfolio snapshot asset-composition migration.
--
-- EOD cash is the available cash after open BUY reservations have been returned. A subscribed
-- capital-increase amount is not available cash, but it remains an asset until the subscribed
-- shares are listed,
-- so it is persisted explicitly instead of being inferred from a residual. This migration is
-- bounded to portfolio_snapshot, immutable close-account inputs, and the low-volume corporate
-- action entitlement ledger. It never reads or writes stock_order or stock_execution.

SET SESSION lock_wait_timeout = 15;

SET @stock_portfolio_asset_table_count := (
  SELECT COUNT(*)
    FROM information_schema.tables
   WHERE table_schema = DATABASE()
     AND table_name = 'portfolio_snapshot'
     AND table_type = 'BASE TABLE'
     AND engine = 'InnoDB'
);

SET @stock_portfolio_pending_asset_column_count := (
  SELECT COUNT(*)
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'portfolio_snapshot'
     AND column_name = 'pending_subscription_asset'
);

SET @stock_portfolio_pending_asset_compatible_column_count := (
  SELECT COUNT(*)
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'portfolio_snapshot'
     AND column_name = 'pending_subscription_asset'
     AND data_type = 'decimal'
     AND numeric_precision = 19
     AND numeric_scale = 2
);

SET @stock_portfolio_pending_asset_guard_sql := IF(
  @stock_portfolio_asset_table_count = 1
  AND (
    @stock_portfolio_pending_asset_column_count = 0
    OR (
      @stock_portfolio_pending_asset_column_count = 1
      AND @stock_portfolio_pending_asset_compatible_column_count = 1
    )
  ),
  'SELECT 1',
  'SELECT 1 FROM stock_migration_required_portfolio_pending_subscription_asset_schema'
);

PREPARE stock_portfolio_pending_asset_guard_stmt
  FROM @stock_portfolio_pending_asset_guard_sql;
EXECUTE stock_portfolio_pending_asset_guard_stmt;
DEALLOCATE PREPARE stock_portfolio_pending_asset_guard_stmt;

SET @stock_portfolio_pending_asset_add_sql := IF(
  @stock_portfolio_pending_asset_column_count = 0,
  'ALTER TABLE portfolio_snapshot
     ADD COLUMN pending_subscription_asset DECIMAL(19,2) NULL AFTER cash_balance',
  'SELECT 1'
);

PREPARE stock_portfolio_pending_asset_add_stmt
  FROM @stock_portfolio_pending_asset_add_sql;
EXECUTE stock_portfolio_pending_asset_add_stmt;
DEALLOCATE PREPARE stock_portfolio_pending_asset_add_stmt;

DROP TEMPORARY TABLE IF EXISTS stock_portfolio_asset_fix_guard;
CREATE TEMPORARY TABLE stock_portfolio_asset_fix_guard (
  validation_name VARCHAR(80) NOT NULL,
  invalid_count BIGINT NOT NULL,
  CONSTRAINT chk_stock_portfolio_asset_fix_guard CHECK (invalid_count = 0)
);

DROP TEMPORARY TABLE IF EXISTS stock_portfolio_asset_fix_target;
CREATE TEMPORARY TABLE stock_portfolio_asset_fix_target (
  portfolio_snapshot_id BIGINT NOT NULL,
  correction_kind VARCHAR(20) NOT NULL,
  corrected_cash_balance DECIMAL(19,2) NOT NULL,
  corrected_pending_subscription_asset DECIMAL(19,2) NOT NULL,
  corrected_input_hash VARCHAR(64) NULL,
  corrected_calculation_version VARCHAR(40) NOT NULL,
  corrected_data_quality_status VARCHAR(20) NOT NULL,
  PRIMARY KEY (portfolio_snapshot_id)
);

START TRANSACTION;

-- Refuse unknown calculation contracts rather than silently reclassifying their assets.
INSERT INTO stock_portfolio_asset_fix_guard(validation_name, invalid_count)
SELECT 'supported-calculation-version', COUNT(*)
  FROM portfolio_snapshot portfolio
 WHERE portfolio.calculation_version IS NOT NULL
   AND portfolio.calculation_version NOT IN (
       'portfolio-v1-post-close-cash-backfill',
       'portfolio-v1-explicit-asset-backfill',
       'portfolio-v2-frozen-close',
       'portfolio-v3-post-close-cash',
       'portfolio-v4-explicit-subscription-asset',
       'portfolio-v5-net-contribution-return'
   );

-- v2+ rows have immutable input. Their cash, pending subscription, holdings, and total asset must
-- all be reproducible before the row is upgraded to the explicit subscription-asset representation.
INSERT INTO stock_portfolio_asset_fix_guard(validation_name, invalid_count)
SELECT 'immutable-close-input', COUNT(*)
  FROM portfolio_snapshot portfolio
  LEFT JOIN stock_close_account_snapshot account_snapshot
    ON account_snapshot.close_cycle_id = portfolio.close_cycle_id
   AND account_snapshot.account_id = portfolio.account_id
 WHERE portfolio.calculation_version IN (
       'portfolio-v2-frozen-close',
       'portfolio-v3-post-close-cash',
       'portfolio-v4-explicit-subscription-asset',
       'portfolio-v5-net-contribution-return'
   )
   AND (
       portfolio.close_cycle_id IS NULL
       OR portfolio.close_run_id IS NULL
       OR portfolio.input_hash IS NULL
       OR portfolio.holding_quantity IS NULL
       OR portfolio.reserved_sell_quantity IS NULL
       OR portfolio.holding_position_count IS NULL
       OR account_snapshot.id IS NULL
       OR account_snapshot.close_run_id <> portfolio.close_run_id
       OR account_snapshot.settlement_target <> TRUE
       OR account_snapshot.reconciliation_status <> 'MATCHED'
       OR account_snapshot.post_cancel_cash IS NULL
       OR account_snapshot.post_cancel_cash
            <> account_snapshot.pre_cancel_cash
             + account_snapshot.pre_cancel_order_reserved_cash
       OR (
           portfolio.calculation_version = 'portfolio-v2-frozen-close'
           AND portfolio.cash_balance <> account_snapshot.pre_cancel_cash
       )
       OR (
           portfolio.calculation_version IN (
               'portfolio-v3-post-close-cash',
               'portfolio-v4-explicit-subscription-asset',
               'portfolio-v5-net-contribution-return'
           )
           AND portfolio.cash_balance <> account_snapshot.post_cancel_cash
       )
       OR portfolio.market_value <> account_snapshot.holding_market_value
       OR portfolio.total_asset
            <> account_snapshot.post_cancel_cash
             + account_snapshot.subscription_reserved_cash
             + portfolio.market_value
       OR portfolio.holding_quantity <> account_snapshot.holding_quantity
       OR portfolio.reserved_sell_quantity <> account_snapshot.reserved_sell_quantity
       OR portfolio.holding_position_count <> account_snapshot.holding_position_count
   );

INSERT INTO stock_portfolio_asset_fix_target(
    portfolio_snapshot_id,
    correction_kind,
    corrected_cash_balance,
    corrected_pending_subscription_asset,
    corrected_input_hash,
    corrected_calculation_version,
    corrected_data_quality_status
)
SELECT portfolio.id,
       'IMMUTABLE',
       account_snapshot.post_cancel_cash,
       account_snapshot.subscription_reserved_cash,
       SHA2(
           CONCAT(
               CAST(portfolio.close_cycle_id AS CHAR), '|',
               CAST(portfolio.close_run_id AS CHAR), '|',
               CAST(portfolio.account_id AS CHAR), '|',
               IF(
                   account_snapshot.post_cancel_cash = 0,
                   '0',
                   TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(account_snapshot.post_cancel_cash AS CHAR)))
               ), '|',
               IF(
                   account_snapshot.external_net_cash_flow = 0,
                   '0',
                   TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(account_snapshot.external_net_cash_flow AS CHAR)))
               ), '|',
               IF(
                   portfolio.market_value = 0,
                   '0',
                   TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(portfolio.market_value AS CHAR)))
               ), '|',
               IF(
                   account_snapshot.subscription_reserved_cash = 0,
                   '0',
                   TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(account_snapshot.subscription_reserved_cash AS CHAR)))
               ), '|',
               CAST(portfolio.holding_quantity AS CHAR), '|',
               CAST(portfolio.reserved_sell_quantity AS CHAR), '|',
               CAST(portfolio.holding_position_count AS CHAR)
           ),
           256
       ),
       CASE
         WHEN portfolio.calculation_version = 'portfolio-v5-net-contribution-return'
           THEN 'portfolio-v5-net-contribution-return'
         ELSE 'portfolio-v4-explicit-subscription-asset'
       END,
       'VERIFIED'
  FROM portfolio_snapshot portfolio
  JOIN stock_close_account_snapshot account_snapshot
    ON account_snapshot.close_cycle_id = portfolio.close_cycle_id
   AND account_snapshot.account_id = portfolio.account_id
 WHERE portfolio.calculation_version IN (
       'portfolio-v2-frozen-close',
       'portfolio-v3-post-close-cash',
       'portfolio-v4-explicit-subscription-asset',
       'portfolio-v5-net-contribution-return'
   );

-- Legacy snapshots predate immutable close inputs. Reconstruct their pending subscription asset
-- from the entitlement lifetime at the actual snapshot timestamp, then fold only the remaining
-- open-BUY residual into post-close available cash.
INSERT INTO stock_portfolio_asset_fix_target(
    portfolio_snapshot_id,
    correction_kind,
    corrected_cash_balance,
    corrected_pending_subscription_asset,
    corrected_input_hash,
    corrected_calculation_version,
    corrected_data_quality_status
)
SELECT portfolio.id,
       'LEGACY',
       portfolio.total_asset
         - portfolio.market_value
         - COALESCE(subscription.pending_asset, 0),
       COALESCE(subscription.pending_asset, 0),
       NULL,
       'portfolio-v1-explicit-asset-backfill',
       'WARNING'
  FROM portfolio_snapshot portfolio
  LEFT JOIN (
       SELECT legacy.id AS portfolio_snapshot_id,
              SUM(entitlement.subscribed_cash_amount) AS pending_asset
         FROM portfolio_snapshot legacy
         JOIN stock_corporate_action_entitlement entitlement
           ON entitlement.account_id = legacy.account_id
          AND entitlement.subscribed_cash_amount IS NOT NULL
          AND entitlement.subscribed_at IS NOT NULL
          AND entitlement.subscribed_at <= legacy.created_at
          AND (
              entitlement.paid_at IS NULL
              OR entitlement.paid_at > legacy.created_at
          )
        WHERE legacy.calculation_version IS NULL
           OR legacy.calculation_version IN (
               'portfolio-v1-post-close-cash-backfill',
               'portfolio-v1-explicit-asset-backfill'
           )
        GROUP BY legacy.id
  ) subscription ON subscription.portfolio_snapshot_id = portfolio.id
 WHERE portfolio.calculation_version IS NULL
    OR portfolio.calculation_version IN (
        'portfolio-v1-post-close-cash-backfill',
        'portfolio-v1-explicit-asset-backfill'
    );

INSERT INTO stock_portfolio_asset_fix_guard(validation_name, invalid_count)
SELECT 'target-coverage', ABS(
    (SELECT COUNT(*) FROM portfolio_snapshot)
    - (SELECT COUNT(*) FROM stock_portfolio_asset_fix_target)
);

INSERT INTO stock_portfolio_asset_fix_guard(validation_name, invalid_count)
SELECT 'non-negative-corrected-assets', COUNT(*)
  FROM stock_portfolio_asset_fix_target correction
 WHERE correction.corrected_cash_balance < 0
    OR correction.corrected_pending_subscription_asset < 0;

SET @stock_portfolio_asset_fix_target_count := (
  SELECT COUNT(*) FROM stock_portfolio_asset_fix_target
);

UPDATE portfolio_snapshot portfolio
JOIN stock_portfolio_asset_fix_target correction
  ON correction.portfolio_snapshot_id = portfolio.id
   SET portfolio.cash_balance = correction.corrected_cash_balance,
       portfolio.pending_subscription_asset = correction.corrected_pending_subscription_asset,
       portfolio.input_hash = correction.corrected_input_hash,
       portfolio.calculation_version = correction.corrected_calculation_version,
       portfolio.data_quality_status = correction.corrected_data_quality_status;

SET @stock_portfolio_asset_fix_updated_count := ROW_COUNT();

INSERT INTO stock_portfolio_asset_fix_guard(validation_name, invalid_count)
SELECT 'post-correction-target', COUNT(*)
  FROM stock_portfolio_asset_fix_target correction
  JOIN portfolio_snapshot portfolio
    ON portfolio.id = correction.portfolio_snapshot_id
 WHERE portfolio.cash_balance <> correction.corrected_cash_balance
    OR portfolio.pending_subscription_asset
         <> correction.corrected_pending_subscription_asset
    OR NOT (portfolio.input_hash <=> correction.corrected_input_hash)
    OR portfolio.calculation_version <> correction.corrected_calculation_version
    OR portfolio.data_quality_status <> correction.corrected_data_quality_status
    OR portfolio.total_asset
         <> portfolio.cash_balance
          + portfolio.pending_subscription_asset
          + portfolio.market_value;

COMMIT;

-- Tighten the column and add invariants only after every existing row has been normalized.
SET @stock_portfolio_pending_asset_column_is_final := (
  SELECT COUNT(*)
    FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'portfolio_snapshot'
     AND column_name = 'pending_subscription_asset'
     AND data_type = 'decimal'
     AND numeric_precision = 19
     AND numeric_scale = 2
     AND is_nullable = 'NO'
     AND CAST(column_default AS DECIMAL(19,2)) = 0.00
);

SET @stock_portfolio_pending_asset_finalize_sql := IF(
  @stock_portfolio_pending_asset_column_is_final = 1,
  'SELECT 1',
  'ALTER TABLE portfolio_snapshot
     MODIFY COLUMN pending_subscription_asset DECIMAL(19,2) NOT NULL DEFAULT 0.00 AFTER cash_balance'
);

PREPARE stock_portfolio_pending_asset_finalize_stmt
  FROM @stock_portfolio_pending_asset_finalize_sql;
EXECUTE stock_portfolio_pending_asset_finalize_stmt;
DEALLOCATE PREPARE stock_portfolio_pending_asset_finalize_stmt;

SET @stock_portfolio_pending_asset_non_negative_check_count := (
  SELECT COUNT(*)
    FROM information_schema.table_constraints
   WHERE table_schema = DATABASE()
     AND table_name = 'portfolio_snapshot'
     AND constraint_name = 'chk_portfolio_snapshot_pending_subscription_non_negative'
     AND constraint_type = 'CHECK'
);

SET @stock_portfolio_pending_asset_non_negative_sql := IF(
  @stock_portfolio_pending_asset_non_negative_check_count = 0,
  'ALTER TABLE portfolio_snapshot
     ADD CONSTRAINT chk_portfolio_snapshot_pending_subscription_non_negative
     CHECK (pending_subscription_asset >= 0)',
  'SELECT 1'
);

PREPARE stock_portfolio_pending_asset_non_negative_stmt
  FROM @stock_portfolio_pending_asset_non_negative_sql;
EXECUTE stock_portfolio_pending_asset_non_negative_stmt;
DEALLOCATE PREPARE stock_portfolio_pending_asset_non_negative_stmt;

SET @stock_portfolio_asset_composition_check_count := (
  SELECT COUNT(*)
    FROM information_schema.table_constraints
   WHERE table_schema = DATABASE()
     AND table_name = 'portfolio_snapshot'
     AND constraint_name = 'chk_portfolio_snapshot_asset_composition'
     AND constraint_type = 'CHECK'
);

SET @stock_portfolio_asset_composition_sql := IF(
  @stock_portfolio_asset_composition_check_count = 0,
  'ALTER TABLE portfolio_snapshot
     ADD CONSTRAINT chk_portfolio_snapshot_asset_composition
     CHECK (total_asset = cash_balance + pending_subscription_asset + market_value)',
  'SELECT 1'
);

PREPARE stock_portfolio_asset_composition_stmt
  FROM @stock_portfolio_asset_composition_sql;
EXECUTE stock_portfolio_asset_composition_stmt;
DEALLOCATE PREPARE stock_portfolio_asset_composition_stmt;

SELECT @stock_portfolio_asset_fix_target_count AS target_count,
       @stock_portfolio_asset_fix_updated_count AS updated_count;

DROP TEMPORARY TABLE stock_portfolio_asset_fix_target;
DROP TEMPORARY TABLE stock_portfolio_asset_fix_guard;
