package stock.batch.service.batch.holdingcleanup.writer;

import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HoldingCleanupWriter {

    private final JdbcTemplate jdbcTemplate;

    public int deleteEmptyHoldingsBefore(LocalDateTime cutoff, int limit) {
        if (limit <= 0) {
            return 0;
        }
        return jdbcTemplate.update(
                """
                delete from stock_holding
                where quantity <= 0
                  and reserved_quantity <= 0
                  and updated_at < ?
                limit ?
                """,
                cutoff,
                limit
        );
    }
}
