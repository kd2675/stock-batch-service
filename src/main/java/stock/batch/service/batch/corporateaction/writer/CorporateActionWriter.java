package stock.batch.service.batch.corporateaction.writer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.corporateaction.model.ExRightsActionRow;
import stock.batch.service.batch.corporateaction.model.ShareEntitlementRow;

@Component
@RequiredArgsConstructor
public class CorporateActionWriter {

    private final JdbcTemplate jdbcTemplate;

    public int markActionExRightsApplied(long actionId, String nextStatus, String sourceStatus, LocalDateTime appliedAt) {
        return jdbcTemplate.update(
                """
                update stock_corporate_action
                   set status = ?,
                       applied_at = ?
                 where id = ?
                   and status = ?
                """,
                nextStatus,
                appliedAt,
                actionId,
                sourceStatus
        );
    }

    public int markDueRightsPayments(
            LocalDate today,
            String paidStatus,
            String actionType,
            String sourceStatus,
            LocalDateTime paidAt
    ) {
        return jdbcTemplate.update(
                """
                update stock_corporate_action
                   set status = ?,
                       paid_at = ?
                 where action_type = ?
                   and status = ?
                   and payment_date <= ?
                """,
                paidStatus,
                paidAt,
                actionType,
                sourceStatus,
                today
        );
    }

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

    public int markActionPaid(long actionId, String paidStatus, String sourceStatus, LocalDateTime paidAt) {
        return jdbcTemplate.update(
                """
                update stock_corporate_action
                   set status = ?,
                       paid_at = ?
                 where id = ?
                   and status = ?
                """,
                paidStatus,
                paidAt,
                actionId,
                sourceStatus
        );
    }

    public int markActionListed(long actionId, String listedStatus, String sourceStatus, LocalDateTime listedAt) {
        return jdbcTemplate.update(
                """
                update stock_corporate_action
                   set status = ?,
                       listed_at = ?
                 where id = ?
                   and status = ?
                """,
                listedStatus,
                listedAt,
                actionId,
                sourceStatus
        );
    }

    public int markActionDelisted(long actionId, String delistedStatus, String sourceStatus, LocalDateTime appliedAt) {
        return jdbcTemplate.update(
                """
                update stock_corporate_action
                   set status = ?,
                       applied_at = ?
                 where id = ?
                   and status = ?
                """,
                delistedStatus,
                appliedAt,
                actionId,
                sourceStatus
        );
    }

    public void releaseReservedSellQuantity(long accountId, String symbol, long quantity, LocalDateTime updatedAt) {
        jdbcTemplate.update(
                """
                update stock_holding
                   set reserved_quantity = case
                           when reserved_quantity >= ? then reserved_quantity - ?
                           else 0
                       end,
                       updated_at = ?
                 where account_id = ?
                   and symbol = ?
                """,
                quantity,
                quantity,
                updatedAt,
                accountId,
                symbol
        );
    }

    public void cancelOrder(long orderId, LocalDateTime updatedAt) {
        jdbcTemplate.update(
                """
                update stock_order
                   set status = 'CANCELLED',
                       reserved_cash = 0,
                       updated_at = ?
                 where id = ?
                   and status in ('PENDING', 'PARTIALLY_FILLED')
                """,
                updatedAt,
                orderId
        );
    }

    public int delistInstrument(String symbol, LocalDateTime updatedAt) {
        return jdbcTemplate.update(
                """
                update stock_order_book_instrument
                   set enabled = false,
                       tradable_shares = 0,
                       updated_at = ?
                 where symbol = ?
                """,
                updatedAt,
                symbol
        );
    }

    public void haltOrderBookMarket(String symbol, LocalDateTime updatedAt) {
        jdbcTemplate.update(
                """
                update stock_order_book_market_config
                   set enabled = false,
                       market_status = 'HALTED',
                       updated_at = ?
                 where symbol = ?
                """,
                updatedAt,
                symbol
        );
    }

    public void disableAutoMarket(String symbol, LocalDateTime updatedAt) {
        jdbcTemplate.update(
                """
                update stock_auto_market_config
                   set enabled = false,
                       updated_at = ?
                 where symbol = ?
                """,
                updatedAt,
                symbol
        );
    }

    public void disableListingAutoAccount(String symbol, LocalDateTime updatedAt) {
        jdbcTemplate.update(
                """
                update stock_listing_auto_account_config
                   set enabled = false,
                       updated_at = ?
                 where symbol = ?
                """,
                updatedAt,
                symbol
        );
    }

    public void disableParticipantSymbolConfigs(String symbol, LocalDateTime updatedAt) {
        jdbcTemplate.update(
                """
                update stock_auto_participant_symbol_config
                   set enabled = false,
                       updated_at = ?
                 where symbol = ?
                """,
                updatedAt,
                symbol
        );
    }

    public int addIssuedAndTradableShares(String symbol, long shareQuantity, LocalDateTime updatedAt) {
        return jdbcTemplate.update(
                """
                update stock_order_book_instrument
                   set issued_shares = issued_shares + ?,
                       tradable_shares = tradable_shares + ?,
                       updated_at = ?
                 where symbol = ?
                """,
                shareQuantity,
                shareQuantity,
                updatedAt,
                symbol
        );
    }

    public int multiplyInstrumentShares(String symbol, int multiplier, LocalDateTime updatedAt) {
        return jdbcTemplate.update(
                """
                update stock_order_book_instrument
                   set issued_shares = issued_shares * ?,
                       tradable_shares = tradable_shares * ?,
                       updated_at = ?
                 where symbol = ?
                """,
                multiplier,
                multiplier,
                updatedAt,
                symbol
        );
    }

    public void multiplyHoldingsForSplit(
            String symbol,
            int multiplier,
            BigDecimal priceDivisor,
            LocalDateTime updatedAt
    ) {
        jdbcTemplate.update(
                """
                update stock_holding
                   set quantity = quantity * ?,
                       reserved_quantity = reserved_quantity * ?,
                       average_price = average_price / ?,
                       updated_at = ?
                 where symbol = ?
                """,
                multiplier,
                multiplier,
                priceDivisor,
                updatedAt,
                symbol
        );
    }

    public void adjustPriceForSplit(String symbol, BigDecimal priceDivisor, LocalDateTime priceTime) {
        jdbcTemplate.update(
                """
                update stock_price
                   set current_price = current_price / ?,
                       previous_close = previous_close / ?,
                       price_time = ?,
                       provider = 'corporate-action-split'
                 where symbol = ?
                """,
                priceDivisor,
                priceDivisor,
                priceTime,
                symbol
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
