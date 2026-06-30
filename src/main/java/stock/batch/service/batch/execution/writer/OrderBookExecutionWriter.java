package stock.batch.service.batch.execution.writer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.execution.model.OrderBookOrderRow;
import stock.batch.service.execution.biz.ExecutionCostCalculator;

@Component
@RequiredArgsConstructor
public class OrderBookExecutionWriter {

    private final JdbcTemplate jdbcTemplate;

    public void debitCash(long accountId, BigDecimal amount, LocalDateTime updatedAt) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        jdbcTemplate.update(
                "update stock_account set cash_balance = cash_balance - ?, updated_at = ? where id = ?",
                amount,
                updatedAt,
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

    public void deleteEmptyHolding(long accountId, String symbol) {
        jdbcTemplate.update(
                "delete from stock_holding where account_id = ? and symbol = ? and quantity <= 0 and reserved_quantity <= 0",
                accountId,
                symbol
        );
    }

    public void insertExecution(
            OrderBookOrderRow order,
            long quantity,
            BigDecimal executionPrice,
            ExecutionCostCalculator.ExecutionAmounts amounts,
            LocalDateTime executedAt
    ) {
        jdbcTemplate.update(
                """
                insert into stock_execution(
                  order_id, account_id, symbol, side, quantity, price,
                  gross_amount, fee_amount, tax_amount, net_amount, realized_profit,
                  source, executed_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'INTERNAL_ORDER_BOOK', ?)
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
        jdbcTemplate.update(
                """
                update stock_holding
                set reserved_quantity = case when reserved_quantity >= ? then reserved_quantity - ? else 0 end,
                    updated_at = ?
                where account_id = ?
                  and symbol = ?
                """,
                quantity,
                quantity,
                updatedAt,
                order.accountId(),
                order.symbol()
        );
    }

    public void upsertHolding(long accountId, String symbol, long quantity, BigDecimal costAmount, LocalDateTime executedAt) {
        int updatedRows = jdbcTemplate.update(
                """
                update stock_holding
                set average_price = ((average_price * quantity) + ?) / (quantity + ?),
                    quantity = quantity + ?,
                    updated_at = ?
                where account_id = ?
                  and symbol = ?
                """,
                costAmount,
                quantity,
                quantity,
                executedAt,
                accountId,
                symbol
        );
        if (updatedRows == 0) {
            jdbcTemplate.update(
                    """
                    insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                    values (?, ?, ?, 0, ?, ?)
                    """,
                    accountId,
                    symbol,
                    quantity,
                    costAmount.divide(BigDecimal.valueOf(quantity), 2, RoundingMode.HALF_UP),
                    executedAt
            );
        }
    }
}
