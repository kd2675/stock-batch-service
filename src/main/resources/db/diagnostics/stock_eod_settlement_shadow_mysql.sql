-- One-time rollout diagnostic for the first frozen portfolio settlement cycle.
--
-- Required caller input:
--   SET @stock_eod_shadow_cycle_id = <close cycle id>;
-- Optional page cursor:
--   SET @stock_eod_shadow_after_account_id = <last account id from the previous page>;
--
-- Run only while the selected cycle is exactly PORTFOLIO_SETTLED and every market fence is
-- closed. The guard returns a rejected status and an empty comparison page after overnight
-- cash/corporate actions have started, because current account/holding/price rows no longer
-- represent the legacy settlement input.
-- This script writes no business or diagnostic row. Each execution compares at most 200 frozen
-- accounts and returns the next account cursor in every result row.

USE STOCK_SERVICE;

START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;

SET @stock_eod_shadow_after_account_id := COALESCE(@stock_eod_shadow_after_account_id, 0);

SELECT CASE
         WHEN @stock_eod_shadow_cycle_id IS NOT NULL
          AND @stock_eod_shadow_cycle_id > 0
          AND @stock_eod_shadow_after_account_id >= 0
          AND cycle.id IS NOT NULL
          AND cycle.scope_type = 'FULL_MARKET'
          AND cycle.scope_key = 'ALL'
          AND cycle.phase = 'PORTFOLIO_SETTLED'
          AND cycle.status = 'PENDING'
          AND cycle.owner_id IS NULL
          AND NOT EXISTS (
              SELECT 1
                FROM stock_post_close_phase_attempt attempt
               WHERE attempt.cycle_id = cycle.id
                 AND attempt.phase = 'PORTFOLIO_SETTLED'
          )
          AND NOT EXISTS (
              SELECT 1
                FROM stock_market_session_fence fence
               WHERE fence.session_state = 'OPEN'
          )
         THEN 1 ELSE 0
       END,
       CASE
         WHEN @stock_eod_shadow_cycle_id IS NULL OR @stock_eod_shadow_cycle_id <= 0
           THEN 'INVALID_CYCLE_ID'
         WHEN @stock_eod_shadow_after_account_id < 0
           THEN 'INVALID_ACCOUNT_CURSOR'
         WHEN cycle.id IS NULL
           THEN 'CYCLE_NOT_FOUND'
         WHEN cycle.scope_type <> 'FULL_MARKET' OR cycle.scope_key <> 'ALL'
           THEN 'NOT_FULL_MARKET_CYCLE'
         WHEN cycle.phase <> 'PORTFOLIO_SETTLED'
           THEN 'NOT_PORTFOLIO_SETTLED'
         WHEN EXISTS (
              SELECT 1
                FROM stock_post_close_phase_attempt attempt
               WHERE attempt.cycle_id = cycle.id
                 AND attempt.phase = 'PORTFOLIO_SETTLED'
          )
           THEN 'OVERNIGHT_PHASE_ALREADY_ATTEMPTED'
         WHEN cycle.status <> 'PENDING' OR cycle.owner_id IS NOT NULL
           THEN 'CYCLE_IS_RUNNING_OR_INELIGIBLE'
         WHEN EXISTS (
              SELECT 1
                FROM stock_market_session_fence fence
               WHERE fence.session_state = 'OPEN'
          )
           THEN 'MARKET_IS_OPEN'
         ELSE 'ACCEPTED'
       END
  INTO @stock_eod_shadow_guard_valid, @stock_eod_shadow_guard_reason
  FROM (SELECT 1 AS singleton) singleton
  LEFT JOIN stock_post_close_cycle cycle
    ON cycle.id = @stock_eod_shadow_cycle_id;

SELECT @stock_eod_shadow_guard_valid AS guard_valid,
       @stock_eod_shadow_guard_reason AS guard_status,
       @stock_eod_shadow_cycle_id AS close_cycle_id,
       @stock_eod_shadow_after_account_id AS after_account_id;

