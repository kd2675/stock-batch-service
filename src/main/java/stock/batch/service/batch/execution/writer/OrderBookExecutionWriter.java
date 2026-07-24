package stock.batch.service.batch.execution.writer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.common.support.StockHoldingReservationJdbcSupport;
import stock.batch.service.batch.execution.model.OrderBookOrderFillUpdate;
import stock.batch.service.batch.execution.model.OrderBookOrderRow;
import stock.batch.service.execution.biz.ExecutionCostCalculator;

@Component
public class OrderBookExecutionWriter {

    private final JdbcTemplate jdbcTemplate;
    private final ExecutionHoldingJdbcSupport holdingJdbcSupport;
    private final StockHoldingReservationJdbcSupport holdingReservationJdbcSupport;
    private final boolean mysql;
    private final String lockedAccountTable;

    public OrderBookExecutionWriter(
            JdbcTemplate jdbcTemplate,
            ExecutionHoldingJdbcSupport holdingJdbcSupport,
            StockHoldingReservationJdbcSupport holdingReservationJdbcSupport
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.holdingJdbcSupport = holdingJdbcSupport;
        this.holdingReservationJdbcSupport = holdingReservationJdbcSupport;
        String productName = jdbcTemplate.execute(
                (ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName()
        );
        this.mysql = productName != null && productName.toLowerCase(Locale.ROOT).contains("mysql");
        this.lockedAccountTable = mysql
                ? "stock_account force index (primary)"
                : "stock_account";
    }

    public void lockAccountsForUpdate(long firstAccountId, long secondAccountId) {
        if (firstAccountId == secondAccountId) {
            lockAccountForUpdate(firstAccountId);
            return;
        }
        long lowerAccountId = Math.min(firstAccountId, secondAccountId);
        long higherAccountId = Math.max(firstAccountId, secondAccountId);
        // Use exact PK probes in one statement. An IN-list locking read can create a next-key
        // range under InnoDB REPEATABLE READ and unnecessarily serialize account creation.
        int lockedAccountCount = jdbcTemplate.queryForList(
                """
                select stock_account.id
                  from %s
                  join (
                      select cast(? as decimal(19, 0)) as id
                      union all
                      select cast(? as decimal(19, 0)) as id
                  ) selected_account
                    on selected_account.id = stock_account.id
                 order by stock_account.id asc
                 for update
                """.formatted(lockedAccountTable),
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

    public void adjustMatchedAccounts(
            long sellAccountId,
            BigDecimal sellCredit,
            long buyAccountId,
            BigDecimal buyDelta,
            LocalDateTime updatedAt
    ) {
        if (buyDelta.compareTo(BigDecimal.ZERO) == 0) {
            creditCash(sellAccountId, sellCredit, updatedAt);
            return;
        }
        int updatedRows = mysql
                ? updateMatchedAccountsWithExactPrimaryKeyJoin(
                        sellAccountId,
                        sellCredit,
                        buyAccountId,
                        buyDelta,
                        updatedAt
                )
                : updateMatchedAccountsPortable(
                        sellAccountId,
                        sellCredit,
                        buyAccountId,
                        buyDelta,
                        updatedAt
                );
        if (updatedRows != 2) {
            throw new IllegalStateException(
                    "Order-book execution account update count mismatch: expected=2, actual=" + updatedRows
            );
        }
    }

    private int updateMatchedAccountsWithExactPrimaryKeyJoin(
            long sellAccountId,
            BigDecimal sellCredit,
            long buyAccountId,
            BigDecimal buyDelta,
            LocalDateTime updatedAt
    ) {
        return jdbcTemplate.update(
                """
                update stock_account force index (primary)
                  join (
                      select cast(? as decimal(19, 0)) as id
                      union all
                      select cast(? as decimal(19, 0)) as id
                  ) selected_account
                    on selected_account.id = stock_account.id
                   set cash_balance = cash_balance + case stock_account.id
                           when ? then ?
                           when ? then ?
                           else 0
                       end,
                       updated_at = ?
                """,
                sellAccountId,
                buyAccountId,
                sellAccountId,
                sellCredit,
                buyAccountId,
                buyDelta,
                updatedAt
        );
    }

    private int updateMatchedAccountsPortable(
            long sellAccountId,
            BigDecimal sellCredit,
            long buyAccountId,
            BigDecimal buyDelta,
            LocalDateTime updatedAt
    ) {
        return jdbcTemplate.update(
                """
                update stock_account
                   set cash_balance = cash_balance + case id
                           when ? then ?
                           when ? then ?
                           else 0
                       end,
                       updated_at = ?
                 where id in (?, ?)
                """,
                sellAccountId,
                sellCredit,
                buyAccountId,
                buyDelta,
                updatedAt,
                sellAccountId,
                buyAccountId
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

    public void updateOrdersAfterFill(
            OrderBookOrderFillUpdate first,
            OrderBookOrderFillUpdate second,
            LocalDateTime executedAt
    ) {
        if (first.orderId() == second.orderId()) {
            throw new IllegalArgumentException("Order-book fill updates require two distinct order ids");
        }
        int updatedRows = mysql
                ? updateOrdersWithExactPrimaryKeyJoin(first, second, executedAt)
                : updateOrdersPortable(first, second, executedAt);
        if (updatedRows != 2) {
            throw new IllegalStateException(
                    "Order-book execution order update count mismatch: expected=2, actual=" + updatedRows
            );
        }
    }

    private int updateOrdersWithExactPrimaryKeyJoin(
            OrderBookOrderFillUpdate first,
            OrderBookOrderFillUpdate second,
            LocalDateTime executedAt
    ) {
        return jdbcTemplate.update(
                """
                update stock_order force index (primary)
                  join (
                      select cast(? as decimal(19, 0)) as id
                      union all
                      select cast(? as decimal(19, 0)) as id
                  ) selected_order
                    on selected_order.id = stock_order.id
                   set status = case stock_order.id when ? then ? when ? then ? else status end,
                       filled_quantity = case stock_order.id
                           when ? then ?
                           when ? then ?
                           else filled_quantity
                       end,
                       average_fill_price = case stock_order.id
                           when ? then ?
                           when ? then ?
                           else average_fill_price
                       end,
                       reserved_cash = case stock_order.id
                           when ? then ?
                           when ? then ?
                           else reserved_cash
                       end,
                       updated_at = ?
                """,
                first.orderId(),
                second.orderId(),
                first.orderId(),
                first.status(),
                second.orderId(),
                second.status(),
                first.orderId(),
                first.filledQuantity(),
                second.orderId(),
                second.filledQuantity(),
                first.orderId(),
                first.averageFillPrice(),
                second.orderId(),
                second.averageFillPrice(),
                first.orderId(),
                first.reservedCash(),
                second.orderId(),
                second.reservedCash(),
                executedAt
        );
    }

    private int updateOrdersPortable(
            OrderBookOrderFillUpdate first,
            OrderBookOrderFillUpdate second,
            LocalDateTime executedAt
    ) {
        return jdbcTemplate.update(
                """
                update stock_order
                   set status = case id when ? then ? when ? then ? else status end,
                       filled_quantity = case id when ? then ? when ? then ? else filled_quantity end,
                       average_fill_price = case id when ? then ? when ? then ? else average_fill_price end,
                       reserved_cash = case id when ? then ? when ? then ? else reserved_cash end,
                       updated_at = ?
                 where id in (?, ?)
                """,
                first.orderId(),
                first.status(),
                second.orderId(),
                second.status(),
                first.orderId(),
                first.filledQuantity(),
                second.orderId(),
                second.filledQuantity(),
                first.orderId(),
                first.averageFillPrice(),
                second.orderId(),
                second.averageFillPrice(),
                first.orderId(),
                first.reservedCash(),
                second.orderId(),
                second.reservedCash(),
                executedAt,
                first.orderId(),
                second.orderId()
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
