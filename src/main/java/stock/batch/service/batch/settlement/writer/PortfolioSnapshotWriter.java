package stock.batch.service.batch.settlement.writer;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.settlement.model.PortfolioSnapshotCommand;

@Component
@RequiredArgsConstructor
public class PortfolioSnapshotWriter {

    private final JdbcTemplate jdbcTemplate;

    public void write(PortfolioSnapshotCommand command) {
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        int updatedRows = jdbcTemplate.update(
                """
                update portfolio_snapshot
                set total_asset = ?, cash_balance = ?, market_value = ?, return_rate = ?, created_at = ?
                where account_id = ? and snapshot_date = ?
                """,
                command.totalAsset(),
                command.cashBalance(),
                command.marketValue(),
                command.returnRate(),
                now,
                command.accountId(),
                today
        );
        if (updatedRows > 0) {
            return;
        }

        jdbcTemplate.update(
                """
                insert into portfolio_snapshot(account_id, snapshot_date, total_asset, cash_balance, market_value, return_rate, created_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """,
                command.accountId(),
                today,
                command.totalAsset(),
                command.cashBalance(),
                command.marketValue(),
                command.returnRate(),
                now
        );
    }
}
