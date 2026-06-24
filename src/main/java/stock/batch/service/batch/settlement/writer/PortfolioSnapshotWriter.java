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
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from portfolio_snapshot where account_id = ? and snapshot_date = ?",
                Integer.class,
                command.accountId(),
                today
        );
        if (count != null && count > 0) {
            jdbcTemplate.update(
                    """
                    update portfolio_snapshot
                    set total_asset = ?, cash_balance = ?, market_value = ?, return_rate = ?, created_at = ?
                    where account_id = ? and snapshot_date = ?
                    """,
                    command.totalAsset(),
                    command.cashBalance(),
                    command.marketValue(),
                    command.returnRate(),
                    LocalDateTime.now(),
                    command.accountId(),
                    today
            );
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
                LocalDateTime.now()
        );
    }
}
