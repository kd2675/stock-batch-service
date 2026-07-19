package stock.batch.service.mysql;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import stock.batch.service.batch.marketclose.writer.MarketCloseRolloverWriter;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("mysql")
@Testcontainers
class StockMysqlDdlMigrationTest {

    private static final List<String> EOD_ALTER_FILES = List.of(
            "stock_eod_session_fence_alter.sql",
            "stock_eod_cycle_alter.sql",
            "stock_eod_immutable_snapshot_alter.sql",
            "stock_eod_report_participant_snapshot_alter.sql",
            "stock_batch_job_signal_lease_alter.sql",
            "stock_corporate_action_processing_alter.sql",
            "stock_corporate_action_chunking_alter.sql",
            "stock_execution_daily_account_last_executed_at_alter.sql",
            "stock_execution_profit_summary_alter.sql",
            "stock_eod_volume_indexes_alter.sql",
            "stock_auto_participant_cash_flow_run_alter.sql"
    );
    private static final String EOD_APPLICATION_ROLLBACK_FILE =
            "stock_eod_application_rollback_alter.sql";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.36")
            .withDatabaseName("STOCK_SERVICE")
            .withUsername("stock_test")
            .withPassword("stock_test");

    @Test
    void canonicalDdlAndEodAlters_reapplyWithoutChangingHotLedgerIndexes() throws IOException {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        resetToCanonicalSchema(dataSource, jdbcTemplate);
        int orderIndexCountBefore = indexCount(jdbcTemplate, "stock_order");
        int executionIndexCountBefore = indexCount(jdbcTemplate, "stock_execution");

        for (int attempt = 0; attempt < 2; attempt++) {
            for (String alterFile : EOD_ALTER_FILES) {
                executeScript(dataSource, ddlPath(alterFile), false);
            }
        }

        assertThat(indexCount(jdbcTemplate, "stock_order")).isEqualTo(orderIndexCountBefore);
        assertThat(indexCount(jdbcTemplate, "stock_execution")).isEqualTo(executionIndexCountBefore);
        assertThat(tableCount(jdbcTemplate, "stock_market_session_fence")).isEqualTo(1);
        assertThat(tableCount(jdbcTemplate, "stock_post_close_cycle")).isEqualTo(1);
        assertThat(tableCount(jdbcTemplate, "stock_post_close_readiness_check")).isEqualTo(1);
        assertThat(columnCount(jdbcTemplate, "stock_post_close_cycle", "next_retry_at")).isEqualTo(1);
        assertThat(tableCount(jdbcTemplate, "stock_close_account_snapshot")).isEqualTo(1);
        assertThat(columnCount(
                jdbcTemplate,
                "stock_close_account_snapshot",
                "participant_category"
        )).isEqualTo(1);
        assertThat(tableCount(jdbcTemplate, "stock_corporate_action_processing")).isEqualTo(1);
        assertThat(columnCount(jdbcTemplate, "stock_batch_job_signal", "claim_token")).isEqualTo(1);
        assertThat(columnCount(
                jdbcTemplate,
                "stock_execution_daily_account_snapshot",
                "last_executed_at"
        )).isEqualTo(1);
        assertThat(columnCount(
                jdbcTemplate,
                "stock_execution_account_day_summary",
                "realized_profit"
        )).isEqualTo(1);
        assertThat(indexNamedCount(
                jdbcTemplate,
                "stock_close_open_order_snapshot",
                "idx_stock_close_open_order_snapshot_cycle_stream"
        )).isEqualTo(1);
        assertThat(tableCount(jdbcTemplate, "stock_auto_participant_cash_flow_run")).isEqualTo(1);
    }

