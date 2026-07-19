package stock.batch.service.batch.execution.writer;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.common.support.StockHoldingReservationJdbcSupport;
import stock.batch.service.batch.execution.model.OrderBookOrderRow;
import stock.batch.service.execution.biz.ExecutionCostCalculator;

@Component
@RequiredArgsConstructor
public class OrderBookExecutionWriter {

    private final JdbcTemplate jdbcTemplate;
    private final ExecutionHoldingJdbcSupport holdingJdbcSupport;
    private final StockHoldingReservationJdbcSupport holdingReservationJdbcSupport;

    public void lockAccountsForUpdate(long firstAccountId, long secondAccountId) {
        if (firstAccountId == secondAccountId) {
            lockAccountForUpdate(firstAccountId);
            return;
        }
        long lowerAccountId = Math.min(firstAccountId, secondAccountId);
        long higherAccountId = Math.max(firstAccountId, secondAccountId);
        int lockedAccountCount = jdbcTemplate.queryForList(
                "select id from stock_account where id in (?, ?) order by id asc for update",
                Long.class,
                lowerAccountId,
                higherAccountId
        ).size();
        if (lockedAccountCount != 2) {
            throw new IllegalStateException(
                    "Order-book execution account lock count mismatch: expected=2, actual="
                            + lockedAccountCount
            );
        }
    }

    private void lockAccountForUpdate(long accountId) {
        jdbcTemplate.queryForList(
                "select id from stock_account where id = ? for update",
                Long.class,
                accountId
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

    public void adjustCash(long accountId, BigDecimal deltaAmount, LocalDateTime updatedAt) {
        if (deltaAmount.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        jdbcTemplate.update(
                "update stock_account set cash_balance = cash_balance + ?, updated_at = ? where id = ?",
                deltaAmount,
                updatedAt,
                accountId
        );
    }

    public int reduceReservedSellHolding(OrderBookOrderRow order, long quantity, LocalDateTime updatedAt) {
        return jdbcTemplate.update(
                """
                update stock_holding
                set quantity = quantity - ?,
                    reserved_quantity = case when reserved_quantity >= ? then reserved_quantity - ? else 0 end,
                    updated_at = ?
                where account_id = ? and symbol = ?
                  and quantity >= ?
                  and reserved_quantity >= ?
                """,
                quantity,
                quantity,
                quantity,
                updatedAt,
                order.accountId(),
                order.symbol(),
                quantity,
                quantity
        );
    }

    public void insertExecutions(
            OrderBookOrderRow order,
            long quantity,
            BigDecimal executionPrice,
            ExecutionCostCalculator.ExecutionAmounts amounts,
            OrderBookOrderRow counterOrder,
            ExecutionCostCalculator.ExecutionAmounts counterAmounts,
            LocalDateTime executedAt
    ) {
        jdbcTemplate.update(
                """
                insert into stock_execution(
                  order_id, account_id, symbol, side, quantity, price,
                  gross_amount, fee_amount, tax_amount, net_amount, realized_profit,
                  source, executed_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'INTERNAL_ORDER_BOOK', ?),
                       (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'INTERNAL_ORDER_BOOK', ?)
                """,
                order.id(),
                order.accountId(),
                order.symbol(),
                order.side(),
                quantity,
                executionPrice,
                amounts.grossAmount(),
                amounts.feeAmount(),
                amounts.taxAmount(),
                amounts.netAmount(),
                amounts.realizedProfit(),
                executedAt,
                counterOrder.id(),
                counterOrder.accountId(),
                counterOrder.symbol(),
                counterOrder.side(),
                quantity,
                executionPrice,
                counterAmounts.grossAmount(),
                counterAmounts.feeAmount(),
                counterAmounts.taxAmount(),
                counterAmounts.netAmount(),
                counterAmounts.realizedProfit(),
                executedAt
        );
    }

    public void updateOrderAfterFill(
            OrderBookOrderRow order,
            long nextFilledQuantity,
            BigDecimal nextAverageFillPrice,
            BigDecimal nextReservedCash,
            LocalDateTime executedAt
    ) {
        String nextStatus = nextFilledQuantity >= order.quantity() ? "FILLED" : "PARTIALLY_FILLED";
        BigDecimal adjustedReservedCash = "FILLED".equals(nextStatus) ? BigDecimal.ZERO : nextReservedCash.max(BigDecimal.ZERO);
        jdbcTemplate.update(
                """
                update stock_order
                set status = ?,
                    filled_quantity = ?,
                    average_fill_price = ?,
                    reserved_cash = ?,
                    updated_at = ?
                where id = ?
                """,
                nextStatus,
                nextFilledQuantity,
                nextAverageFillPrice,
                adjustedReservedCash,
                executedAt,
                order.id()
        );
    }

    public void rejectBuyOrder(OrderBookOrderRow order, LocalDateTime rejectedAt) {
        jdbcTemplate.update(
                """
                update stock_order
                set status = 'REJECTED',
                    reserved_cash = 0,
                    updated_at = ?
                where id = ?
                """,
                rejectedAt,
                order.id()
        );
    }

    public void rejectSellOrder(OrderBookOrderRow order, LocalDateTime rejectedAt) {
        jdbcTemplate.update(
                """
                update stock_order
                set status = 'REJECTED',
                    updated_at = ?
                where id = ?
                """,
                rejectedAt,
                order.id()
        );
    }

    public void releaseReservedSellQuantity(OrderBookOrderRow order, LocalDateTime updatedAt) {
        long quantity = order.remainingQuantity();
        if (quantity <= 0) {
            return;
        }
        holdingReservationJdbcSupport.releaseReservedSellQuantity(order.accountId(), order.symbol(), quantity, updatedAt);
    }

    public void upsertHolding(long accountId, String symbol, long quantity, BigDecimal costAmount, LocalDateTime executedAt) {
        holdingJdbcSupport.upsertHolding(accountId, symbol, quantity, costAmount, executedAt);
    }
}
