package stock.batch.service.batch.corporateaction.writer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.common.support.StockHoldingReservationJdbcSupport;

@Component
@RequiredArgsConstructor
public class CorporateActionWriter {

    private final JdbcTemplate jdbcTemplate;
    private final StockHoldingReservationJdbcSupport holdingReservationJdbcSupport;

    public int markActionExRightsApplied(long actionId, String nextStatus, String sourceStatus, LocalDateTime appliedAt) {
        return markCorporateActionTimestamp(actionId, nextStatus, sourceStatus, "applied_at", appliedAt);
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

    public int markActionPaid(long actionId, String paidStatus, String sourceStatus, LocalDateTime paidAt) {
        return markCorporateActionTimestamp(actionId, paidStatus, sourceStatus, "paid_at", paidAt);
    }

    public int markActionListed(long actionId, String listedStatus, String sourceStatus, LocalDateTime listedAt) {
        return markCorporateActionTimestamp(actionId, listedStatus, sourceStatus, "listed_at", listedAt);
    }

    public int markActionDelisted(long actionId, String delistedStatus, String sourceStatus, LocalDateTime appliedAt) {
        return markCorporateActionTimestamp(actionId, delistedStatus, sourceStatus, "applied_at", appliedAt);
    }

    public void releaseReservedSellQuantity(long accountId, String symbol, long quantity, LocalDateTime updatedAt) {
        holdingReservationJdbcSupport.releaseReservedSellQuantity(accountId, symbol, quantity, updatedAt);
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
        disableSymbolConfig("stock_auto_market_config", symbol, updatedAt);
    }

    public void disableListingAutoAccount(String symbol, LocalDateTime updatedAt) {
        disableSymbolConfig("stock_listing_auto_account_config", symbol, updatedAt);
    }

    public void disableParticipantSymbolConfigs(String symbol, LocalDateTime updatedAt) {
        disableSymbolConfig("stock_auto_participant_symbol_config", symbol, updatedAt);
    }

    private int markCorporateActionTimestamp(
            long actionId,
            String nextStatus,
            String sourceStatus,
            String timestampColumn,
            LocalDateTime timestamp
    ) {
        return jdbcTemplate.update(
                """
                update stock_corporate_action
                   set status = ?,
                       %s = ?
                 where id = ?
                   and status = ?
                """.formatted(timestampColumn),
                nextStatus,
                timestamp,
                actionId,
                sourceStatus
        );
    }

    private void disableSymbolConfig(String tableName, String symbol, LocalDateTime updatedAt) {
        jdbcTemplate.update(
                """
                update %s
                   set enabled = false,
                       updated_at = ?
                 where symbol = ?
                """.formatted(tableName),
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

}
