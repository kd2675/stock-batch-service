package stock.batch.service.database;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StockDdlContractTest {

    private static final List<String> INITIAL_CORPORATE_ACTION_SCOPE = List.of(
            "INITIAL_ISSUE",
            "PAID_IN_CAPITAL_INCREASE",
            "STOCK_SPLIT",
            "CASH_DIVIDEND",
            "BONUS_ISSUE",
            "STOCK_DIVIDEND",
            "DELISTING"
    );

    private static final List<String> DEFERRED_CORPORATE_ACTION_SCOPE = List.of(
            "SPECIAL_DIVIDEND",
            "CAPITAL_REDUCTION",
            "REVERSE_SPLIT",
            "RIGHTS_OFFERING",
            "MERGER",
            "SPIN_OFF"
    );

    private static final List<String> REQUIRED_CORPORATE_ACTION_CONSTRAINTS = List.of(
            "chk_stock_corporate_action_type_valid",
            "chk_stock_corporate_action_status_valid",
            "chk_stock_corporate_action_share_quantity",
            "chk_stock_corporate_action_issue_price",
            "chk_stock_corporate_action_dividend_amount",
            "chk_stock_corporate_action_delisting_treatment",
            "chk_stock_corporate_action_base_price",
            "chk_stock_corporate_action_ex_rights_price",
            "chk_stock_corporate_action_paid_dates",
            "chk_stock_corporate_action_listing_dates",
            "chk_stock_corporate_action_split_from",
            "chk_stock_corporate_action_split_to",
            "chk_stock_corporate_action_issue_required",
            "chk_stock_corporate_action_paid_schedule_required",
            "chk_stock_corporate_action_split_required",
            "chk_stock_corporate_action_dividend_required",
            "chk_stock_corporate_action_free_share_required",
            "chk_stock_corporate_action_delisting_required",
            "chk_stock_corporate_action_field_scope",
            "chk_stock_corporate_action_initial_listed"
    );

    private static final List<String> CORPORATE_ACTION_DDL_RESOURCES = List.of(
            "db/ddl/stock_all.sql",
            "db/ddl/stock_h2.sql"
    );

    private static final List<String> DEFAULT_SEED_MARKERS = List.of(
            "INSERT INTO stock_instrument",
            "INSERT INTO stock_price",
            "INSERT INTO stock_virtual_market_config",
            "INSERT INTO stock_auto_participant",
            "MERGE INTO stock_virtual_market_config",
            "MERGE INTO stock_order_book_instrument",
            "MERGE INTO stock_auto_participant",
            "삼성전자",
            "'seed'",
            "stock-auto-001"
    );

    private static final List<String> BATCH_METADATA_TABLE_MARKERS = List.of(
            "BATCH_JOB_INSTANCE",
            "BATCH_JOB_EXECUTION",
            "BATCH_JOB_EXECUTION_PARAMS",
            "BATCH_STEP_EXECUTION",
            "BATCH_STEP_EXECUTION_CONTEXT",
            "BATCH_JOB_EXECUTION_CONTEXT",
            "BATCH_STEP_EXECUTION_SEQ",
            "BATCH_JOB_EXECUTION_SEQ",
            "BATCH_JOB_INSTANCE_SEQ"
    );

    private static final List<String> BATCH_METADATA_DDL_RESOURCES = List.of(
            "db/schema/batch-metadata-mysql.sql",
            "db/schema/batch-metadata-h2.sql"
    );

    private static final List<String> BATCH_OPERATION_TABLE_MARKERS = List.of(
            "stock_batch_job_control",
            "stock_batch_job_lock",
            "stock_batch_job_signal"
    );

    private static final List<String> SIMULATION_CLOCK_TABLE_MARKERS = List.of(
            "stock_simulation_clock"
    );

    private static final List<String> ADMIN_QUERY_INDEX_MARKERS = List.of(
            "idx_stock_account_status_id",
            "idx_stock_account_cash_flow_account_reason_creator_time",
            "idx_stock_account_cash_flow_time",
            "idx_stock_order_market_status_side",
            "idx_stock_order_market_status_account_time",
            "idx_stock_order_market_account_time",
            "idx_stock_order_market_created_status",
            "idx_stock_execution_time_account",
            "idx_stock_execution_source_account_time",
            "idx_stock_execution_source_time_account",
            "idx_stock_execution_source_symbol_time",
            "idx_stock_execution_source_time",
            "idx_stock_holding_symbol_account",
            "idx_stock_holding_empty_cleanup",
            "idx_stock_auto_participant_active",
            "idx_stock_auto_participant_profile_active",
            "idx_stock_auto_participant_symbol_lookup",
            "idx_stock_auto_order_schedule_due",
            "idx_stock_corporate_action_status_symbol"
    );

    private static final List<String> MARKET_CLOSE_SNAPSHOT_TABLE_MARKERS = List.of(
            "stock_market_close_run",
            "stock_holding_snapshot",
            "stock_order_book_daily_snapshot",
            "uk_stock_order_book_daily_snapshot_run_symbol",
            "holding_snapshot_run_id"
    );

    @Test
    void corporateActionDdlResources_matchInitialProjectScope() throws IOException {
        for (String resourcePath : CORPORATE_ACTION_DDL_RESOURCES) {
            String ddl = readDdlResource(resourcePath);

            assertThat(ddl).as(resourcePath).contains(INITIAL_CORPORATE_ACTION_SCOPE.toArray(String[]::new));
            assertThat(ddl).as(resourcePath).contains(REQUIRED_CORPORATE_ACTION_CONSTRAINTS.toArray(String[]::new));
            assertThat(ddl).as(resourcePath).doesNotContain(DEFERRED_CORPORATE_ACTION_SCOPE.toArray(String[]::new));
        }
    }

    @Test
    void ddlResources_createSchemaWithoutDefaultMarketSeed() throws IOException {
        for (String resourcePath : CORPORATE_ACTION_DDL_RESOURCES) {
            String ddl = readDdlResource(resourcePath);

            assertThat(ddl).as(resourcePath).doesNotContain(DEFAULT_SEED_MARKERS.toArray(String[]::new));
        }
    }

    @Test
    void batchMetadataDdlResources_defineSpringBatchSixJobRepositorySchema() throws IOException {
        for (String resourcePath : BATCH_METADATA_DDL_RESOURCES) {
            String ddl = readDdlResource(resourcePath);

            assertThat(ddl).as(resourcePath).contains(BATCH_METADATA_TABLE_MARKERS.toArray(String[]::new));
            assertThat(ddl).as(resourcePath).doesNotContain("BATCH_JOB_SEQ");
        }
    }

    @Test
    void batchMetadataMysqlDdl_selectsStockBatchMetadataSchema() throws IOException {
        String ddl = readDdlResource("db/schema/batch-metadata-mysql.sql");

        assertThat(firstExecutableSqlLine(ddl)).isEqualTo("CREATE SCHEMA IF NOT EXISTS STOCK_BATCH_METADATA;");
        assertThat(ddl).contains("USE STOCK_BATCH_METADATA;");
        assertThat(ddl).doesNotContain("USE STOCK_SERVICE;");
    }

    @Test
    void businessDdlResources_doNotDefineSpringBatchMetadataTables() throws IOException {
        for (String resourcePath : CORPORATE_ACTION_DDL_RESOURCES) {
            String ddl = readDdlResource(resourcePath);

            assertThat(ddl).as(resourcePath).doesNotContain(BATCH_METADATA_TABLE_MARKERS.toArray(String[]::new));
        }
    }

    @Test
    void batchMetadataDdlResources_doNotDefineStockBusinessOperationTables() throws IOException {
        for (String resourcePath : BATCH_METADATA_DDL_RESOURCES) {
            String ddl = readDdlResource(resourcePath);

            assertThat(ddl).as(resourcePath).doesNotContain(BATCH_OPERATION_TABLE_MARKERS.toArray(String[]::new));
        }
    }

    @Test
    void ddlResources_defineSharedBatchOperationTables() throws IOException {
        for (String resourcePath : CORPORATE_ACTION_DDL_RESOURCES) {
            String ddl = readDdlResource(resourcePath);

            assertThat(ddl).as(resourcePath).contains(BATCH_OPERATION_TABLE_MARKERS.toArray(String[]::new));
            assertThat(ddl).as(resourcePath).contains(SIMULATION_CLOCK_TABLE_MARKERS.toArray(String[]::new));
        }
    }

    @Test
    void ddlResources_defineMarketCloseHoldingSnapshotTables() throws IOException {
        for (String resourcePath : CORPORATE_ACTION_DDL_RESOURCES) {
            String ddl = readDdlResource(resourcePath);

            assertThat(ddl).as(resourcePath).contains(MARKET_CLOSE_SNAPSHOT_TABLE_MARKERS.toArray(String[]::new));
        }
    }

    @Test
    void ddlResources_defineAdminQueryPerformanceIndexes() throws IOException {
        for (String resourcePath : CORPORATE_ACTION_DDL_RESOURCES) {
            String ddl = readDdlResource(resourcePath);

            assertThat(ddl).as(resourcePath).contains(ADMIN_QUERY_INDEX_MARKERS.toArray(String[]::new));
        }
    }

    @Test
    void stockH2Ddl_definesBatchOperationTablesWithRequiredColumnsAndConstraints() throws IOException {
        String h2Ddl = readDdlResource("db/ddl/stock_h2.sql");

        assertThat(extractCreateTableBlock(h2Ddl, "stock_batch_job_control"))
                .contains(
                        "job_name VARCHAR(100) NOT NULL PRIMARY KEY",
                        "runtime_enabled BOOLEAN NOT NULL DEFAULT TRUE",
                        "scheduler_configured BOOLEAN NOT NULL DEFAULT TRUE",
                        "updated_by VARCHAR(64)",
                        "created_at TIMESTAMP NOT NULL",
                        "updated_at TIMESTAMP NOT NULL",
                        "CONSTRAINT chk_stock_batch_job_control_name CHECK (job_name <> '')"
                );
        assertThat(extractCreateTableBlock(h2Ddl, "stock_batch_job_lock"))
                .contains(
                        "job_name VARCHAR(100) NOT NULL PRIMARY KEY",
                        "lock_owner VARCHAR(128) NOT NULL",
                        "locked_until TIMESTAMP NOT NULL",
                        "created_at TIMESTAMP NOT NULL",
                        "updated_at TIMESTAMP NOT NULL",
                        "CONSTRAINT chk_stock_batch_job_lock_name CHECK (job_name <> '')",
                        "CONSTRAINT chk_stock_batch_job_lock_owner CHECK (lock_owner <> '')"
                );
        assertThat(extractCreateTableBlock(h2Ddl, "stock_batch_job_signal"))
                .contains(
                        "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY",
                        "signal_type VARCHAR(60) NOT NULL",
                        "job_name VARCHAR(100) NOT NULL",
                        "execution_mode VARCHAR(120) NOT NULL",
                        "status VARCHAR(20) NOT NULL DEFAULT 'PENDING'",
                        "requested_at TIMESTAMP NOT NULL",
                        "CONSTRAINT chk_stock_batch_job_signal_type CHECK (signal_type <> '')",
                        "CONSTRAINT chk_stock_batch_job_signal_job CHECK (job_name <> '')",
                        "CONSTRAINT chk_stock_batch_job_signal_mode CHECK (execution_mode <> '')",
                        "CONSTRAINT chk_stock_batch_job_signal_status CHECK"
                );
        assertThat(extractCreateTableBlock(h2Ddl, "stock_simulation_clock"))
                .contains(
                        "clock_id VARCHAR(40) NOT NULL PRIMARY KEY",
                        "base_simulation_date DATE NOT NULL",
                        "real_seconds_per_simulation_day INT NOT NULL",
                        "accumulated_real_seconds BIGINT NOT NULL DEFAULT 0",
                        "running BOOLEAN NOT NULL DEFAULT FALSE",
                        "CONSTRAINT chk_stock_simulation_clock_id CHECK (clock_id <> '')",
                        "CONSTRAINT chk_stock_simulation_clock_day_seconds CHECK (real_seconds_per_simulation_day > 0)",
                        "CONSTRAINT chk_stock_simulation_clock_accumulated CHECK (accumulated_real_seconds >= 0)"
                );
    }

    @Test
    void alterDdlFiles_selectStockServiceSchemaBeforeChanges() throws IOException {
        List<Path> alterFiles = listAlterDdlFiles();

        assertThat(alterFiles).isNotEmpty();
        for (Path alterFile : alterFiles) {
            String ddl = Files.readString(alterFile, StandardCharsets.UTF_8);

            assertThat(firstExecutableSqlLine(ddl)).as(alterFile.toString()).isEqualTo("USE STOCK_SERVICE;");
        }
    }

    @Test
    void orderBookExpiryAlterDdl_isIdempotentAndSyncedWithBackServiceCopy() throws IOException {
        String batchDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_order_book_expiry_index_alter.sql"),
                StandardCharsets.UTF_8
        );
        String backDdl = Files.readString(
                Path.of("../stock-back-service/src/main/resources/db/ddl/stock_order_book_expiry_index_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(batchDdl)
                .contains(
                        "CREATE TABLE IF NOT EXISTS stock_auto_participant_order_schedule",
                        "information_schema.statistics",
                        "idx_stock_auto_order_schedule_due",
                        "idx_stock_auto_order_schedule_profile_due",
                        "idx_stock_order_order_book_expiry",
                        "idx_stock_order_status_symbol",
                        "PREPARE stock_auto_order_schedule_due_index_stmt",
                        "PREPARE stock_auto_order_schedule_profile_due_index_stmt",
                        "PREPARE stock_order_book_expiry_index_stmt",
                        "PREPARE stock_order_status_symbol_index_stmt",
                        "DROP INDEX idx_stock_order_status_symbol"
                );
        assertThat(batchDdl).doesNotContain("ALTER TABLE stock_order\n  ADD INDEX");
    }

    @Test
    void stockOrderDdl_doesNotCreateRedundantStatusSymbolIndex() throws IOException {
        String mysqlDdl = readDdlResource("db/ddl/stock_all.sql");
        String h2Ddl = readDdlResource("db/ddl/stock_h2.sql");

        assertThat(extractCreateTableBlock(mysqlDdl, "stock_order"))
                .doesNotContain("idx_stock_order_status_symbol");
        assertThat(h2Ddl).doesNotContain("idx_stock_order_status_symbol ON stock_order(status, symbol)");
    }

    private String readDdlResource(String resourcePath) throws IOException {
        try (var inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertThat(inputStream).as(resourcePath + " resource").isNotNull();
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private List<Path> listAlterDdlFiles() throws IOException {
        try (var paths = Files.list(Path.of("src/main/resources/db/ddl"))) {
            return paths
                    .filter(path -> path.getFileName().toString().endsWith("_alter.sql"))
                    .sorted()
                    .toList();
        }
    }

    private String firstExecutableSqlLine(String ddl) {
        return ddl.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .filter(line -> !line.startsWith("--"))
                .findFirst()
                .orElse("");
    }

    private String extractCreateTableBlock(String ddl, String tableName) {
        String marker = "CREATE TABLE IF NOT EXISTS " + tableName + " (";
        int startIndex = ddl.indexOf(marker);
        assertThat(startIndex).as(tableName + " create table marker").isGreaterThanOrEqualTo(0);

        int endIndex = ddl.indexOf(";", startIndex);
        assertThat(endIndex).as(tableName + " create table terminator").isGreaterThan(startIndex);

        return ddl.substring(startIndex, endIndex + 1);
    }

    private String normalizeSqlBlock(String sql) {
        return sql.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }
}
