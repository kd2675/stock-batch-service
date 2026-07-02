package stock.batch.service.batch.execution.writer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ExecutionHoldingJdbcSupport {

    private final JdbcTemplate jdbcTemplate;

    public void deleteEmptyHolding(long accountId, String symbol) {
        jdbcTemplate.update(
                "delete from stock_holding where account_id = ? and symbol = ? and quantity <= 0 and reserved_quantity <= 0",
                accountId,
                symbol
        );
    }

    public void upsertHolding(
            long accountId,
            String symbol,
            long quantity,
            BigDecimal costAmount,
            LocalDateTime updatedAt
    ) {
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
                updatedAt,
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
                    updatedAt
            );
        }
    }
}