    @Test
    void postCloseReportQueries_mysqlIndexHintGrammar_executesAgainstCanonicalSchema() throws IOException {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        resetToCanonicalSchema(dataSource, jdbcTemplate);
        MarketCloseRolloverWriter writer = new MarketCloseRolloverWriter(jdbcTemplate);
        LocalDate businessDate = LocalDate.of(2026, 7, 19);
        LocalDateTime rangeStart = businessDate.atStartOfDay();
        LocalDateTime rangeEnd = businessDate.plusDays(1).atStartOfDay();

        assertThat(List.of(
                writer.snapshotOrderBookDailySymbols(
                        1L,
                        1L,
                        "NO_MATCHING_SYMBOL",
                        businessDate,
                        rangeEnd,
                        rangeStart,
                        rangeEnd
                ),
                writer.snapshotDailyAccountExecutions(
                        1L,
                        1L,
                        "NO_MATCHING_SYMBOL",
                        businessDate,
                        rangeEnd,
                        rangeStart,
                        rangeEnd
                ),
                writer.updateClosePriceLastExecutionId(
                        1L,
                        "NO_MATCHING_SYMBOL",
                        rangeStart,
                        rangeEnd
                )
        )).containsExactly(0, 0, 0);
    }

    @Test
    void legacySchemaAndRows_eodAltersCreateBackfillAndReapplyWithoutChangingHotLedgers()
            throws IOException {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        resetToCanonicalSchema(dataSource, jdbcTemplate);
        String orderDdlBefore = showCreateTable(jdbcTemplate, "stock_order");
        String executionDdlBefore = showCreateTable(jdbcTemplate, "stock_execution");
        downgradeToLegacyEodSchema(jdbcTemplate);

        jdbcTemplate.update(
                """
                insert into stock_batch_job_signal(
                    signal_type, job_name, execution_mode, symbol, payload_json, status,
                    requested_by, requested_at, picked_at, completed_at, processed_count,
                    message, error_message, created_at, updated_at
                ) values (?, ?, ?, null, null, 'PENDING', ?, ?, null, null, null, null, null, ?, ?)
                """,
                "RUN_JOB",
                "market-close-rollover",
                "FULL_MARKET",
                "mysql-migration-test",
                "2026-07-15 17:59:00",
                "2026-07-15 17:59:00",
                "2026-07-15 17:59:00"
        );

        executeScript(dataSource, ddlPath("stock_eod_session_fence_alter.sql"), false);
        executeScript(dataSource, ddlPath("stock_eod_cycle_alter.sql"), false);

        // Exercise the upgrade branch used by an installation that already has the cycle table
        // but predates durable retry scheduling.
        jdbcTemplate.execute("alter table stock_post_close_cycle drop column next_retry_at");
        executeScript(dataSource, ddlPath("stock_eod_cycle_alter.sql"), false);

        executeScript(dataSource, ddlPath("stock_eod_immutable_snapshot_alter.sql"), false);

        // Exercise the upgrade branch used by a database that created immutable account
        // snapshots before participant classification became a frozen report input.
        jdbcTemplate.execute(
                """
                alter table stock_close_account_snapshot
                  drop check chk_stock_close_account_snapshot_participant_category,
                  drop column participant_category
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_close_account_snapshot(
                    close_cycle_id, close_run_id, account_id, user_key, account_status,
                    settlement_target, pre_cancel_cash, snapshot_at, created_at
                ) values (8001, 8001, 8001, 'stock-listing-ddltest', 'ACTIVE', true, 0,
                          '2026-07-15 18:00:00', '2026-07-15 18:00:00')
                """
        );
        executeScript(dataSource, ddlPath("stock_eod_report_participant_snapshot_alter.sql"), false);
        executeScript(dataSource, ddlPath("stock_batch_job_signal_lease_alter.sql"), false);
        executeScript(dataSource, ddlPath("stock_corporate_action_processing_alter.sql"), false);
        executeScript(dataSource, ddlPath("stock_corporate_action_chunking_alter.sql"), false);
        executeScript(
                dataSource,
                ddlPath("stock_execution_daily_account_last_executed_at_alter.sql"),
                false
        );
        jdbcTemplate.update(
                """
                insert into stock_execution(
                    order_id, account_id, symbol, side, quantity, price, gross_amount,
                    fee_amount, tax_amount, net_amount, realized_profit, source, executed_at
                ) values
                    (7001, 7001, 'DDLTEST', 'BUY', 10, 100.00, 1000.00,
                     1.00, 0.00, 1001.00, null, 'INTERNAL_ORDER_BOOK', '2026-07-15 09:00:00'),
                    (7002, 7001, 'DDLTEST', 'SELL', 10, 120.00, 1200.00,
                     1.20, 2.40, 1196.40, 196.40, 'INTERNAL_ORDER_BOOK', '2026-07-15 10:00:00')
                """
        );
        executeScript(dataSource, ddlPath("stock_execution_profit_summary_alter.sql"), false);

        // Simulate a connection loss after the columns committed but before the historical
        // rebuild committed. A retry must detect the default-zero summary shape and rebuild it
        // without requiring another schema marker table or touching the hot ledger definition.
        jdbcTemplate.update(
                """
                update stock_execution_account_day_summary
                   set buy_gross_amount = 0,
                       sell_gross_amount = 0,
                       buy_net_amount = 0,
                       sell_net_amount = 0,
                       fee_amount = 0,
                       tax_amount = 0,
                       realized_profit = 0
                 where simulation_trade_date = '2026-07-15'
                   and account_id = 7001
                """
        );

        // The immutable-snapshot migration is also used by installations that were upgraded
        // before the volume cursor was introduced. Recreate that intermediate shape so the
        // source-status backfill and every bounded EOD index branch run at least once.
        jdbcTemplate.update(
                """
                insert into stock_close_open_order_snapshot(
                    close_cycle_id, close_run_id, order_id, account_id, symbol, side,
                    source_order_status, remaining_quantity, reserved_cash, captured_at, released_at
                ) values (9001, 9001, 9001, 9001, 'DDLTEST', 'BUY', 'PARTIALLY_FILLED',
                          1, 1.00, '2026-07-15 18:00:00', null)
                """
        );
        downgradeToPreVolumeSnapshotSchema(jdbcTemplate);
        executeScript(dataSource, ddlPath("stock_eod_volume_indexes_alter.sql"), false);

        // A maintenance-window retry repairs the simulated interrupted summary backfill while
        // every already-complete schema object remains a no-op.
        for (String alterFile : EOD_ALTER_FILES) {
            executeScript(dataSource, ddlPath(alterFile), false);
        }

        assertThat(showCreateTable(jdbcTemplate, "stock_order")).isEqualTo(orderDdlBefore);
        assertThat(showCreateTable(jdbcTemplate, "stock_execution")).isEqualTo(executionDdlBefore);
        assertThat(columnCount(jdbcTemplate, "stock_holding_snapshot", "close_cycle_id")).isEqualTo(1);
        assertThat(columnCount(jdbcTemplate, "stock_holding_snapshot", "evaluation_price")).isEqualTo(1);
        assertThat(columnCount(jdbcTemplate, "portfolio_snapshot", "input_hash")).isEqualTo(1);
        assertThat(columnCount(
                jdbcTemplate,
                "stock_close_account_snapshot",
                "participant_category"
        )).isEqualTo(1);
        assertThat(requiredCount(
                jdbcTemplate,
                """
                select count(*)
                  from stock_close_account_snapshot
                 where close_cycle_id = 8001
                   and account_id = 8001
                   and participant_category = 'LISTING_UNDERWRITER'
                """
        )).isEqualTo(1);
        assertThat(columnCount(jdbcTemplate, "stock_batch_job_signal", "claim_token")).isEqualTo(1);
        assertThat(requiredCount(
                jdbcTemplate,
                """
                select count(*)
                  from stock_batch_job_signal
                 where requested_business_date is not null
                   and next_attempt_at = requested_at
                """
        )).isEqualTo(1);
        assertThat(indexNamedCount(
                jdbcTemplate,
                "stock_corporate_action_entitlement",
                "idx_stock_corporate_action_entitlement_action_status_id"
        )).isEqualTo(1);
        assertThat(indexNamedCount(
                jdbcTemplate,
                "stock_account_cash_flow",
                "idx_stock_account_cash_flow_account_id"
        )).isEqualTo(1);
        assertThat(columnCount(
                jdbcTemplate,
                "stock_execution_daily_account_snapshot",
                "last_executed_at"
        )).isEqualTo(1);
        assertThat(requiredCount(
                jdbcTemplate,
                """
                select count(*)
                  from stock_execution_account_day_summary
                 where simulation_trade_date = '2026-07-15'
                   and account_id = 7001
                   and execution_count = 2
                   and buy_gross_amount = 1000.00
                   and sell_gross_amount = 1200.00
                   and buy_net_amount = 1001.00
                   and sell_net_amount = 1196.40
                   and fee_amount = 2.20
                   and tax_amount = 2.40
                   and realized_profit = 196.40
                """
        )).isEqualTo(1);
        assertThat(indexNamedCount(
                jdbcTemplate,
                "stock_close_open_order_snapshot",
                "idx_stock_close_open_order_snapshot_cycle_stream"
        )).isEqualTo(1);
        assertThat(requiredCount(
                jdbcTemplate,
                """
                select count(*)
                  from stock_close_open_order_snapshot
                 where order_id = 9001
                   and source_order_status = 'PENDING'
                """
        )).isEqualTo(1);
    }