WITH target_account AS (
  SELECT account_snapshot.close_cycle_id,
         account_snapshot.close_run_id,
         account_snapshot.account_id,
         account_snapshot.pre_cancel_cash,
         account_snapshot.pre_cancel_order_reserved_cash,
         account_snapshot.subscription_reserved_cash,
         account_snapshot.post_cancel_cash,
         account_snapshot.external_net_cash_flow,
         account_snapshot.holding_market_value,
         account_snapshot.holding_quantity,
         account_snapshot.reserved_sell_quantity,
         account_snapshot.holding_position_count
    FROM stock_close_account_snapshot account_snapshot
   WHERE account_snapshot.close_cycle_id = @stock_eod_shadow_cycle_id
     AND @stock_eod_shadow_guard_valid = 1
     AND account_snapshot.settlement_target = TRUE
     AND account_snapshot.reconciliation_status = 'MATCHED'
     AND account_snapshot.account_id > @stock_eod_shadow_after_account_id
   ORDER BY account_snapshot.account_id
   LIMIT 200
), legacy_input AS (
  SELECT target.*,
         account.cash_balance AS legacy_cash_balance,
         COALESCE((
           SELECT SUM(
                    CASE
                      WHEN cash_flow.flow_type = 'DEPOSIT'
                       AND cash_flow.reason <> 'DIVIDEND_PAYMENT' THEN cash_flow.amount
                      WHEN cash_flow.flow_type = 'WITHDRAW'
                       AND cash_flow.reason <> 'CAPITAL_INCREASE_SUBSCRIPTION' THEN -cash_flow.amount
                      ELSE 0
                    END
                  )
             FROM stock_account_cash_flow cash_flow
                  FORCE INDEX (idx_stock_account_cash_flow_account_id)
            WHERE cash_flow.account_id = target.account_id
         ), 0) AS legacy_external_net_cash_flow,
         COALESCE((
           SELECT SUM(holding.quantity * COALESCE(price.current_price, holding.average_price))
             FROM stock_holding holding
                  FORCE INDEX (uk_stock_holding_account_symbol)
             LEFT JOIN stock_price price ON price.symbol = holding.symbol
            WHERE holding.account_id = target.account_id
         ), 0) AS legacy_holding_market_value,
         COALESCE((
           SELECT SUM(holding.quantity)
             FROM stock_holding holding
                  FORCE INDEX (uk_stock_holding_account_symbol)
            WHERE holding.account_id = target.account_id
         ), 0) AS legacy_holding_quantity,
         COALESCE((
           SELECT SUM(holding.reserved_quantity)
             FROM stock_holding holding
                  FORCE INDEX (uk_stock_holding_account_symbol)
            WHERE holding.account_id = target.account_id
         ), 0) AS legacy_reserved_sell_quantity,
         COALESCE((
           SELECT SUM(CASE WHEN holding.quantity > 0 THEN 1 ELSE 0 END)
             FROM stock_holding holding
                  FORCE INDEX (uk_stock_holding_account_symbol)
            WHERE holding.account_id = target.account_id
         ), 0) AS legacy_holding_position_count,
         COALESCE((
           SELECT SUM(stock_order.reserved_cash)
             FROM stock_order stock_order
                  FORCE INDEX (idx_stock_order_account_status_created)
            WHERE stock_order.account_id = target.account_id
              AND stock_order.side = 'BUY'
              AND stock_order.status IN ('PENDING', 'PARTIALLY_FILLED')
         ), 0) AS legacy_open_order_reserved_cash,
         COALESCE((
           SELECT SUM(entitlement.subscribed_cash_amount)
             FROM stock_corporate_action_entitlement entitlement
                  FORCE INDEX (idx_stock_corporate_action_entitlement_account_status)
            WHERE entitlement.account_id = target.account_id
              AND entitlement.status = 'SUBSCRIBED'
              AND entitlement.subscribed_cash_amount IS NOT NULL
         ), 0) AS legacy_subscription_reserved_cash
    FROM target_account target
    JOIN stock_account account ON account.id = target.account_id
), comparison AS (
  SELECT legacy.*,
         legacy.post_cancel_cash
           + legacy.subscription_reserved_cash AS frozen_cash_and_pending_asset,
         legacy.legacy_cash_balance
           + legacy.legacy_open_order_reserved_cash
           + legacy.legacy_subscription_reserved_cash AS legacy_cash_and_pending_asset,
         legacy.post_cancel_cash
           + legacy.subscription_reserved_cash
           + legacy.holding_market_value AS frozen_total_asset,
         legacy.legacy_cash_balance
           + legacy.legacy_open_order_reserved_cash
           + legacy.legacy_subscription_reserved_cash
           + legacy.legacy_holding_market_value AS legacy_total_asset,
         portfolio.cash_balance AS stored_cash_balance,
         portfolio.pending_subscription_asset AS stored_pending_subscription_asset,
         portfolio.market_value AS stored_market_value,
         portfolio.holding_quantity AS stored_holding_quantity,
         portfolio.reserved_sell_quantity AS stored_reserved_sell_quantity,
         portfolio.holding_position_count AS stored_holding_position_count,
         portfolio.total_asset AS stored_total_asset,
         portfolio.return_rate AS stored_return_rate,
         portfolio.calculation_version,
         portfolio.data_quality_status
    FROM legacy_input legacy
    LEFT JOIN portfolio_snapshot portfolio
      ON portfolio.close_cycle_id = legacy.close_cycle_id
     AND portfolio.account_id = legacy.account_id
), result AS (
  SELECT comparison.*,
         CASE
           WHEN comparison.external_net_cash_flow > 0 THEN ROUND(
             (comparison.frozen_total_asset - comparison.external_net_cash_flow)
               * 100 / comparison.external_net_cash_flow,
             4
           )
           ELSE 0
         END AS frozen_return_rate,
         CASE
           WHEN comparison.legacy_external_net_cash_flow > 0 THEN ROUND(
             (comparison.legacy_total_asset - comparison.legacy_external_net_cash_flow)
               * 100 / comparison.legacy_external_net_cash_flow,
             4
           )
           ELSE 0
         END AS legacy_return_rate
    FROM comparison
), classified AS (
  SELECT result.*,
         CASE
           WHEN result.post_cancel_cash = result.legacy_cash_balance
            AND result.frozen_cash_and_pending_asset = result.legacy_cash_and_pending_asset
            AND result.external_net_cash_flow = result.legacy_external_net_cash_flow
            AND result.holding_market_value = result.legacy_holding_market_value
            AND result.holding_quantity = result.legacy_holding_quantity
            AND result.reserved_sell_quantity = result.legacy_reserved_sell_quantity
            AND result.holding_position_count = result.legacy_holding_position_count
            AND result.frozen_total_asset = result.legacy_total_asset
            AND result.frozen_return_rate = result.legacy_return_rate
            AND result.stored_cash_balance = result.post_cancel_cash
            AND result.stored_pending_subscription_asset = result.subscription_reserved_cash
            AND result.stored_market_value = result.holding_market_value
            AND result.stored_holding_quantity = result.holding_quantity
            AND result.stored_reserved_sell_quantity = result.reserved_sell_quantity
            AND result.stored_holding_position_count = result.holding_position_count
            AND result.stored_total_asset = result.frozen_total_asset
            AND result.stored_return_rate = result.frozen_return_rate
            AND result.calculation_version = 'portfolio-v4-explicit-subscription-asset'
            AND result.data_quality_status = 'VERIFIED'
           THEN 'MATCHED'
           ELSE 'MISMATCHED'
         END AS shadow_status
    FROM result
)
SELECT classified.close_cycle_id,
       classified.close_run_id,
       classified.account_id,
       COUNT(*) OVER () AS page_account_count,
       MAX(classified.account_id) OVER () AS next_after_account_id,
       SUM(CASE WHEN classified.shadow_status = 'MISMATCHED' THEN 1 ELSE 0 END)
         OVER () AS page_mismatch_count,
       classified.pre_cancel_cash AS frozen_pre_cancel_cash,
       classified.post_cancel_cash AS frozen_post_cancel_cash,
       classified.legacy_cash_balance,
       classified.pre_cancel_order_reserved_cash AS frozen_order_reserved_cash,
       classified.legacy_open_order_reserved_cash,
       classified.subscription_reserved_cash AS frozen_subscription_reserved_cash,
       classified.stored_pending_subscription_asset,
       classified.legacy_subscription_reserved_cash,
       classified.frozen_cash_and_pending_asset,
       classified.legacy_cash_and_pending_asset,
       classified.external_net_cash_flow AS frozen_external_net_cash_flow,
       classified.legacy_external_net_cash_flow,
       classified.holding_market_value AS frozen_holding_market_value,
       classified.legacy_holding_market_value,
       classified.holding_quantity AS frozen_holding_quantity,
       classified.legacy_holding_quantity,
       classified.reserved_sell_quantity AS frozen_reserved_sell_quantity,
       classified.legacy_reserved_sell_quantity,
       classified.holding_position_count AS frozen_holding_position_count,
       classified.legacy_holding_position_count,
       classified.frozen_total_asset,
       classified.legacy_total_asset,
       classified.frozen_return_rate,
       classified.legacy_return_rate,
       classified.stored_cash_balance,
       classified.stored_market_value,
       classified.stored_holding_quantity,
       classified.stored_reserved_sell_quantity,
       classified.stored_holding_position_count,
       classified.stored_total_asset,
       classified.stored_return_rate,
       classified.calculation_version,
       classified.data_quality_status,
       classified.shadow_status
  FROM classified
 ORDER BY classified.account_id;

COMMIT;
