package stock.batch.service.batch.corporateaction.writer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.corporateaction.model.ExRightsActionRow;
import stock.batch.service.batch.corporateaction.model.ShareEntitlementRow;

@Component
@RequiredArgsConstructor
public class CorporateActionAccountWriter {

    private final JdbcTemplate jdbcTemplate;

    public int creditCash(long accountId, BigDecimal cashAmount, LocalDateTime updatedAt) {
        return jdbcTemplate.update(
                """
                update stock_account
                   set cash_balance = cash_balance + ?,
                       updated_at = ?
                 where id = ?
                """,
                cashAmount,
                updatedAt,
                accountId
        );
    }

    public void recordDividendPaymentCashFlow(long accountId, BigDecimal cashAmount, LocalDateTime createdAt) {
        jdbcTemplate.update(
                """
                insert into stock_account_cash_flow(account_id, flow_type, amount, reason, created_by, created_at)
                values (?, 'DEPOSIT', ?, 'DIVIDEND_PAYMENT', 'CORPORATE_ACTION', ?)
                """,
                accountId,
                cashAmount,
                createdAt
        );
    }

    public void recordCapitalIncreaseSubscriptionCashFlow(long accountId, BigDecimal cashAmount, LocalDateTime createdAt) {
        jdbcTemplate.update(
                """
                insert into stock_account_cash_flow(account_id, flow_type, amount, reason, created_by, created_at)
                values (?, 'WITHDRAW', ?, 'CAPITAL_INCREASE_SUBSCRIPTION', 'CORPORATE_ACTION_AUTO', ?)
                """,
                accountId,
                cashAmount,
                createdAt
        );
    }

    public boolean withdrawCashForSubscription(long accountId, BigDecimal cashAmount, LocalDateTime updatedAt) {
        return jdbcTemplate.update(
                """
                update stock_account
                   set cash_balance = cash_balance - ?,
                       updated_at = ?
                 where id = ?
                   and status = 'ACTIVE'
                   and cash_balance >= ?
                """,
                cashAmount,
                updatedAt,
                accountId,
                cashAmount
        ) > 0;
    }

    public int subscribeAllocatedRights(
            long entitlementId,
            long shareQuantity,
            BigDecimal cashAmount,
            String subscribedStatus,
            String sourceStatus,
            LocalDateTime subscribedAt
    ) {
        return jdbcTemplate.update(
                """
                update stock_corporate_action_entitlement
                   set status = ?,
                       subscribed_share_quantity = ?,
                       subscribed_cash_amount = ?,
                       subscribed_at = ?
                 where id = ?
                   and status = ?
                   and share_quantity >= ?
                """,
                subscribedStatus,
                shareQuantity,
                cashAmount,
                subscribedAt,
                entitlementId,
                sourceStatus,
                shareQuantity
        );
    }

    public int createPublicOfferingSubscription(
            long actionId,
            long accountId,
            String symbol,
            long shareQuantity,
            BigDecimal cashAmount,
            String subscribedStatus,
            LocalDateTime subscribedAt
    ) {
        return jdbcTemplate.update(
                """
                insert into stock_corporate_action_entitlement(
                  action_id, account_id, symbol, quantity, share_quantity, cash_amount,
                  subscribed_share_quantity, subscribed_cash_amount, status,
                  holding_snapshot_run_id, created_at, subscribed_at, paid_at
                )
                select ?, ?, ?, ?, ?, ?, ?, ?, ?, null, ?, ?, null
                 where not exists (
                     select 1
                       from stock_corporate_action_entitlement e
                      where e.action_id = ?
                        and e.account_id = ?
                 )
                """,
                actionId,
                accountId,
                symbol,
                shareQuantity,
                shareQuantity,
                cashAmount,
                shareQuantity,
                cashAmount,
                subscribedStatus,
                subscribedAt,
                subscribedAt,
                actionId,
                accountId
        );
    }

    public void markEntitlementPaid(long entitlementId, String paidStatus, String sourceStatus, LocalDateTime paidAt) {
        jdbcTemplate.update(
                """
                update stock_corporate_action_entitlement
                   set status = ?,
                       paid_at = ?
                 where id = ?
                   and status = ?
                """,
                paidStatus,
                paidAt,
                entitlementId,
                sourceStatus
        );
    }

    public void expireEntitlements(long actionId, String expiredStatus, String sourceStatus, LocalDateTime updatedAt) {
        jdbcTemplate.update(
                """
                update stock_corporate_action_entitlement
                   set status = ?,
                       paid_at = ?
                 where action_id = ?
                   and status = ?
                """,
                expiredStatus,
                updatedAt,
                actionId,
                sourceStatus
        );
    }

