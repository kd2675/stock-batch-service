package stock.batch.service.batch.settlement.writer;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.settlement.model.PortfolioSnapshotCommand;
import stock.batch.service.common.config.StockRuntimeIdentity;

@Component
public class PortfolioSnapshotWriter {

    private static final int MYSQL_UPSERT_ROW_CHUNK_SIZE = 500;
    private static final String MYSQL_UPSERT_SQL = """
            insert into portfolio_snapshot(
                close_cycle_id, close_run_id, account_id, snapshot_date,
                total_asset, cash_balance, market_value,
                holding_quantity, reserved_sell_quantity, holding_position_count,
                return_rate, input_hash, calculation_version, data_quality_status,
                source_build_version, created_at
            )
            values %s as incoming
            on duplicate key update
                close_cycle_id = incoming.close_cycle_id,
                close_run_id = incoming.close_run_id,
                total_asset = incoming.total_asset,
                cash_balance = incoming.cash_balance,
                market_value = incoming.market_value,
                holding_quantity = incoming.holding_quantity,
                reserved_sell_quantity = incoming.reserved_sell_quantity,
                holding_position_count = incoming.holding_position_count,
                return_rate = incoming.return_rate,
                input_hash = incoming.input_hash,
                calculation_version = incoming.calculation_version,
                data_quality_status = incoming.data_quality_status,
                source_build_version = incoming.source_build_version,
                created_at = incoming.created_at
            """;
    private static final String H2_MERGE_SQL = """
            merge into portfolio_snapshot(
                close_cycle_id, close_run_id, account_id, snapshot_date,
                total_asset, cash_balance, market_value,
                holding_quantity, reserved_sell_quantity, holding_position_count,
                return_rate, input_hash, calculation_version, data_quality_status,
                source_build_version, created_at
            ) key(account_id, snapshot_date)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final String sourceBuildVersion;
    private final boolean mysql;

    public PortfolioSnapshotWriter(
            JdbcTemplate jdbcTemplate,
            StockRuntimeIdentity runtimeIdentity
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.sourceBuildVersion = normalizeVersion(runtimeIdentity.buildVersion());
        String productName = jdbcTemplate.execute(
                (ConnectionCallback<String>) connection -> databaseProductName(connection)
        );
        this.mysql = productName != null && productName.toLowerCase(Locale.ROOT).contains("mysql");
    }

    public void write(
            List<? extends PortfolioSnapshotCommand> commands,
            LocalDate snapshotDate,
            LocalDateTime snapshotAt
    ) {
        if (commands.isEmpty()) {
            return;
        }
        if (mysql) {
            writeMysql(commands, snapshotDate, snapshotAt);
            return;
        }
        jdbcTemplate.batchUpdate(
                H2_MERGE_SQL,
                commands.stream()
                        .map(command -> parameters(command, snapshotDate, snapshotAt))
                        .toList()
        );
    }

    private void writeMysql(
            List<? extends PortfolioSnapshotCommand> commands,
            LocalDate snapshotDate,
            LocalDateTime snapshotAt
    ) {
        for (int start = 0; start < commands.size(); start += MYSQL_UPSERT_ROW_CHUNK_SIZE) {
            int end = Math.min(commands.size(), start + MYSQL_UPSERT_ROW_CHUNK_SIZE);
            List<? extends PortfolioSnapshotCommand> chunk = commands.subList(start, end);
            String values = String.join(",", Collections.nCopies(
                    chunk.size(),
                    "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            ));
            List<Object> parameters = new ArrayList<>(chunk.size() * 16);
            for (PortfolioSnapshotCommand command : chunk) {
                Collections.addAll(parameters, parameters(command, snapshotDate, snapshotAt));
            }
            jdbcTemplate.update(MYSQL_UPSERT_SQL.formatted(values), parameters.toArray());
        }
    }

    private Object[] parameters(
            PortfolioSnapshotCommand command,
            LocalDate snapshotDate,
            LocalDateTime snapshotAt
    ) {
        return new Object[]{
                command.closeCycleId(),
                command.closeRunId(),
                command.accountId(),
                snapshotDate,
                command.totalAsset(),
                command.cashBalance(),
                command.marketValue(),
                command.holdingQuantity(),
                command.reservedSellQuantity(),
                command.holdingPositionCount(),
                command.returnRate(),
                command.inputHash(),
                command.calculationVersion(),
                command.dataQualityStatus(),
                sourceBuildVersion,
                snapshotAt
        };
    }

    private String databaseProductName(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName();
    }

    private String normalizeVersion(String version) {
        if (version == null || version.isBlank()) {
            return "unknown";
        }
        return version.trim();
    }
}