    @Test
    void eodApplicationRollback_preservesSchemaFailsNewSignalsClosedAndAllowsForwardReapply()
            throws IOException {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        resetToCanonicalSchema(dataSource, jdbcTemplate);
        String orderDdlBefore = showCreateTable(jdbcTemplate, "stock_order");
        String executionDdlBefore = showCreateTable(jdbcTemplate, "stock_execution");

        jdbcTemplate.update(
                """
                insert into stock_batch_job_signal(
                    signal_type, job_name, execution_mode, status, requested_by,
                    requested_at, eligible_at, next_attempt_at, attempt_count, max_attempts,
                    claim_token, lease_until, created_at, updated_at
                ) values
                    ('ROLLBACK_PENDING', 'rollback-test', 'pending', 'PENDING', 'test',
                     '2026-07-19 09:00:00', '2026-07-20 00:00:00', '2026-07-19 09:00:00', 0, 8,
                     null, null, '2026-07-19 09:00:00', '2026-07-19 09:00:00'),
                    ('ROLLBACK_DEFERRED', 'rollback-test', 'deferred', 'DEFERRED', 'test',
                     '2026-07-19 09:00:00', null, '2026-07-19 09:05:00', 1, 8,
                     null, null, '2026-07-19 09:00:00', '2026-07-19 09:00:00'),
                    ('ROLLBACK_PROCESSING', 'rollback-test', 'processing', 'PROCESSING', 'test',
                     '2026-07-19 09:00:00', null, '2026-07-19 09:00:00', 1, 8,
                     'rollback-claim', '2026-07-19 09:10:00',
                     '2026-07-19 09:00:00', '2026-07-19 09:00:00')
                """
        );

        executeScript(dataSource, ddlPath(EOD_APPLICATION_ROLLBACK_FILE), false);
        executeScript(dataSource, ddlPath(EOD_APPLICATION_ROLLBACK_FILE), false);

        assertThat(requiredCount(
                jdbcTemplate,
                """
                select count(*)
                  from stock_batch_job_signal
                 where job_name = 'rollback-test'
                   and status = 'FAILED'
                   and claim_token is null
                   and lease_until is null
                   and failure_class = 'APPLICATION_ROLLBACK'
                """
        )).isEqualTo(3);
        assertThat(requiredCount(
                jdbcTemplate,
                """
                select count(*)
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = 'stock_batch_job_signal'
                   and column_name = 'next_attempt_at'
                   and is_nullable = 'YES'
                """
        )).isEqualTo(1);

        jdbcTemplate.update(
                """
                insert into stock_batch_job_signal(
                    signal_type, job_name, execution_mode, symbol, payload_json, status,
                    requested_by, requested_at, picked_at, completed_at, processed_count,
                    message, error_message, created_at, updated_at
                ) values ('LEGACY_SIGNAL', 'legacy-signal-test', 'legacy', null, null, 'PENDING',
                          'test', '2026-07-19 09:30:00', null, null, null, null, null,
                          '2026-07-19 09:30:00', '2026-07-19 09:30:00')
                """
        );

        executeScript(dataSource, ddlPath("stock_batch_job_signal_lease_alter.sql"), false);

        assertThat(requiredCount(
                jdbcTemplate,
                """
                select count(*)
                  from stock_batch_job_signal
                 where job_name = 'legacy-signal-test'
                   and status = 'PENDING'
                   and next_attempt_at = requested_at
                """
        )).isEqualTo(1);
        assertThat(requiredCount(
                jdbcTemplate,
                """
                select count(*)
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = 'stock_batch_job_signal'
                   and column_name = 'next_attempt_at'
                   and is_nullable = 'NO'
                """
        )).isEqualTo(1);
        assertThat(showCreateTable(jdbcTemplate, "stock_order")).isEqualTo(orderDdlBefore);
        assertThat(showCreateTable(jdbcTemplate, "stock_execution")).isEqualTo(executionDdlBefore);
        assertThat(tableCount(jdbcTemplate, "stock_post_close_cycle")).isEqualTo(1);
        assertThat(tableCount(jdbcTemplate, "stock_close_account_snapshot")).isEqualTo(1);
    }

