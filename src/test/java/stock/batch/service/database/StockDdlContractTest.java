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
            "chk_stock_corporate_action_subscription_dates",
            "chk_stock_corporate_action_paid_date_order",
            "chk_stock_corporate_action_split_from",
            "chk_stock_corporate_action_split_to",
            "chk_stock_corporate_action_issue_required",
            "chk_stock_corporate_action_paid_schedule_required",
            "chk_stock_corporate_action_split_required",
            "chk_stock_corporate_action_dividend_required",
            "chk_stock_corporate_action_free_share_required",
            "chk_stock_corporate_action_delisting_required",
            "chk_stock_corporate_action_field_scope",
            "chk_stock_corporate_action_initial_listed",
            "chk_stock_corporate_action_entitlement_subscribed_share_limit"
    );

    private static final List<String> AUTO_PARTICIPANT_PROFILE_TYPES = List.of(
            "NEWS_REACTIVE", "MOMENTUM_FOLLOWER", "CONTRARIAN", "LOSS_AVERSE",
            "OVERCONFIDENT", "HERD_FOLLOWER", "MARKET_MAKER", "NOISE_TRADER",
            "VALUE_ANCHOR", "SCALPER", "DAY_TRADER", "SWING_TRADER",
            "LONG_TERM_HOLDER", "PAYDAY_ACCUMULATOR", "DIVIDEND_REINVESTOR",
            "LIMIT_DOWN_TRAPPED", "AVERAGE_DOWN_BUYER", "STOP_LOSS_TRADER",
            "FOMO_BUYER", "PANIC_SELLER", "DIP_BUYER", "PROFIT_LOCKER",
            "LIQUIDITY_AVOIDANT", "CASH_DEFENSIVE", "WHALE", "SMALL_DIVERSIFIER", "OBSERVER"
    );

    private static final Path CANONICAL_MYSQL_DDL = Path.of(
            "../stock-back-service/src/main/resources/db/ddl/stock_all.sql"
    );
    private static final Path BATCH_MYSQL_DDL_DUPLICATE = Path.of(
            "src/main/resources/db/ddl/stock_all.sql"
    );
    private static final List<Path> BUSINESS_DDL_FILES = List.of(
            CANONICAL_MYSQL_DDL,
            Path.of("src/main/resources/db/ddl/stock_h2.sql")
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
            "idx_stock_corporate_action_created",
            "idx_stock_corporate_action_type_created",
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
        for (Path ddlFile : BUSINESS_DDL_FILES) {
            String ddl = Files.readString(ddlFile, StandardCharsets.UTF_8);

            assertThat(ddl).as(ddlFile.toString()).contains(INITIAL_CORPORATE_ACTION_SCOPE.toArray(String[]::new));
            assertThat(ddl).as(ddlFile.toString()).contains(REQUIRED_CORPORATE_ACTION_CONSTRAINTS.toArray(String[]::new));
            assertThat(ddl).as(ddlFile.toString()).doesNotContain(DEFERRED_CORPORATE_ACTION_SCOPE.toArray(String[]::new));
        }
    }

    @Test
    void ddlResources_createSchemaWithoutDefaultMarketSeed() throws IOException {
        for (Path ddlFile : BUSINESS_DDL_FILES) {
            String ddl = Files.readString(ddlFile, StandardCharsets.UTF_8);

            assertThat(ddl).as(ddlFile.toString()).doesNotContain(DEFAULT_SEED_MARKERS.toArray(String[]::new));
        }
    }

    @Test
    void mysqlBusinessDdl_hasSingleCanonicalBackServiceSource() {
        assertThat(CANONICAL_MYSQL_DDL).isRegularFile();
        assertThat(BATCH_MYSQL_DDL_DUPLICATE).doesNotExist();
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
        for (Path ddlFile : BUSINESS_DDL_FILES) {
            String ddl = Files.readString(ddlFile, StandardCharsets.UTF_8);

            assertThat(ddl).as(ddlFile.toString()).doesNotContain(BATCH_METADATA_TABLE_MARKERS.toArray(String[]::new));
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
        for (Path ddlFile : BUSINESS_DDL_FILES) {
            String ddl = Files.readString(ddlFile, StandardCharsets.UTF_8);

            assertThat(ddl).as(ddlFile.toString()).contains(BATCH_OPERATION_TABLE_MARKERS.toArray(String[]::new));
            assertThat(ddl).as(ddlFile.toString()).contains(SIMULATION_CLOCK_TABLE_MARKERS.toArray(String[]::new));
        }
    }

    @Test
    void ddlResources_defineMarketCloseHoldingSnapshotTables() throws IOException {
        for (Path ddlFile : BUSINESS_DDL_FILES) {
            String ddl = Files.readString(ddlFile, StandardCharsets.UTF_8);

            assertThat(ddl).as(ddlFile.toString()).contains(MARKET_CLOSE_SNAPSHOT_TABLE_MARKERS.toArray(String[]::new));
        }
    }

    @Test
    void ddlResources_defineAdminQueryPerformanceIndexes() throws IOException {
        for (Path ddlFile : BUSINESS_DDL_FILES) {
            String ddl = Files.readString(ddlFile, StandardCharsets.UTF_8);

            assertThat(ddl).as(ddlFile.toString()).contains(ADMIN_QUERY_INDEX_MARKERS.toArray(String[]::new));
        }
    }

    @Test
    void stockH2Ddl_matchesCanonicalSharedOrderAndExecutionValues() throws IOException {
        String mysqlDdl = Files.readString(CANONICAL_MYSQL_DDL, StandardCharsets.UTF_8);
        String h2Ddl = readDdlResource("db/ddl/stock_h2.sql");
        List<String> sharedConstraints = List.of(
                "chk_stock_order_market_type_valid CHECK (CASE `market_type` WHEN 'VIRTUAL_PRICE' THEN 1 WHEN 'ORDER_BOOK' THEN 1 ELSE 0 END = 1)",
                "chk_stock_execution_source_valid CHECK (CASE `source` WHEN 'VIRTUAL_MARKET_PRICE' THEN 1 WHEN 'INTERNAL_ORDER_BOOK' THEN 1 ELSE 0 END = 1)"
        );

        assertThat(mysqlDdl).contains(sharedConstraints.toArray(String[]::new));
        assertThat(h2Ddl).contains(sharedConstraints.toArray(String[]::new));
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
    void stockH2Ddl_definesInstitutionalListingAutoPolicy() throws IOException {
        String h2Ddl = readDdlResource("db/ddl/stock_h2.sql");

        assertThat(extractCreateTableBlock(h2Ddl, "stock_listing_auto_account_config"))
                .contains(
                        "target_buy_quantity BIGINT NOT NULL",
                        "target_sell_quantity BIGINT NOT NULL",
                        "target_holding_quantity BIGINT NOT NULL",
                        "inventory_band_quantity BIGINT NOT NULL",
                        "buy_price_offset_direction VARCHAR(10) NOT NULL",
                        "sell_price_offset_direction VARCHAR(10) NOT NULL",
                        "WHEN 'TWO_SIDED' THEN 1",
                        "chk_stock_listing_auto_account_target_buy",
                        "chk_stock_listing_auto_account_target_sell",
                        "chk_stock_listing_auto_account_target_holding",
                        "chk_stock_listing_auto_account_inventory_band"
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
    void listingAutoInstitutionPolicyAlterDdl_isSyncedWithBackServiceCopy() throws IOException {
        String batchDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_listing_auto_institution_policy_alter.sql"),
                StandardCharsets.UTF_8
        );
        String backDdl = Files.readString(
                Path.of("../stock-back-service/src/main/resources/db/ddl/stock_listing_auto_institution_policy_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
    }

    @Test
    void listingAutoInventoryBandAlterDdl_isIdempotentAndSyncedWithBackServiceCopy() throws IOException {
        String batchDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_listing_auto_inventory_band_alter.sql"),
                StandardCharsets.UTF_8
        );
        String backDdl = Files.readString(
                Path.of("../stock-back-service/src/main/resources/db/ddl/stock_listing_auto_inventory_band_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(batchDdl).contains(
                "USE STOCK_SERVICE;",
                "information_schema.columns",
                "inventory_band_quantity",
                "chk_stock_listing_auto_account_inventory_band"
        );
    }

    @Test
    void capitalIncreaseAlterDdl_guardsLegacyRowsAndIsSyncedWithBackServiceCopy() throws IOException {
        String batchDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_capital_increase_subscription_alter.sql"),
                StandardCharsets.UTF_8
        );
        String backDdl = Files.readString(
                Path.of("../stock-back-service/src/main/resources/db/ddl/stock_capital_increase_subscription_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(batchDdl).contains(
                "stock_legacy_paid_in_unsafe_count",
                "status NOT IN ('ANNOUNCED', 'LISTED')",
                "stock_migration_required_legacy_paid_in_entitlements",
                "DATE_ADD(ex_rights_date, INTERVAL 1 DAY)",
                "DATE_SUB(payment_date, INTERVAL 1 DAY)",
                "chk_stock_corporate_action_paid_date_order",
                "chk_stock_corporate_action_entitlement_subscribed_share_limit"
        ).doesNotContain("SIGNAL SQLSTATE");
    }

    @Test
    void capitalIncreaseHardeningAlterDdl_isSeparateAndSyncedWithBackServiceCopy() throws IOException {
        String batchDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_capital_increase_contract_hardening_alter.sql"),
                StandardCharsets.UTF_8
        );
        String backDdl = Files.readString(
                Path.of("../stock-back-service/src/main/resources/db/ddl/stock_capital_increase_contract_hardening_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(batchDdl).contains(
                "information_schema.statistics",
                "idx_stock_corporate_action_created",
                "idx_stock_corporate_action_type_created",
                "PREPARE stock_corporate_action_created_index_stmt",
                "PREPARE stock_corporate_action_type_created_index_stmt",
                "stock_paid_in_invalid_schedule_count",
                "payment_date <= subscription_end_date",
                "stock_migration_required_paid_in_schedule",
                "stock_auto_event_profile_invalid_type_count",
                "stock_migration_required_event_profile_type",
                "chk_stock_auto_event_profile_type",
                "chk_stock_corporate_action_entitlement_subscribed_share_limit"
        ).doesNotContain("SIGNAL SQLSTATE");
    }

    @Test
    void schemaContractAlignmentAlterDdl_isGuardedAndSyncedWithBackServiceCopy() throws IOException {
        String batchDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_schema_contract_alignment_alter.sql"),
                StandardCharsets.UTF_8
        );
        String backDdl = Files.readString(
                Path.of("../stock-back-service/src/main/resources/db/ddl/stock_schema_contract_alignment_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(batchDdl).contains(
                "stock_migration_required_schema_contract_alignment",
                "ALTER COLUMN market_enabled DROP DEFAULT",
                "ALTER COLUMN regime_phase DROP DEFAULT",
                "chk_stock_order_book_daily_snapshot_flow",
                "chk_stock_order_book_daily_snapshot_open_order",
                "chk_stock_order_book_daily_snapshot_holding",
                "chk_stock_order_book_daily_regime_phase",
                "chk_stock_order_book_daily_regime_execution_aggression",
                "action_type NOT IN ('INITIAL_ISSUE', 'PAID_IN_CAPITAL_INCREASE')"
        ).doesNotContain("ADDITIONAL_ISSUE", "SIGNAL SQLSTATE");
    }

    @Test
    void priceTickLatestLookupAlterDdl_isIdempotentAndSyncedWithBackServiceCopy() throws IOException {
        String batchDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_price_tick_latest_lookup_alter.sql"),
                StandardCharsets.UTF_8
        );
        String backDdl = Files.readString(
                Path.of("../stock-back-service/src/main/resources/db/ddl/stock_price_tick_latest_lookup_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(batchDdl).contains(
                "USE STOCK_SERVICE",
                "information_schema.statistics",
                "ADD INDEX idx_stock_price_tick_symbol_time_id (symbol, price_time, id)",
                "DROP INDEX idx_stock_price_tick_symbol_time"
        );
    }

    @Test
    void activityLatestLookupAlterDdl_isIdempotentAndSyncedWithBackServiceCopy() throws IOException {
        String batchDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_activity_latest_lookup_alter.sql"),
                StandardCharsets.UTF_8
        );
        String backDdl = Files.readString(
                Path.of("../stock-back-service/src/main/resources/db/ddl/stock_activity_latest_lookup_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(batchDdl).contains(
                "USE STOCK_SERVICE",
                "information_schema.statistics",
                "ADD INDEX idx_stock_order_account_market_created (account_id, market_type, created_at)",
                "ADD INDEX idx_stock_execution_account_source_time (account_id, source, executed_at)",
                "ADD INDEX idx_stock_execution_candle (source, symbol, side, executed_at, id, price, quantity, gross_amount)",
                "SELECT 1"
        );
    }

    @Test
    void eventProfileDdlResources_allowOnlyKnownAutoParticipantProfiles() throws IOException {
        List<String> ddlResources = List.of(
                Files.readString(CANONICAL_MYSQL_DDL, StandardCharsets.UTF_8),
                readDdlResource("db/ddl/stock_h2.sql"),
                readDdlResource("db/ddl/stock_auto_participant_event_profile_config_alter.sql"),
                readDdlResource("db/ddl/stock_capital_increase_contract_hardening_alter.sql")
        );

        for (String ddl : ddlResources) {
            String eventProfileBlock = ddl.substring(ddl.indexOf("chk_stock_auto_event_profile_type"));
            assertThat(eventProfileBlock).contains(AUTO_PARTICIPANT_PROFILE_TYPES.toArray(String[]::new));
        }
    }

    @Test
    void stockOrderDdl_doesNotCreateRedundantStatusSymbolIndex() throws IOException {
        String mysqlDdl = Files.readString(CANONICAL_MYSQL_DDL, StandardCharsets.UTF_8);
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
