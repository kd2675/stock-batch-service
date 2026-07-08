package stock.batch.service.batch.settlement.writer;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.settlement.model.PortfolioSnapshotCommand;
import stock.batch.service.simulation.SimulationClockService;

@Component
@RequiredArgsConstructor
public class PortfolioSnapshotWriter {

    private final JdbcTemplate jdbcTemplate;
    private final SimulationClockService simulationClockService;

    public void write(PortfolioSnapshotCommand command) {
        write(command, simulationClockService.currentDate(), simulationClockService.currentMarketDateTime());
    }

    public void write(PortfolioSnapshotCommand command, LocalDate snapshotDate, LocalDateTime snapshotAt) {
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
                snapshotAt,
                command.accountId(),
                snapshotDate
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
                snapshotDate,
                command.totalAsset(),
                command.cashBalance(),
                command.marketValue(),
                command.returnRate(),
                snapshotAt
        );
    }
}