    private void resetToCanonicalSchema(
            DriverManagerDataSource dataSource,
            JdbcTemplate jdbcTemplate
    ) throws IOException {
        List<String> tableNames = jdbcTemplate.queryForList(
                """
                select table_name
                  from information_schema.tables
                 where table_schema = database()
                   and table_type = 'BASE TABLE'
                """,
                String.class
        );
        if (!tableNames.isEmpty()) {
            String quotedTableNames = String.join(
                    ", ",
                    tableNames.stream()
                            .map(tableName -> "`" + tableName.replace("`", "``") + "`")
                            .toList()
            );
            jdbcTemplate.execute("drop table if exists " + quotedTableNames);
        }
        executeScript(dataSource, ddlPath("stock_all.sql"), true);
    }

    private void downgradeToLegacyEodSchema(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute(
                """
                drop table if exists
                  stock_corporate_action_processing,
                  stock_close_open_order_snapshot,
                  stock_close_open_order_summary,
                  stock_close_price_snapshot,
                  stock_close_account_snapshot,
                  stock_post_close_cycle_metric,
                  stock_post_close_readiness_check,
                  stock_post_close_phase_attempt,
                  stock_post_close_cycle,
                  stock_auto_participant_cash_flow_run,
                  stock_market_session_fence,
                  stock_market_business_state
                """
        );
        jdbcTemplate.execute(
                """
                alter table stock_holding_snapshot
                  drop check chk_stock_holding_snapshot_evaluation_price,
                  drop index idx_stock_holding_snapshot_cycle_account,
                  drop column evaluation_price,
                  drop column close_cycle_id
                """
        );
        jdbcTemplate.execute(
                """
                alter table portfolio_snapshot
                  drop check chk_portfolio_snapshot_input_hash,
                  drop check chk_portfolio_snapshot_data_quality,
                  drop index uk_portfolio_snapshot_cycle_account,
                  drop index idx_portfolio_snapshot_close_run,
                  drop column source_build_version,
                  drop column data_quality_status,
                  drop column calculation_version,
                  drop column input_hash,
                  drop column close_run_id,
                  drop column close_cycle_id
                """
        );
        jdbcTemplate.execute(
                """
                alter table stock_batch_job_signal
                  drop check chk_stock_batch_job_signal_status,
                  drop check chk_stock_batch_job_signal_attempt_count,
                  drop check chk_stock_batch_job_signal_max_attempts,
                  drop check chk_stock_batch_job_signal_epoch,
                  drop index idx_stock_batch_job_signal_claim,
                  drop index idx_stock_batch_job_signal_lease,
                  drop index idx_stock_batch_job_signal_cycle,
                  drop index idx_stock_batch_job_signal_cycle_id,
                  drop column failure_class,
                  drop column lease_until,
                  drop column claim_token,
                  drop column max_attempts,
                  drop column attempt_count,
                  drop column next_attempt_at,
                  drop column eligible_at,
                  drop column expected_cycle_id,
                  drop column requested_session_epoch,
                  drop column requested_business_date,
                  add constraint chk_stock_batch_job_signal_status check (
                    case `status`
                      when 'PENDING' then 1
                      when 'PROCESSING' then 1
                      when 'COMPLETED' then 1
                      when 'FAILED' then 1
                      else 0
                    end = 1
                  )
                """
        );
        jdbcTemplate.execute(
                """
                alter table stock_corporate_action_entitlement
                  drop index idx_stock_corporate_action_entitlement_account_status,
                  drop index idx_stock_corporate_action_entitlement_action_status_id
                """
        );
        jdbcTemplate.execute(
                """
                alter table stock_execution_daily_account_snapshot
                  drop column last_executed_at
                """
        );
        jdbcTemplate.execute(
                """
                alter table stock_execution_account_day_summary
                  drop check chk_stock_execution_account_day_amount,
                  drop column buy_gross_amount,
                  drop column sell_gross_amount,
                  drop column buy_net_amount,
                  drop column sell_net_amount,
                  drop column fee_amount,
                  drop column tax_amount,
                  drop column realized_profit,
                  add constraint chk_stock_execution_account_day_amount check (gross_amount >= 0)
                """
        );
        jdbcTemplate.execute(
                """
                alter table stock_account_cash_flow
                  drop index idx_stock_account_cash_flow_account_id
                """
        );
    }

