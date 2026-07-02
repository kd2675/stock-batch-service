package stock.batch.service.batch.corporateaction.writer;

import java.math.BigDecimal;
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
}
