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

    public void createDividendEntitlements(ExRightsActionRow row, String announcedStatus, LocalDateTime createdAt) {
        jdbcTemplate.update(
                """
                insert into stock_corporate_action_entitlement(
                  action_id, account_id, symbol, quantity, share_quantity, cash_amount, status, created_at, paid_at
                )
                select ?, account_id, symbol, quantity, null, quantity * ?, ?, ?, null
                  from stock_holding
                 where symbol = ?
                   and quantity > 0
                """,
                row.id(),
                row.dividendAmount(),
                announcedStatus,
                createdAt,
                row.symbol()
        );
    }

    public void createShareEntitlements(ExRightsActionRow row, String announcedStatus, LocalDateTime createdAt) {
        jdbcTemplate.update(
                """
                insert into stock_corporate_action_entitlement(
                  action_id, account_id, symbol, quantity, share_quantity, cash_amount, status, created_at, paid_at
                )
                select ?, h.account_id, h.symbol, h.quantity,
                       floor(h.quantity * a.share_quantity / i.issued_shares),
                       null, ?, ?, null
                  from stock_holding h
                  join stock_corporate_action a on a.id = ?
                  join stock_order_book_instrument i on i.symbol = h.symbol
                 where h.symbol = ?
                   and h.quantity > 0
                   and floor(h.quantity * a.share_quantity / i.issued_shares) > 0
                """,
                row.id(),
                announcedStatus,
                createdAt,
                row.id(),
                row.symbol()
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
