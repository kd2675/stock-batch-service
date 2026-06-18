package stock.batch.service.execution.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class InternalOrderBookExecutionService {

    private final JdbcTemplate jdbcTemplate;

    @Value("${stock.batch.execution.scan-limit:100}")
    private int scanLimit;

    @Transactional
    public int executeEligibleOrders() {
        List<String> symbols = jdbcTemplate.queryForList(
                """
                select distinct symbol
                from stock_order
                where status in ('PENDING', 'PARTIALLY_FILLED')
                  and order_type in ('LIMIT', 'MARKET')
                order by symbol asc
                """,
                String.class
        );

        int matchCount = 0;
        for (String symbol : symbols) {
            while (matchCount < scanLimit && matchNext(symbol)) {
                matchCount++;
            }
            if (matchCount >= scanLimit) {
                return matchCount;
            }
        }
        return matchCount;
    }

    private boolean matchNext(String symbol) {
        for (OrderRow buyOrder : findBestBuyCandidates(symbol)) {
            OrderRow sellOrder = findBestSell(symbol, buyOrder);
            if (sellOrder != null) {
                long quantity = Math.min(buyOrder.remainingQuantity(), sellOrder.remainingQuantity());
                if (quantity <= 0) {
                    continue;
                }

                BigDecimal executionPrice = resolveExecutionPrice(buyOrder, sellOrder);
                if (executionPrice == null) {
                    continue;
                }
                LocalDateTime executedAt = LocalDateTime.now();
                if (!hasEnoughBuyReserve(buyOrder, quantity, executionPrice)) {
                    rejectBuyOrder(buyOrder, executedAt);
                    continue;
                }
                if (!hasEnoughReservedHolding(sellOrder, quantity)) {
                    rejectSellOrder(sellOrder, executedAt);
                    continue;
                }
                if (!executeSell(sellOrder, quantity, executionPrice, executedAt)) {
                    continue;
                }
                executeBuy(buyOrder, quantity, executionPrice, executedAt);
                return true;
            }
        }
        return false;
    }

    private List<OrderRow> findBestBuyCandidates(String symbol) {
        return jdbcTemplate.query(
                """
                select id, user_key, symbol, side, order_type, limit_price, quantity, filled_quantity, average_fill_price, reserved_cash
                from stock_order
                where symbol = ?
                  and side = 'BUY'
                  and order_type in ('LIMIT', 'MARKET')
                  and status in ('PENDING', 'PARTIALLY_FILLED')
                  and (order_type = 'MARKET' or limit_price is not null)
                order by case when order_type = 'MARKET' then 1 else 0 end desc,
                         limit_price desc,
                         created_at asc
                limit ?
                for update
                """,
                (rs, rowNum) -> mapOrderRow(rs),
                symbol,
                scanLimit
        );
    }

    private OrderRow findBestSell(String symbol, OrderRow buyOrder) {
        String pricePredicate = "MARKET".equals(buyOrder.orderType())
                ? "and order_type = 'LIMIT' and limit_price is not null"
                : "and ((order_type = 'LIMIT' and limit_price <= ?) or order_type = 'MARKET')";
        String orderBy = "MARKET".equals(buyOrder.orderType())
                ? "order by limit_price asc, created_at asc"
                : "order by case when order_type = 'MARKET' then 1 else 0 end desc, limit_price asc, created_at asc";
        Object[] params = "MARKET".equals(buyOrder.orderType())
                ? new Object[]{symbol, buyOrder.userKey()}
                : new Object[]{symbol, buyOrder.userKey(), buyOrder.limitPrice()};
        List<OrderRow> rows = jdbcTemplate.query(
                String.format(
                        """
                        select id, user_key, symbol, side, order_type, limit_price, quantity, filled_quantity, average_fill_price, reserved_cash
                        from stock_order
                        where symbol = ?
                          and side = 'SELL'
                          and order_type in ('LIMIT', 'MARKET')
                          and status in ('PENDING', 'PARTIALLY_FILLED')
                          and user_key <> ?
                          %s
                        %s
                        limit 1
                        for update
                        """,
                        pricePredicate,
                        orderBy
                ),
                (rs, rowNum) -> mapOrderRow(rs),
                params
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private BigDecimal resolveExecutionPrice(OrderRow buyOrder, OrderRow sellOrder) {
        if (sellOrder.limitPrice() != null) {
            return sellOrder.limitPrice();
        }
        return buyOrder.limitPrice();
    }

    private boolean hasEnoughBuyReserve(OrderRow order, long quantity, BigDecimal executionPrice) {
        BigDecimal requiredReserve = executionPrice.multiply(BigDecimal.valueOf(quantity));
        return order.reservedCash().compareTo(requiredReserve) >= 0;
    }

    private boolean hasEnoughReservedHolding(OrderRow order, long quantity) {
        Long count = jdbcTemplate.queryForObject(
                """
                select count(*)
                from stock_holding
                where user_key = ?
                  and symbol = ?
                  and quantity >= ?
                  and reserved_quantity >= ?
                """,
                Long.class,
                order.userKey(),
                order.symbol(),
                quantity,
                quantity
        );
        return count != null && count > 0;
    }

    private void executeBuy(OrderRow order, long quantity, BigDecimal executionPrice, LocalDateTime executedAt) {
        BigDecimal reservedForMatchedQuantity = calculateReservedCashToRelease(order, quantity, executionPrice);
        BigDecimal actualCost = executionPrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal release = reservedForMatchedQuantity.subtract(actualCost).max(BigDecimal.ZERO);
        if (release.compareTo(BigDecimal.ZERO) > 0) {
            jdbcTemplate.update(
                    "update stock_account set cash_balance = cash_balance + ?, updated_at = ? where user_key = ?",
                    release,
                    executedAt,
                    order.userKey()
            );
        }
        upsertHolding(order.userKey(), order.symbol(), quantity, executionPrice, executedAt);
        insertExecution(order, quantity, executionPrice, executedAt);
        updateOrderAfterFill(order, quantity, executionPrice, reservedForMatchedQuantity, executedAt);
    }

    private BigDecimal calculateReservedCashToRelease(OrderRow order, long quantity, BigDecimal executionPrice) {
        if ("MARKET".equals(order.orderType())) {
            if (order.remainingQuantity() == quantity) {
                return order.reservedCash();
            }
            BigDecimal reservedPerShare = order.reservedCash()
                    .divide(BigDecimal.valueOf(order.remainingQuantity()), 2, RoundingMode.HALF_UP);
            return reservedPerShare.multiply(BigDecimal.valueOf(quantity));
        }
        return order.limitPrice().multiply(BigDecimal.valueOf(quantity));
    }

    private boolean executeSell(OrderRow order, long quantity, BigDecimal executionPrice, LocalDateTime executedAt) {
        BigDecimal proceeds = executionPrice.multiply(BigDecimal.valueOf(quantity));
        int updatedRows = jdbcTemplate.update(
                """
                update stock_holding
                set quantity = quantity - ?,
                    reserved_quantity = case when reserved_quantity >= ? then reserved_quantity - ? else 0 end,
                    updated_at = ?
                where user_key = ? and symbol = ?
                  and quantity >= ?
                  and reserved_quantity >= ?
                """,
                quantity,
                quantity,
                quantity,
                executedAt,
                order.userKey(),
                order.symbol(),
                quantity,
                quantity
        );
        if (updatedRows == 0) {
            rejectSellOrder(order, executedAt);
            return false;
        }
        jdbcTemplate.update(
                "update stock_account set cash_balance = cash_balance + ?, updated_at = ? where user_key = ?",
                proceeds,
                executedAt,
                order.userKey()
        );
        jdbcTemplate.update(
                "delete from stock_holding where user_key = ? and symbol = ? and quantity <= 0 and reserved_quantity <= 0",
                order.userKey(),
                order.symbol()
        );
        insertExecution(order, quantity, executionPrice, executedAt);
        updateOrderAfterFill(order, quantity, executionPrice, BigDecimal.ZERO, executedAt);
        return true;
    }

    private void insertExecution(OrderRow order, long quantity, BigDecimal executionPrice, LocalDateTime executedAt) {
        jdbcTemplate.update(
                """
                insert into stock_execution(order_id, user_key, symbol, side, quantity, price, source, executed_at)
                values (?, ?, ?, ?, ?, ?, 'INTERNAL_ORDER_BOOK', ?)
                """,
                order.id(),
                order.userKey(),
                order.symbol(),
                order.side(),
                quantity,
                executionPrice,
                executedAt
        );
    }

    private void updateOrderAfterFill(
            OrderRow order,
            long fillQuantity,
            BigDecimal executionPrice,
            BigDecimal reservedCashToRelease,
            LocalDateTime executedAt
    ) {
        long nextFilledQuantity = order.filledQuantity() + fillQuantity;
        String nextStatus = nextFilledQuantity >= order.quantity() ? "FILLED" : "PARTIALLY_FILLED";
        BigDecimal nextAverageFillPrice = calculateAverageFillPrice(order, fillQuantity, executionPrice);
        BigDecimal nextReservedCash = order.reservedCash().subtract(reservedCashToRelease).max(BigDecimal.ZERO);
        if ("FILLED".equals(nextStatus)) {
            nextReservedCash = BigDecimal.ZERO;
        }
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
                nextReservedCash,
                executedAt,
                order.id()
        );
    }

    private void rejectBuyOrder(OrderRow order, LocalDateTime rejectedAt) {
        if (order.reservedCash().compareTo(BigDecimal.ZERO) > 0) {
            jdbcTemplate.update(
                    "update stock_account set cash_balance = cash_balance + ?, updated_at = ? where user_key = ?",
                    order.reservedCash(),
                    rejectedAt,
                    order.userKey()
            );
        }
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

    private void rejectSellOrder(OrderRow order, LocalDateTime rejectedAt) {
        releaseReservedSellQuantity(order, rejectedAt);
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

    private void releaseReservedSellQuantity(OrderRow order, LocalDateTime updatedAt) {
        long quantity = order.remainingQuantity();
        if (quantity <= 0) {
            return;
        }
        jdbcTemplate.update(
                """
                update stock_holding
                set reserved_quantity = case when reserved_quantity >= ? then reserved_quantity - ? else 0 end,
                    updated_at = ?
                where user_key = ?
                  and symbol = ?
                """,
                quantity,
                quantity,
                updatedAt,
                order.userKey(),
                order.symbol()
        );
    }

    private BigDecimal calculateAverageFillPrice(OrderRow order, long fillQuantity, BigDecimal executionPrice) {
        BigDecimal previousAmount = (order.averageFillPrice() == null ? BigDecimal.ZERO : order.averageFillPrice())
                .multiply(BigDecimal.valueOf(order.filledQuantity()));
        BigDecimal nextAmount = previousAmount.add(executionPrice.multiply(BigDecimal.valueOf(fillQuantity)));
        long nextFilledQuantity = order.filledQuantity() + fillQuantity;
        return nextAmount.divide(BigDecimal.valueOf(nextFilledQuantity), 2, RoundingMode.HALF_UP);
    }

    private void upsertHolding(String userKey, String symbol, long quantity, BigDecimal executionPrice, LocalDateTime executedAt) {
        List<HoldingRow> rows = jdbcTemplate.query(
                "select id, quantity, average_price from stock_holding where user_key = ? and symbol = ?",
                (rs, rowNum) -> new HoldingRow(rs.getLong("id"), rs.getLong("quantity"), rs.getBigDecimal("average_price")),
                userKey,
                symbol
        );
        if (rows.isEmpty()) {
            jdbcTemplate.update(
                    """
                    insert into stock_holding(user_key, symbol, quantity, reserved_quantity, average_price, updated_at)
                    values (?, ?, ?, 0, ?, ?)
                    """,
                    userKey,
                    symbol,
                    quantity,
                    executionPrice,
                    executedAt
            );
            return;
        }
        HoldingRow holding = rows.get(0);
        long nextQuantity = holding.quantity() + quantity;
        BigDecimal totalCost = holding.averagePrice()
                .multiply(BigDecimal.valueOf(holding.quantity()))
                .add(executionPrice.multiply(BigDecimal.valueOf(quantity)));
        BigDecimal nextAveragePrice = totalCost.divide(BigDecimal.valueOf(nextQuantity), 2, RoundingMode.HALF_UP);
        jdbcTemplate.update(
                "update stock_holding set quantity = ?, average_price = ?, updated_at = ? where id = ?",
                nextQuantity,
                nextAveragePrice,
                executedAt,
                holding.id()
        );
    }

    private OrderRow mapOrderRow(ResultSet rs) throws SQLException {
        return new OrderRow(
                rs.getLong("id"),
                rs.getString("user_key"),
                rs.getString("symbol"),
                rs.getString("side"),
                rs.getString("order_type"),
                rs.getBigDecimal("limit_price"),
                rs.getLong("quantity"),
                rs.getLong("filled_quantity"),
                rs.getBigDecimal("average_fill_price"),
                rs.getBigDecimal("reserved_cash")
        );
    }

    private record OrderRow(
            long id,
            String userKey,
            String symbol,
            String side,
            String orderType,
            BigDecimal limitPrice,
            long quantity,
            long filledQuantity,
            BigDecimal averageFillPrice,
            BigDecimal reservedCash
    ) {
        long remainingQuantity() {
            return quantity - filledQuantity;
        }
    }

    private record HoldingRow(long id, long quantity, BigDecimal averagePrice) {
    }
}