    private void downgradeToPreVolumeSnapshotSchema(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute(
                """
                alter table stock_close_open_order_snapshot
                  drop check chk_stock_close_open_order_snapshot_status,
                  drop index idx_stock_close_open_order_snapshot_cycle_stream,
                  drop index idx_stock_close_open_order_snapshot_cycle_release_order,
                  drop column source_order_status
                """
        );
        jdbcTemplate.execute(
                """
                alter table stock_close_account_snapshot
                  drop index idx_stock_close_account_snapshot_cycle_reconciliation,
                  drop index idx_stock_close_account_snapshot_cycle_target
                """
        );
    }

    private void executeScript(
            DriverManagerDataSource dataSource,
            Path scriptPath,
            boolean omitCreateDatabase
    ) throws IOException {
        String sql = Files.readString(scriptPath, StandardCharsets.UTF_8);
        if (omitCreateDatabase) {
            int useSchemaOffset = sql.indexOf("USE STOCK_SERVICE;");
            assertThat(useSchemaOffset).isGreaterThanOrEqualTo(0);
            sql = sql.substring(useSchemaOffset);
        }

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
        populator.addScript(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        populator.execute(dataSource);
    }

    private Path ddlPath(String filename) {
        Path workingDirectory = Path.of(System.getProperty("user.dir"));
        Path moduleRelative = workingDirectory
                .resolve("../stock-back-service/src/main/resources/db/ddl")
                .resolve(filename)
                .normalize();
        if (Files.isRegularFile(moduleRelative)) {
            return moduleRelative;
        }

        Path rootRelative = workingDirectory
                .resolve("stock-back-service/src/main/resources/db/ddl")
                .resolve(filename)
                .normalize();
        assertThat(rootRelative).isRegularFile();
        return rootRelative;
    }

    private int tableCount(JdbcTemplate jdbcTemplate, String tableName) {
        return requiredCount(
                jdbcTemplate,
                """
                select count(*)
                  from information_schema.tables
                 where table_schema = database()
                   and table_name = ?
                """,
                tableName
        );
    }

    private int columnCount(JdbcTemplate jdbcTemplate, String tableName, String columnName) {
        return requiredCount(
                jdbcTemplate,
                """
                select count(*)
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = ?
                   and column_name = ?
                """,
                tableName,
                columnName
        );
    }

    private int indexNamedCount(JdbcTemplate jdbcTemplate, String tableName, String indexName) {
        return requiredCount(
                jdbcTemplate,
                """
                select count(distinct index_name)
                  from information_schema.statistics
                 where table_schema = database()
                   and table_name = ?
                   and index_name = ?
                """,
                tableName,
                indexName
        );
    }

    private int indexCount(JdbcTemplate jdbcTemplate, String tableName) {
        return requiredCount(
                jdbcTemplate,
                """
                select count(distinct index_name)
                  from information_schema.statistics
                 where table_schema = database()
                   and table_name = ?
                """,
                tableName
        );
    }

    private String showCreateTable(JdbcTemplate jdbcTemplate, String tableName) {
        String createTable = jdbcTemplate.queryForObject(
                "show create table `" + tableName.replace("`", "``") + "`",
                (resultSet, rowNumber) -> resultSet.getString(2)
        );
        assertThat(createTable).isNotNull();
        return createTable;
    }

    private int requiredCount(JdbcTemplate jdbcTemplate, String sql, Object... arguments) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, arguments);
        assertThat(count).isNotNull();
        return count;
    }
}
