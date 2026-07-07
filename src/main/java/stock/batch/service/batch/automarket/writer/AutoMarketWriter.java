package stock.batch.service.batch.automarket.writer;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import stock.batch.service.batch.automarket.model.AutoOrder;
import stock.batch.service.batch.common.support.StockHoldingReservationJdbcSupport;
import stock.batch.service.execution.queue.OrderBookReadySymbolQueue;

@Component
@RequiredArgsConstructor
public class AutoMarketWriter {

    private final JdbcTemplate jdbcTemplate;
    private final StockHoldingReservationJdbcSupport holdingReservationJdbcSupport;
    private final OrderBookReadySymbolQueue readySymbolQueue;

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
        releaseReservedSellQuantity(order.accountId(), order.symbol(), remaining, updatedAt);
    }

    public void releaseReservedSellQuantity(long accountId, String symbol, long quantity, LocalDateTime updatedAt) {
        if (quantity <= 0) {
            return;
        }
        holdingReservationJdbcSupport.releaseReservedSellQuantity(accountId, symbol, quantity, updatedAt);
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
                """,
                quantity,
                updatedAt,
                accountId,
                symbol,
                quantity
        );
        return updatedRows > 0;
    }

    public boolean insertLimitOrder(
            String clientOrderId,
            long accountId,
            String symbol,
            String side,
            BigDecimal price,
            long quantity,
            BigDecimal reservedCash,
            LocalDateTime createdAt
    ) {
        return insertLimitOrders(List.of(new LimitOrderInsert(
                clientOrderId,
                accountId,
                symbol,
                side,
                price,
                quantity,
                reservedCash
        )), createdAt) == 1;
    }

    public int insertLimitOrders(List<LimitOrderInsert> orders, LocalDateTime createdAt) {
        if (orders.isEmpty()) {
            return 0;
        }
        int[] updatedRows = jdbcTemplate.batchUpdate(
                """
                insert into stock_order(
                    client_order_id, account_id, symbol, market_type, side, order_type, status,
                    limit_price, quantity, filled_quantity, average_fill_price,
                    reserved_cash, created_at, updated_at
                )
                values (?, ?, ?, 'ORDER_BOOK', ?, 'LIMIT', 'PENDING', ?, ?, 0, null, ?, ?, ?)
                """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        LimitOrderInsert order = orders.get(i);
                        ps.setString(1, order.clientOrderId());
                        ps.setLong(2, order.accountId());
                        ps.setString(3, order.symbol());
                        ps.setString(4, order.side());
                        ps.setBigDecimal(5, order.price());
                        ps.setLong(6, order.quantity());
                        ps.setBigDecimal(7, order.reservedCash());
                        ps.setObject(8, createdAt);
                        ps.setObject(9, createdAt);
                    }

                    @Override
                    public int getBatchSize() {
                        return orders.size();
                    }
                }
        );
        int insertedCount = 0;
        for (int updatedRow : updatedRows) {
            if (updatedRow > 0 || updatedRow == Statement.SUCCESS_NO_INFO) {
                insertedCount++;
            }
        }
        if (insertedCount > 0) {
            enqueueInsertedSymbolsAfterCommit(orders);
        }
        return insertedCount;
    }

    private void enqueueInsertedSymbolsAfterCommit(List<LimitOrderInsert> orders) {
        Set<String> symbols = orders.stream()
                .map(LimitOrderInsert::symbol)
                .collect(Collectors.toSet());
        Runnable enqueueAction = () -> readySymbolQueue.enqueueAll(symbols);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            enqueueAction.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                enqueueAction.run();
            }
        });
    }

    public record LimitOrderInsert(
            String clientOrderId,
            long accountId,
            String symbol,
            String side,
            BigDecimal price,
            long quantity,
            BigDecimal reservedCash
    ) {
    }
}
