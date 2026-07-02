package stock.batch.service.batch.automarket.writer;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.automarket.model.AutoOrder;
import stock.batch.service.batch.automarket.model.AutoParticipant;
import stock.batch.service.batch.common.support.StockHoldingReservationJdbcSupport;

@Component
@RequiredArgsConstructor
public class AutoMarketWriter {

    private final JdbcTemplate jdbcTemplate;
    private final StockHoldingReservationJdbcSupport holdingReservationJdbcSupport;

    public void insertAccount(AutoParticipant participant, LocalDateTime createdAt) {
        jdbcTemplate.update(
                """
                insert into stock_account(user_key, cash_balance, created_at, updated_at)
                values (?, 0.00, ?, ?)
                """,
                participant.userKey(),
                createdAt,
                createdAt
        );
    }

    public void creditCash(long accountId, BigDecimal amount, LocalDateTime updatedAt) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        jdbcTemplate.update(
                "update stock_account set cash_balance = cash_balance + ?, updated_at = ? where id = ?",
                amount,
                updatedAt,
                accountId
        );
    }

    public void depositCashFlow(long accountId, BigDecimal amount, String reason, String createdBy, LocalDateTime createdAt) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        jdbcTemplate.update(
                "update stock_account set cash_balance = cash_balance + ?, updated_at = ? where id = ?",
                amount,
                createdAt,
                accountId
        );
        jdbcTemplate.update(
                """
                insert into stock_account_cash_flow(account_id, flow_type, amount, reason, created_by, created_at)
                values (?, 'DEPOSIT', ?, ?, ?, ?)
                """,
                accountId,
                amount,
                reason,
                createdBy,
                createdAt
        );
    }

    public void releaseReservedSellQuantity(AutoOrder order, LocalDateTime updatedAt) {
        long remaining = order.quantity() - order.filledQuantity();
        if (remaining <= 0) {
            return;
        }
        holdingReservationJdbcSupport.releaseReservedSellQuantity(order.accountId(), order.symbol(), remaining, updatedAt);
    }

    public boolean cancelOpenOrder(AutoOrder order, LocalDateTime cancelledAt) {
        int updatedRows = jdbcTemplate.update(
                """
                update stock_order
                set status = 'CANCELLED',
                    reserved_cash = 0,
                    updated_at = ?
                where id = ?
                  and status in ('PENDING', 'PARTIALLY_FILLED')
                """,
                cancelledAt,
                order.id()
        );
        return updatedRows > 0;
    }

    public boolean reserveBuyCash(long accountId, BigDecimal reservedCash, LocalDateTime updatedAt) {
        int updatedRows = jdbcTemplate.update(
                """
                update stock_account
                set cash_balance = cash_balance - ?,
                    updated_at = ?
                where id = ?
                  and status = 'ACTIVE'
                  and cash_balance >= ?
                """,
                reservedCash,
                updatedAt,
                accountId,
                reservedCash
        );
        return updatedRows > 0;
    }

    public boolean reserveSellQuantity(long accountId, String symbol, long quantity, LocalDateTime updatedAt) {
        int updatedRows = jdbcTemplate.update(
                """
                update stock_holding
                set reserved_quantity = reserved_quantity + ?,
                    updated_at = ?
                where account_id = ?
                  and symbol = ?
                  and quantity - reserved_quantity >= ?
                  and exists (
                      select 1
                      from stock_account a
                      where a.id = stock_holding.account_id
                        and a.status = 'ACTIVE'
                  )
                """,
                quantity,
                updatedAt,
                accountId,
                symbol,
                quantity
        );
        return updatedRows > 0;
    }

    public void insertLimitOrder(
            String clientOrderId,
            long accountId,
            String symbol,
            String side,
            BigDecimal price,
            long quantity,
            BigDecimal reservedCash,
            LocalDateTime createdAt
    ) {
        jdbcTemplate.update(
                """
                insert into stock_order(
                    client_order_id, account_id, symbol, market_type, side, order_type, status,
                    limit_price, quantity, filled_quantity, average_fill_price,
                    reserved_cash, created_at, updated_at
                )
                values (?, ?, ?, 'ORDER_BOOK', ?, 'LIMIT', 'PENDING', ?, ?, 0, null, ?, ?, ?)
                """,
                clientOrderId,
                accountId,
                symbol,
                side,
                price,
                quantity,
                reservedCash,
                createdAt,
                createdAt
        );
    }
}
