package stock.batch.service.execution.biz;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExecutionService {

    private final JdbcTemplate jdbcTemplate;

    @Value("${stock.batch.execution.scan-limit:100}")
    private int scanLimit;

    @Transactional
    public int executeEligibleOrders() {
        List<OrderCandidate> candidates = jdbcTemplate.query(
                """
                select o.id, o.user_key, o.symbol, o.side, o.order_type, o.limit_price,
                       o.quantity, o.filled_quantity, o.average_fill_price, o.reserved_cash, p.current_price
                from stock_order o
                join stock_price p on p.symbol = o.symbol
                where o.status in ('PENDING', 'PARTIALLY_FILLED')
                order by o.created_at asc
                limit ?
                for update
                """,
                (rs, rowNum) -> mapCandidate(rs),
                scanLimit
        );

        int executedCount = 0;
        for (OrderCandidate candidate : candidates) {
            if (isExecutable(candidate)) {
                execute(candidate);
                executedCount++;
            }
        }
        return executedCount;
    }

    private boolean isExecutable(OrderCandidate candidate) {
        if ("MARKET".equals(candidate.orderType())) {
            return true;
        }
        if (candidate.limitPrice() == null) {
            return false;
        }
        if ("BUY".equals(candidate.side())) {
            return candidate.currentPrice().compareTo(candidate.limitPrice()) <= 0;
        }
        return candidate.currentPrice().compareTo(candidate.limitPrice()) >= 0;
    }

    private void execute(OrderCandidate candidate) {
        long remainingQuantity = candidate.quantity() - candidate.filledQuantity();
        if (remainingQuantity <= 0) {
            return;
        }

        BigDecimal executionPrice = candidate.currentPrice();
        if ("BUY".equals(candidate.side())) {
            if (!executeBuy(candidate, remainingQuantity, executionPrice)) {
                return;
            }
        } else if (!executeSell(candidate, remainingQuantity, executionPrice)) {
            return;
        }

        LocalDateTime executedAt = LocalDateTime.now();
        BigDecimal averageFillPrice = calculateAverageFillPrice(candidate, remainingQuantity, executionPrice);

        jdbcTemplate.update(
                """
                insert into stock_execution(order_id, user_key, symbol, side, quantity, price, source, executed_at)
                values (?, ?, ?, ?, ?, ?, 'VIRTUAL_MARKET_PRICE', ?)
                """,
                candidate.id(),
                candidate.userKey(),
                candidate.symbol(),
                candidate.side(),
                remainingQuantity,
                executionPrice,
                executedAt
        );

        jdbcTemplate.update(
                """
                update stock_order
                set status = 'FILLED',
                    filled_quantity = quantity,
                    average_fill_price = ?,
                    reserved_cash = 0,
                    updated_at = ?
                where id = ?
                """,
                averageFillPrice,
                executedAt,
                candidate.id()
        );
    }

    private BigDecimal calculateAverageFillPrice(OrderCandidate candidate, long fillQuantity, BigDecimal executionPrice) {
        BigDecimal previousAverage = candidate.averageFillPrice() == null ? BigDecimal.ZERO : candidate.averageFillPrice();
        BigDecimal previousAmount = previousAverage.multiply(BigDecimal.valueOf(candidate.filledQuantity()));
        BigDecimal nextAmount = previousAmount.add(executionPrice.multiply(BigDecimal.valueOf(fillQuantity)));
        long nextFilledQuantity = candidate.filledQuantity() + fillQuantity;
        return nextAmount.divide(BigDecimal.valueOf(nextFilledQuantity), 2, RoundingMode.HALF_UP);
    }

    private boolean executeBuy(OrderCandidate candidate, long quantity, BigDecimal executionPrice) {
        BigDecimal reservedCost = candidate.reservedCash();
        BigDecimal actualCost = executionPrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal release = reservedCost.subtract(actualCost).max(BigDecimal.ZERO);
        BigDecimal shortfall = actualCost.subtract(reservedCost).max(BigDecimal.ZERO);
        if (shortfall.compareTo(BigDecimal.ZERO) > 0 && !chargeShortfall(candidate, shortfall)) {
            rejectBuyOrder(candidate);
            return false;
        }
        if (release.compareTo(BigDecimal.ZERO) > 0) {
            jdbcTemplate.update(
                    "update stock_account set cash_balance = cash_balance + ?, updated_at = ? where user_key = ?",
                    release,
                    LocalDateTime.now(),
                    candidate.userKey()
            );
        }
        upsertHolding(candidate.userKey(), candidate.symbol(), quantity, executionPrice);
        return true;
    }

    private boolean chargeShortfall(OrderCandidate candidate, BigDecimal shortfall) {
        int updatedRows = jdbcTemplate.update(
                """
                update stock_account
                set cash_balance = cash_balance - ?,
                    updated_at = ?
                where user_key = ?
                  and cash_balance >= ?
                """,
                shortfall,
                LocalDateTime.now(),
                candidate.userKey(),
                shortfall
        );
        return updatedRows > 0;
    }

    private void rejectBuyOrder(OrderCandidate candidate) {
        LocalDateTime rejectedAt = LocalDateTime.now();
        if (candidate.reservedCash().compareTo(BigDecimal.ZERO) > 0) {
            jdbcTemplate.update(
                    "update stock_account set cash_balance = cash_balance + ?, updated_at = ? where user_key = ?",
                    candidate.reservedCash(),
                    rejectedAt,
                    candidate.userKey()
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
                candidate.id()
        );
    }

    private boolean executeSell(OrderCandidate candidate, long quantity, BigDecimal executionPrice) {
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
                LocalDateTime.now(),
                candidate.userKey(),
                candidate.symbol(),
                quantity,
                quantity
        );
        if (updatedRows == 0) {
            rejectSellOrder(candidate);
            return false;
        }
        jdbcTemplate.update(
                "update stock_account set cash_balance = cash_balance + ?, updated_at = ? where user_key = ?",
                proceeds,
                LocalDateTime.now(),
                candidate.userKey()
        );
        jdbcTemplate.update(
                "delete from stock_holding where user_key = ? and symbol = ? and quantity <= 0 and reserved_quantity <= 0",
                candidate.userKey(),
                candidate.symbol()
        );
        return true;
    }

    private void rejectSellOrder(OrderCandidate candidate) {
        releaseReservedSellQuantity(candidate.userKey(), candidate.symbol(), candidate.quantity() - candidate.filledQuantity());
        jdbcTemplate.update(
                """
                update stock_order
                set status = 'REJECTED',
                    updated_at = ?
                where id = ?
                """,
                LocalDateTime.now(),
                candidate.id()
        );
    }

    private void releaseReservedSellQuantity(String userKey, String symbol, long quantity) {
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
                LocalDateTime.now(),
                userKey,
                symbol
        );
    }

    private void upsertHolding(String userKey, String symbol, long quantity, BigDecimal executionPrice) {
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
                    LocalDateTime.now()
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
                LocalDateTime.now(),
                holding.id()
        );
    }

    private OrderCandidate mapCandidate(ResultSet rs) throws SQLException {
        return new OrderCandidate(
                rs.getLong("id"),
                rs.getString("user_key"),
                rs.getString("symbol"),
                rs.getString("side"),
                rs.getString("order_type"),
                rs.getBigDecimal("limit_price"),
                rs.getLong("quantity"),
                rs.getLong("filled_quantity"),
                rs.getBigDecimal("average_fill_price"),
                rs.getBigDecimal("reserved_cash"),
                rs.getBigDecimal("current_price")
        );
    }

    private record OrderCandidate(
            long id,
            String userKey,
            String symbol,
            String side,
            String orderType,
            BigDecimal limitPrice,
            long quantity,
            long filledQuantity,
            BigDecimal averageFillPrice,
            BigDecimal reservedCash,
            BigDecimal currentPrice
    ) {
    }

    private record HoldingRow(long id, long quantity, BigDecimal averagePrice) {
    }
}