    public void createDividendEntitlements(
            ExRightsActionRow row,
            long holdingSnapshotRunId,
            String announcedStatus,
            LocalDateTime createdAt
    ) {
        jdbcTemplate.update(
                """
                insert into stock_corporate_action_entitlement(
                  action_id, account_id, symbol, quantity, share_quantity, cash_amount, status,
                  holding_snapshot_run_id, created_at, paid_at
                )
                select ?, account_id, symbol, quantity, null, quantity * ?, ?, ?, ?, null
                  from stock_holding_snapshot
                 where symbol = ?
                   and close_run_id = ?
                   and quantity > 0
                """,
                row.id(),
                row.dividendAmount(),
                announcedStatus,
                holdingSnapshotRunId,
                createdAt,
                row.symbol(),
                holdingSnapshotRunId
        );
    }

    public void createShareEntitlements(
            ExRightsActionRow row,
            long holdingSnapshotRunId,
            String announcedStatus,
            LocalDateTime createdAt
    ) {
        jdbcTemplate.update(
                """
                insert into stock_corporate_action_entitlement(
                  action_id, account_id, symbol, quantity, share_quantity, cash_amount, status,
                  holding_snapshot_run_id, created_at, paid_at
                )
                select ?, h.account_id, h.symbol, h.quantity,
                       floor(h.quantity * a.share_quantity / i.issued_shares),
                       null, ?, ?, ?, null
                  from stock_holding_snapshot h
                  join stock_corporate_action a on a.id = ?
                  join stock_order_book_instrument i on i.symbol = h.symbol
                 where h.symbol = ?
                   and h.close_run_id = ?
                   and h.quantity > 0
                   and floor(h.quantity * a.share_quantity / i.issued_shares) > 0
                """,
                row.id(),
                announcedStatus,
                holdingSnapshotRunId,
                createdAt,
                row.id(),
                row.symbol(),
                holdingSnapshotRunId
        );
    }

    public void createPaidInRightsEntitlements(
            ExRightsActionRow row,
            long holdingSnapshotRunId,
            String announcedStatus,
            LocalDateTime createdAt
    ) {
        jdbcTemplate.update(
                """
                insert into stock_corporate_action_entitlement(
                  action_id, account_id, symbol, quantity, share_quantity, cash_amount, status,
                  holding_snapshot_run_id, created_at, paid_at
                )
                select ?, h.account_id, h.symbol, h.quantity,
                       floor(h.quantity * ? / i.issued_shares),
                       floor(h.quantity * ? / i.issued_shares) * ?,
                       ?, ?, ?, null
                  from stock_holding_snapshot h
                  join stock_order_book_instrument i on i.symbol = h.symbol
                 where h.symbol = ?
                   and h.close_run_id = ?
                   and h.quantity > 0
                   and floor(h.quantity * ? / i.issued_shares) > 0
                """,
                row.id(),
                row.shareQuantity(),
                row.shareQuantity(),
                row.issuePrice(),
                announcedStatus,
                holdingSnapshotRunId,
                createdAt,
                row.symbol(),
                holdingSnapshotRunId,
                row.shareQuantity()
        );
    }

    public int creditShareHolding(ShareEntitlementRow entitlement, LocalDateTime updatedAt) {
        return jdbcTemplate.update(
                """
                update stock_holding
                   set average_price = (average_price * quantity) / (quantity + ?),
                       quantity = quantity + ?,
                       updated_at = ?
                 where account_id = ?
                   and symbol = ?
                """,
                entitlement.shareQuantity(),
                entitlement.shareQuantity(),
                updatedAt,
                entitlement.accountId(),
                entitlement.symbol()
        );
    }

    public void creditPaidInSubscribedShareHolding(ShareEntitlementRow entitlement, LocalDateTime updatedAt) {
        BigDecimal cashAmount = entitlement.cashAmount();
        if (cashAmount == null || cashAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Paid-in subscription cash amount is missing: " + entitlement.id());
        }
        int updatedRows = jdbcTemplate.update(
                """
                update stock_holding
                   set average_price = ((average_price * quantity) + ?) / (quantity + ?),
                       quantity = quantity + ?,
                       updated_at = ?
                 where account_id = ?
                   and symbol = ?
                """,
                cashAmount,
                entitlement.shareQuantity(),
                entitlement.shareQuantity(),
                updatedAt,
                entitlement.accountId(),
                entitlement.symbol()
        );
        if (updatedRows == 0) {
            jdbcTemplate.update(
                    """
                    insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                    values (?, ?, ?, 0, ?, ?)
                    """,
                    entitlement.accountId(),
                    entitlement.symbol(),
                    entitlement.shareQuantity(),
                    cashAmount.divide(BigDecimal.valueOf(entitlement.shareQuantity()), 2, RoundingMode.HALF_UP),
                    updatedAt
            );
        }
    }
}
