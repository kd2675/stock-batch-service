package stock.batch.service.batch.marketclose.writer;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.marketclose.model.MarketCloseOrderRow;

@Component
@RequiredArgsConstructor
public class MarketCloseRolloverWriter {

    private final JdbcTemplate jdbcTemplate;

    public long createCloseRun(String symbol, LocalDate businessDate, LocalDateTime closedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    insert into stock_market_close_run(
                        symbol, business_date, closed_at, status,
                        cancelled_order_count, holding_snapshot_count, price_rollover_count,
                        created_at, completed_at
                    )
                    values (?, ?, ?, 'STARTED', 0, 0, 0, ?, null)
                    """,
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, symbol);
            ps.setObject(2, businessDate);
            ps.setObject(3, closedAt);
            ps.setObject(4, closedAt);
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Market close run id was not generated");
        }
        return key.longValue();
    }

    public List<MarketCloseOrderRow> findOpenOrderBookOrdersForUpdate(String symbol) {
        return jdbcTemplate.query(
                """
                select id,
                       account_id,
                       symbol,
                       side,
                       quantity - filled_quantity as remaining_quantity,
                       reserved_cash
                  from stock_order
                 where market_type = 'ORDER_BOOK'
                   and status in ('PENDING', 'PARTIALLY_FILLED')
                   and (? is null or symbol = ?)
                 order by symbol asc, created_at asc, id asc
                 for update
                """,
                (rs, rowNum) -> new MarketCloseOrderRow(
                        rs.getLong("id"),
                        rs.getLong("account_id"),
                        rs.getString("symbol"),
                        rs.getString("side"),
                        rs.getLong("remaining_quantity"),
                        rs.getBigDecimal("reserved_cash")
                ),
                symbol,
                symbol
        );
    }

    public void creditCash(long accountId, BigDecimal cashAmount, LocalDateTime updatedAt) {
        jdbcTemplate.update(
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

    public int rolloverClosingPrices(String symbol) {
        return jdbcTemplate.update(
                """
                update stock_price
                   set previous_close = current_price
                 where previous_close <> current_price
                   and (? is null or symbol = ?)
                """,
                symbol,
                symbol
        );
    }

    public int snapshotHoldings(long closeRunId, String symbol, LocalDateTime snapshotAt) {
        return jdbcTemplate.update(
                """
                insert into stock_holding_snapshot(
                    close_run_id, account_id, symbol, quantity, reserved_quantity, average_price, snapshot_at
                )
                select ?, account_id, symbol, quantity, reserved_quantity, average_price, ?
                  from stock_holding
                 where quantity > 0
                   and (? is null or symbol = ?)
                """,
                closeRunId,
                snapshotAt,
                symbol,
                symbol
        );
    }

    public void completeCloseRun(
            long closeRunId,
            int cancelledOrderCount,
            int holdingSnapshotCount,
            int priceRolloverCount,
            LocalDateTime completedAt
    ) {
        jdbcTemplate.update(
                """
                update stock_market_close_run
                   set status = 'COMPLETED',
                       cancelled_order_count = ?,
                       holding_snapshot_count = ?,
                       price_rollover_count = ?,
                       completed_at = ?
                 where id = ?
                """,
                cancelledOrderCount,
                holdingSnapshotCount,
                priceRolloverCount,
                completedAt,
                closeRunId
        );
    }
}
