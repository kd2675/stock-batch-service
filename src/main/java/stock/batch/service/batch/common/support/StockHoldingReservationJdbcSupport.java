package stock.batch.service.batch.common.support;

import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StockHoldingReservationJdbcSupport {

    private final JdbcTemplate jdbcTemplate;

    public void releaseReservedSellQuantity(
            long accountId,
            String symbol,
            long quantity,
            LocalDateTime updatedAt
    ) {
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
}
