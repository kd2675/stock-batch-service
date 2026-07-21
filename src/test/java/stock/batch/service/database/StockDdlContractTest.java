package stock.batch.service.database;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

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
            "STOCK_BATCH_JOB_METADATA_ARCHIVE",
            "BATCH_STEP_EXECUTION_SEQ",
            "BATCH_JOB_EXECUTION_SEQ",
            "BATCH_JOB_INSTANCE_SEQ"
    );

    private static final List<String> BATCH_METADATA_DDL_RESOURCES = List.of(
            "db/schema/batch-metadata-mysql.sql",
            "db/schema/batch-metadata-h2.sql"
    );

    private static final List<String> EOD_ALTER_DDL_FILES = List.of(
            "stock_eod_session_fence_alter.sql",
            "stock_eod_cycle_alter.sql",
            "stock_eod_immutable_snapshot_alter.sql",
            "stock_portfolio_snapshot_post_close_cash_data_fix.sql",
            "stock_eod_report_participant_snapshot_alter.sql",
            "stock_batch_job_signal_lease_alter.sql",
            "stock_corporate_action_processing_alter.sql",
            "stock_corporate_action_chunking_alter.sql",
            "stock_execution_daily_account_last_executed_at_alter.sql",
            "stock_execution_profit_summary_alter.sql",
            "stock_eod_volume_indexes_alter.sql",
            "stock_auto_participant_cash_flow_run_alter.sql"
    );

    private static final Pattern HOT_LEDGER_INDEX_DDL = Pattern.compile(
            "(?is)\\bALTER\\s+TABLE\\s+stock_(?:order|execution)\\b"
                    + "|\\bCREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+\\S+\\s+ON\\s+stock_(?:order|execution)\\b"
    );

    private static final List<String> BATCH_OPERATION_TABLE_MARKERS = List.of(
            "stock_batch_job_control",
            "stock_batch_job_lock",
            "stock_batch_job_signal"
    );

    private static final List<String> SIMULATION_CLOCK_TABLE_MARKERS = List.of(
            "stock_simulation_clock",
            "stock_market_business_state",
            "stock_market_session_fence",
            "idx_stock_market_session_fence_state",
            "chk_stock_market_session_fence_market_type",
            "chk_stock_market_session_fence_state",
            "chk_stock_market_session_fence_epoch"
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
            "stock_post_close_cycle",
            "stock_post_close_phase_attempt",
            "stock_post_close_cycle_metric",
            "released_buy_cash",
            "released_sell_quantity",
            "chk_stock_post_close_cycle_metric_releases",
            "next_retry_at",
            "uk_stock_post_close_cycle_scope",
            "uk_stock_post_close_phase_attempt",
            "idx_stock_post_close_cycle_scope_date_status",
            "idx_stock_post_close_cycle_scope_status_date",
            "idx_stock_post_close_phase_attempt_cycle_id",
            "idx_stock_post_close_cycle_metric_run",
            "stock_market_close_run",
            "stock_holding_snapshot",
            "stock_order_book_daily_snapshot",
            "stock_execution_daily_account_snapshot",
            "stock_execution_account_day_summary",
            "buy_gross_amount",
            "sell_gross_amount",
            "buy_net_amount",
            "sell_net_amount",
            "fee_amount",
            "tax_amount",
            "realized_profit",
            "stock_close_account_snapshot",
            "participant_category",
            "stock_close_price_snapshot",
            "stock_close_open_order_summary",
            "stock_close_open_order_snapshot",
            "open_price",
            "first_executed_at",
            "uk_stock_order_book_daily_snapshot_run_symbol",
            "holding_snapshot_run_id",
            "uk_stock_close_account_snapshot_cycle_account",
            "idx_stock_close_account_snapshot_cycle_target",
            "idx_stock_close_account_snapshot_cycle_reconciliation",
            "idx_stock_close_open_order_snapshot_cycle_release_order",
            "idx_stock_close_open_order_snapshot_cycle_stream",
            "source_order_status",
            "uk_stock_close_price_snapshot_cycle_symbol",
            "uk_stock_close_open_order_summary_cycle_symbol",
            "uk_stock_close_open_order_snapshot_cycle_order",
            "uk_portfolio_snapshot_cycle_account",
            "pending_subscription_asset",
            "chk_portfolio_snapshot_pending_subscription_non_negative",
            "chk_portfolio_snapshot_asset_composition",
            "holding_market_value",
            "input_hash",
            "calculation_version",
            "data_quality_status"
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
    void eodSessionFenceAlterDdl_isFailClosedAndSyncedWithBackServiceCopy() throws IOException {
        String batchDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_eod_session_fence_alter.sql"),
                StandardCharsets.UTF_8
        );
        String backDdl = Files.readString(
                Path.of("../stock-back-service/src/main/resources/db/ddl/stock_eod_session_fence_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(firstExecutableSqlLine(batchDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(batchDdl).contains(
                "CREATE TABLE IF NOT EXISTS stock_market_business_state",
                "CREATE TABLE IF NOT EXISTS stock_market_session_fence",
                "PRIMARY KEY (market_type, symbol)",
                "session_epoch BIGINT NOT NULL",
                "session_state VARCHAR(20) NOT NULL",
                "SET @stock_market_open_time = COALESCE(@stock_market_open_time, TIME('06:00:00'))",
                "source.accumulated_real_seconds",
                "source.last_started_at",
                "source.last_heartbeat_at",
                "TIMESTAMPDIFF(",
                "simulation_seconds_in_day",
                "'CLOSED'",
                "INSERT IGNORE INTO stock_market_session_fence"
        );
        assertThat(batchDdl).doesNotContain(
                "SELECT base_simulation_date\n                 FROM stock_simulation_clock"
        );
        assertThat(batchDdl).doesNotContain("symbol VARCHAR(20) NULL");
    }

    @Test
    void eodCycleAlterDdl_hasLogicalUniquenessAttemptHistoryAndSyncedBackCopy() throws IOException {
        String batchDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_eod_cycle_alter.sql"),
                StandardCharsets.UTF_8
        );
        String backDdl = Files.readString(
                Path.of("../stock-back-service/src/main/resources/db/ddl/stock_eod_cycle_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(firstExecutableSqlLine(batchDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(batchDdl).contains(
                "UNIQUE KEY uk_stock_post_close_cycle_scope (business_date, scope_type, scope_key)",
                "UNIQUE KEY uk_stock_post_close_phase_attempt (cycle_id, phase, attempt_no)",
                "KEY idx_stock_post_close_cycle_scope_date_status (scope_type, scope_key, business_date, status, id)",
                "KEY idx_stock_post_close_phase_attempt_cycle_id (cycle_id, id)",
                "next_retry_at DATETIME NULL",
                "ADD COLUMN next_retry_at DATETIME NULL AFTER lease_until",
                "Legacy close runs predate the immutable close-cycle input tables",
                "'FULL_MARKET'",
                "'ALL'",
                "symbol IS NULL",
                "ON DUPLICATE KEY UPDATE"
        );
        assertThat(batchDdl).doesNotContain("portfolio_snapshot");
    }

    @Test
    void eodImmutableSnapshotAlterDdl_freezesSettlementInputsAndMatchesBackCopy() throws IOException {
        String batchDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_eod_immutable_snapshot_alter.sql"),
                StandardCharsets.UTF_8
        );
        String backDdl = Files.readString(
                Path.of("../stock-back-service/src/main/resources/db/ddl/stock_eod_immutable_snapshot_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(firstExecutableSqlLine(batchDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(batchDdl).contains(
                "information_schema.columns",
                "information_schema.statistics",
                "information_schema.table_constraints",
                "PREPARE stock_eod_immutable_statement",
                "CREATE TABLE IF NOT EXISTS stock_close_account_snapshot",
                "participant_category VARCHAR(30) NOT NULL DEFAULT 'MANUAL_PARTICIPANT'",
                "chk_stock_close_account_snapshot_participant_category",
                "CREATE TABLE IF NOT EXISTS stock_post_close_cycle_metric",
                "released_buy_cash DECIMAL(19,2) NOT NULL DEFAULT 0.00",
                "released_sell_quantity BIGINT NOT NULL DEFAULT 0",
                "chk_stock_post_close_cycle_metric_releases",
                "CREATE TABLE IF NOT EXISTS stock_close_price_snapshot",
                "CREATE TABLE IF NOT EXISTS stock_close_open_order_summary",
                "CREATE TABLE IF NOT EXISTS stock_close_open_order_snapshot",
                "UNIQUE KEY uk_stock_close_account_snapshot_cycle_account (close_cycle_id, account_id)",
                "holding_market_value DECIMAL(19,2) NOT NULL DEFAULT 0.00",
                "holding_quantity BIGINT NOT NULL DEFAULT 0",
                "reserved_sell_quantity BIGINT NOT NULL DEFAULT 0",
                "holding_position_count BIGINT NOT NULL DEFAULT 0",
                "UNIQUE KEY uk_stock_close_price_snapshot_cycle_symbol (close_cycle_id, symbol)",
                "UNIQUE KEY uk_stock_close_open_order_snapshot_cycle_order (close_cycle_id, order_id)",
                "ADD UNIQUE KEY uk_portfolio_snapshot_cycle_account (close_cycle_id, account_id)",
                "ADD COLUMN input_hash VARCHAR(64) NULL",
                "ADD COLUMN calculation_version VARCHAR(40) NULL",
                "ADD COLUMN data_quality_status VARCHAR(20) NULL"
        );
        assertThat(batchDdl)
                .doesNotContain("FOREIGN KEY")
                .doesNotContain("ADD COLUMN IF NOT EXISTS");
    }

    @Test
    void eodReportParticipantSnapshotAlterDdl_freezesClassificationWithoutScanningHotLedgers()
            throws IOException {
        String batchDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_eod_report_participant_snapshot_alter.sql"),
                StandardCharsets.UTF_8
        );
        String backDdl = Files.readString(
                Path.of("../stock-back-service/src/main/resources/db/ddl/stock_eod_report_participant_snapshot_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(firstExecutableSqlLine(batchDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(batchDdl).contains(
                "information_schema.columns",
                "information_schema.table_constraints",
                "ADD COLUMN participant_category VARCHAR(30) NULL AFTER account_status",
                "chk_stock_close_account_snapshot_participant_category",
                "stock_auto_participant participant",
                "snapshot.user_key LIKE 'stock-listing-%'"
        );
        assertThat(batchDdl).doesNotContain(
                "ALTER TABLE stock_order",
                "ALTER TABLE stock_execution",
                "FROM stock_order",
                "JOIN stock_order",
                "FROM stock_execution",
                "JOIN stock_execution",
                "ADD COLUMN IF NOT EXISTS"
        );
    }

    @Test
    void portfolioPostCloseCashDataFix_isGuardedIdempotentAndMatchesBackCopy() throws IOException {
        String batchDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_portfolio_snapshot_post_close_cash_data_fix.sql"),
                StandardCharsets.UTF_8
        );
        String backDdl = Files.readString(
                Path.of("../stock-back-service/src/main/resources/db/ddl/stock_portfolio_snapshot_post_close_cash_data_fix.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(firstExecutableSqlLine(batchDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(batchDdl).contains(
                "stock_portfolio_asset_fix_guard",
                "pending_subscription_asset",
                "portfolio-v2-frozen-close",
                "portfolio-v3-post-close-cash",
                "portfolio-v4-explicit-subscription-asset",
                "portfolio-v1-explicit-asset-backfill",
                "account_snapshot.post_cancel_cash",
                "entitlement.subscribed_at <= legacy.created_at",
                "chk_portfolio_snapshot_asset_composition",
                "SHA2(",
                "START TRANSACTION;",
                "COMMIT;"
        );
        assertThat(batchDdl).doesNotContain(
                "FROM stock_order ",
                "JOIN stock_order ",
                "UPDATE stock_order ",
                "FROM stock_execution ",
                "JOIN stock_execution ",
                "UPDATE stock_execution "
        );
    }

    @Test
    void executionProfitSummaryAlterDdl_backfillsOnlyInMaintenanceWindowAndMatchesBackCopy()
            throws IOException {
        String batchDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_execution_profit_summary_alter.sql"),
                StandardCharsets.UTF_8
        );
        String backDdl = Files.readString(
                Path.of("../stock-back-service/src/main/resources/db/ddl/stock_execution_profit_summary_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(firstExecutableSqlLine(batchDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(batchDdl).contains(
                "stock_execution_profit_summary_columns_were_missing",
                "stock_execution_profit_summary_requires_backfill",
                "ALTER TABLE stock_execution_account_day_summary",
                "ADD COLUMN buy_gross_amount",
                "ADD COLUMN sell_net_amount",
                "ADD COLUMN realized_profit",
                "gross_amount <> buy_gross_amount + sell_gross_amount",
                "FROM stock_execution",
                "GROUP BY DATE(executed_at), account_id"
        );
        assertThat(batchDdl)
                .doesNotContain("ALTER TABLE stock_execution ")
                .doesNotContain("CREATE INDEX")
                .doesNotContain("ADD COLUMN IF NOT EXISTS");
    }

    @Test
    void eodVolumeIndexesAlterDdl_isIdempotentAvoidsHotLedgersAndMatchesBackCopy() throws IOException {
        String batchDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_eod_volume_indexes_alter.sql"),
                StandardCharsets.UTF_8
        );
        String backDdl = Files.readString(
                Path.of("../stock-back-service/src/main/resources/db/ddl/stock_eod_volume_indexes_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(firstExecutableSqlLine(batchDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(batchDdl).contains(
                "information_schema.statistics",
                "idx_stock_close_account_snapshot_cycle_target",
                "idx_stock_close_account_snapshot_cycle_reconciliation",
                "idx_stock_close_open_order_snapshot_cycle_release_order",
                "idx_stock_close_open_order_snapshot_cycle_stream",
                "idx_stock_account_cash_flow_account_id",
                "idx_stock_corporate_action_entitlement_account_status",
                "idx_stock_post_close_cycle_scope_status_date",
                "idx_stock_batch_job_signal_cycle_id",
                "source_order_status",
                "ALTER TABLE stock_close_account_snapshot",
                "ALTER TABLE stock_close_open_order_snapshot"
        );
        assertThat(batchDdl)
                .contains(
                        "'UPDATE stock_close_open_order_snapshot snapshot LEFT JOIN stock_order orders",
                        "AND is_nullable = 'YES'"
                )
                .doesNotContain("\nUPDATE stock_close_open_order_snapshot snapshot");
        assertThat(HOT_LEDGER_INDEX_DDL.matcher(batchDdl).find()).isFalse();
    }

    @Test
    void batchJobSignalLeaseAlterDdl_hasClaimBackoffFieldsAndSyncedBackCopy() throws IOException {
        String batchDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_batch_job_signal_lease_alter.sql"),
                StandardCharsets.UTF_8
        );
        String backDdl = Files.readString(
                Path.of("../stock-back-service/src/main/resources/db/ddl/stock_batch_job_signal_lease_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(firstExecutableSqlLine(batchDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(batchDdl).contains(
                "information_schema.columns",
                "information_schema.check_constraints",
                "PREPARE stock_batch_signal_statement",
                "requested_business_date DATE NULL",
                "requested_session_epoch BIGINT NULL",
                "expected_cycle_id BIGINT NULL",
                "eligible_at DATETIME NULL",
                "next_attempt_at DATETIME NOT NULL",
                "claim_token VARCHAR(64) NULL",
                "lease_until DATETIME NULL",
                "'DEFERRED'",
                "'DEAD_LETTER'",
                "idx_stock_batch_job_signal_claim",
                "idx_stock_batch_job_signal_lease",
                "UPDATE stock_batch_job_signal job_signal"
        );
        assertThat(batchDdl)
                .doesNotContain("UPDATE stock_batch_job_signal signal")
                .doesNotContain("ADD COLUMN IF NOT EXISTS");
    }

    @Test
    void eodAlterDdl_doesNotAddOrderOrExecutionWriteIndexes() throws IOException {
        for (String fileName : EOD_ALTER_DDL_FILES) {
            String batchDdl = Files.readString(
                    Path.of("src/main/resources/db/ddl", fileName),
                    StandardCharsets.UTF_8
            );
            String backDdl = Files.readString(
                    Path.of("../stock-back-service/src/main/resources/db/ddl", fileName),
                    StandardCharsets.UTF_8
            );

            assertThat(normalizeSqlBlock(backDdl)).as(fileName + " mirrored DDL").isEqualTo(normalizeSqlBlock(batchDdl));
            assertThat(HOT_LEDGER_INDEX_DDL.matcher(batchDdl).find())
                    .as(fileName + " must not increase regular-session ledger index writes")
                    .isFalse();
            assertThat(batchDdl)
                    .as(fileName + " must use MySQL 8-compatible conditional DDL")
                    .doesNotContain("ADD COLUMN IF NOT EXISTS");
        }
    }

    @Test
    void recurringCashRunAlterDdl_isRestartableBoundedAndSyncedWithBackServiceCopy() throws IOException {
        String batchDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_auto_participant_cash_flow_run_alter.sql"),
                StandardCharsets.UTF_8
        );
        String backDdl = Files.readString(
                Path.of("../stock-back-service/src/main/resources/db/ddl/stock_auto_participant_cash_flow_run_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(firstExecutableSqlLine(batchDdl)).isEqualTo("USE STOCK_SERVICE;");
        assertThat(batchDdl).contains(
                "CREATE TABLE IF NOT EXISTS stock_auto_participant_cash_flow_run",
                "PRIMARY KEY (run_key)",
                "last_account_id BIGINT NOT NULL DEFAULT 0",
                "processed_count BIGINT NOT NULL DEFAULT 0",
                "idx_stock_auto_participant_cash_flow_run_completed"
        );
        assertThat(batchDdl).doesNotContain(
                "stock_order ",
                "stock_execution "
        );
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
    void batchMetadataRetentionMysqlDdl_isMetadataOnlyAndIdempotent() throws IOException {
        String ddl = readDdlResource("db/schema/batch-metadata-retention-mysql.sql");

        assertThat(firstExecutableSqlLine(ddl)).isEqualTo("CREATE SCHEMA IF NOT EXISTS STOCK_BATCH_METADATA;");
        assertThat(ddl)
                .contains(
                        "USE STOCK_BATCH_METADATA;",
                        "CREATE TABLE IF NOT EXISTS STOCK_BATCH_JOB_METADATA_ARCHIVE",
                        "idx_batch_job_execution_retention"
                )
                .doesNotContain("USE STOCK_SERVICE;", "stock_order", "stock_execution(");
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
                        "requested_business_date DATE",
                        "requested_session_epoch BIGINT",
                        "expected_cycle_id BIGINT",
                        "eligible_at TIMESTAMP",
                        "next_attempt_at TIMESTAMP NOT NULL",
                        "attempt_count INT NOT NULL DEFAULT 0",
                        "max_attempts INT NOT NULL DEFAULT 8",
                        "claim_token VARCHAR(64)",
                        "lease_until TIMESTAMP",
                        "failure_class VARCHAR(40)",
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
                        "operation_mode VARCHAR(30) NOT NULL",
                        "strategy_profile VARCHAR(30) NOT NULL",
                        "initial_inventory_quantity BIGINT NOT NULL",
                        "initial_issue_price DECIMAL(19,2) NOT NULL",
                        "target_spread_ticks INT NOT NULL",
                        "aggressive_order_ratio DECIMAL(8,4) NOT NULL",
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
    void portfolioSnapshotHoldingMetricsAlterDdl_isGuardedAndSyncedWithBackServiceCopy() throws IOException {
        String batchDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_portfolio_snapshot_holding_metrics_alter.sql"),
                StandardCharsets.UTF_8
        );
        String backDdl = Files.readString(
                Path.of("../stock-back-service/src/main/resources/db/ddl/stock_portfolio_snapshot_holding_metrics_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(batchDdl).isEqualTo(backDdl);
        assertThat(batchDdl).contains(
                "stock_migration_required_portfolio_snapshot_holding_metrics_schema",
                "@stock_portfolio_snapshot_holding_metric_column_count = 0",
                "@stock_portfolio_snapshot_holding_metric_correct_column_count = 3",
                "holding_quantity BIGINT NULL",
                "reserved_sell_quantity BIGINT NULL",
                "holding_position_count BIGINT NULL",
                "chk_portfolio_snapshot_holding_metrics_complete",
                "REPLACE(LOWER(cc.check_clause), '`', '') LIKE '%holding_quantity >= 0%'",
                "REPLACE(LOWER(cc.check_clause), '`', '') LIKE '%reserved_sell_quantity >= 0%'",
                "reserved_sell_quantity <= holding_quantity",
                "REPLACE(LOWER(cc.check_clause), '`', '') LIKE '%holding_position_count >= 0%'",
                "SET SESSION lock_wait_timeout = 15",
                "ALGORITHM=COPY",
                "LOCK=SHARED"
        );
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
    void listingAutoStrategyPolicyAlterDdl_repairsIssueBasisAndIsSyncedWithBackServiceCopy() throws IOException {
        String batchDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_listing_auto_strategy_policy_alter.sql"),
                StandardCharsets.UTF_8
        );
        String backDdl = Files.readString(
                Path.of("../stock-back-service/src/main/resources/db/ddl/stock_listing_auto_strategy_policy_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(batchDdl).contains(
                "USE STOCK_SERVICE;",
                "operation_mode VARCHAR(30) NOT NULL",
                "initial_inventory_quantity BIGINT NOT NULL",
                "MIN(candidate.id)",
                "COALESCE(initial_issue.share_quantity, instrument.issued_shares)",
                "DROP COLUMN buy_price_offset_direction",
                "DROP COLUMN sell_price_offset_direction"
        );
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
    void autoMarketPressureDistributionAlterDdl_isGuardedAndSyncedWithBackServiceCopyAndNewContract() throws IOException {
        String batchDdl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_auto_market_pressure_distribution_alter.sql"),
                StandardCharsets.UTF_8
        );
        String backDdl = Files.readString(
                Path.of("../stock-back-service/src/main/resources/db/ddl/stock_auto_market_pressure_distribution_alter.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(normalizeSqlBlock(backDdl)).isEqualTo(normalizeSqlBlock(batchDdl));
        assertThat(batchDdl).contains(
                "USE STOCK_SERVICE",
                "information_schema.columns",
                "information_schema.check_constraints",
                "stock_migration_required_auto_market_pressure_distribution_schema",
                "@stock_auto_market_pressure_legacy_column_count = 13",
                "@stock_auto_market_pressure_new_column_count = 0",
                "@stock_auto_market_pressure_legacy_check_count = 15",
                "DROP COLUMN intensity",
                "primary_price_pressure_bias",
                "secondary_execution_aggression_pressure_bias",
                "price_pressure INT NULL",
                "execution_aggression_pressure INT NULL",
                "WHEN 'SLOT_0600' THEN 1",
                "WHEN 'SLOT_0900' THEN 1",
                "WHEN 'SLOT_1200' THEN 1",
                "WHEN 'SLOT_1500' THEN 1",
                "BETWEEN -100 AND 100"
        );
        assertThat(batchDdl).containsSubsequence(
                "stock_migration_required_auto_market_pressure_distribution_schema",
                "ALTER TABLE stock_auto_market_config"
        );
    }

    @Test
    void autoMarketRegimeCountWeightsDdl_isSyncedAndPresentInH2Contract() throws IOException {
        String batchAlter = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_auto_market_regime_count_weights_alter.sql"),
                StandardCharsets.UTF_8
        );
        String backAlter = Files.readString(
                Path.of("../stock-back-service/src/main/resources/db/ddl/stock_auto_market_regime_count_weights_alter.sql"),
                StandardCharsets.UTF_8
        );
        String h2Ddl = Files.readString(
                Path.of("src/main/resources/db/ddl/stock_h2.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(List.of(
                normalizeSqlBlock(backAlter).equals(normalizeSqlBlock(batchAlter)),
                batchAlter.contains("USE STOCK_SERVICE"),
                batchAlter.contains("primary_regime_count_1_weight"),
                batchAlter.contains("primary_regime_count_4_weight"),
                batchAlter.contains("source_regime_phase"),
                h2Ddl.contains("primary_regime_count_4_weight INT NOT NULL DEFAULT 100"),
                h2Ddl.contains("source_regime_phase VARCHAR(20) NULL")
        )).doesNotContain(false);
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
                "DROP INDEX idx_stock_price_tick_symbol_time",
                "ALGORITHM=INPLACE, LOCK=NONE"
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
