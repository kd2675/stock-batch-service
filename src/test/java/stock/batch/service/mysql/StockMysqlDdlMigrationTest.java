package stock.batch.service.mysql;

import java.io.IOException;
import java.math.BigDecimal;
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
import stock.batch.service.batch.settlement.model.AccountSettlementTarget;
import stock.batch.service.batch.settlement.processor.PortfolioSnapshotProcessor;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("mysql")
@Testcontainers
class StockMysqlDdlMigrationTest {

    private static final List<String> EOD_ALTER_FILES = List.of(
            "stock_eod_session_fence_alter.sql",
            "stock_eod_cycle_alter.sql",
            "stock_eod_immutable_snapshot_alter.sql",
            "stock_portfolio_snapshot_post_close_cash_data_fix.sql",
            "stock_portfolio_snapshot_return_contract_alter.sql",
            "stock_eod_report_participant_snapshot_alter.sql",
            "stock_account_participant_category_alter.sql",
            "stock_batch_job_signal_lease_alter.sql",
            "stock_corporate_action_processing_alter.sql",
            "stock_corporate_action_chunking_alter.sql",
            "stock_execution_daily_account_last_executed_at_alter.sql",
            "stock_execution_profit_summary_alter.sql",
            "stock_eod_volume_indexes_alter.sql",
            "stock_auto_participant_cash_flow_run_alter.sql",
            "stock_capital_increase_lifecycle_hardening_alter.sql"
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
        assertThat(columnCount(jdbcTemplate, "portfolio_snapshot", "net_contribution")).isEqualTo(1);
        assertThat(columnCount(jdbcTemplate, "portfolio_snapshot", "total_profit")).isEqualTo(1);
        assertThat(columnCount(jdbcTemplate, "portfolio_snapshot", "return_rate_status")).isEqualTo(1);
        assertThat(indexNamedCount(
                jdbcTemplate,
                "stock_close_open_order_snapshot",
                "idx_stock_close_open_order_snapshot_cycle_stream"
        )).isEqualTo(1);
        assertThat(tableCount(jdbcTemplate, "stock_auto_participant_cash_flow_run")).isEqualTo(1);
        assertThat(columnCount(jdbcTemplate, "stock_corporate_action", "record_date")).isEqualTo(1);
        assertThat(columnCount(
                jdbcTemplate,
                "stock_corporate_action_entitlement",
                "forfeited_share_quantity"
        )).isEqualTo(1);
        assertThat(columnCount(
                jdbcTemplate,
                "stock_account_cash_flow",
                "effective_business_date"
        )).isEqualTo(1);
    }

    @Test
    void portfolioReturnContractAlter_upgradesLegacyShapeAndReapplies() throws IOException {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        resetToCanonicalSchema(dataSource, jdbcTemplate);
        jdbcTemplate.execute(
                """
                alter table portfolio_snapshot
                  drop check chk_portfolio_snapshot_return_contract,
                  drop column return_rate_status,
                  modify column return_rate decimal(9,4) not null,
                  drop column total_profit,
                  drop column net_contribution
                """
        );
        Path migration = ddlPath("stock_portfolio_snapshot_return_contract_alter.sql");

        executeScript(dataSource, migration, false);
        executeScript(dataSource, migration, false);

        assertThat(columnCount(jdbcTemplate, "portfolio_snapshot", "net_contribution")
                + columnCount(jdbcTemplate, "portfolio_snapshot", "total_profit")
                + columnCount(jdbcTemplate, "portfolio_snapshot", "return_rate_status"))
                .isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*)
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = 'portfolio_snapshot'
                   and column_name = 'return_rate'
                   and numeric_precision = 19
                   and numeric_scale = 8
                   and is_nullable = 'YES'
                """,
                Integer.class
        )).isEqualTo(1);
    }

    @Test
    void accountParticipantCategoryAlter_backfillsThreeRolesAndReappliesWithoutHotLedgerChanges()
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

        jdbcTemplate.execute("""
                alter table stock_account
                  drop check chk_stock_account_participant_category,
                  drop index idx_stock_account_status_participant_id,
                  drop column participant_category
                """);
        jdbcTemplate.update("""
                insert into stock_account(id, user_key, status, cash_balance, created_at, updated_at)
                values
                  (9901, 'migration-manual', 'ACTIVE', 100, current_timestamp, current_timestamp),
                  (9902, 'migration-auto', 'ACTIVE', 200, current_timestamp, current_timestamp),
                  (9903, 'stock-listing-MIGRATION', 'ACTIVE', 300, current_timestamp, current_timestamp)
                """);
        jdbcTemplate.update("""
                insert into stock_auto_participant(
                    user_key, display_name, enabled, profile_type, created_at, updated_at
                ) values ('migration-auto', 'migration auto', true, 'NOISE_TRADER', current_timestamp, current_timestamp)
                """);

        Path migration = ddlPath("stock_account_participant_category_alter.sql");
        executeScript(dataSource, migration, false);
        executeScript(dataSource, migration, false);

        assertThat(jdbcTemplate.queryForObject(
                "select participant_category from stock_account where id = 9901",
                String.class
        )).isEqualTo("MANUAL_PARTICIPANT");
        assertThat(jdbcTemplate.queryForObject(
                "select participant_category from stock_account where id = 9902",
                String.class
        )).isEqualTo("AUTO_PARTICIPANT");
        assertThat(jdbcTemplate.queryForObject(
                "select participant_category from stock_account where id = 9903",
                String.class
        )).isEqualTo("LISTING_UNDERWRITER");
        assertThat(indexNamedCount(
                jdbcTemplate,
                "stock_account",
                "idx_stock_account_status_participant_id"
        )).isEqualTo(1);
        assertThat(showCreateTable(jdbcTemplate, "stock_account"))
                .contains("chk_stock_account_participant_category");
        assertThat(showCreateTable(jdbcTemplate, "stock_order")).isEqualTo(orderDdlBefore);
        assertThat(showCreateTable(jdbcTemplate, "stock_execution")).isEqualTo(executionDdlBefore);
    }

    @Test
    void capitalIncreaseLifecycleAlter_upgradesLegacySchemaAndReapplies() throws IOException {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        resetToCanonicalSchema(dataSource, jdbcTemplate);
        downgradeCapitalIncreaseLifecycleSchema(jdbcTemplate);

        Path migration = ddlPath("stock_capital_increase_lifecycle_hardening_alter.sql");
        executeScript(dataSource, migration, false);
        executeScript(dataSource, migration, false);

        assertThat(columnCount(jdbcTemplate, "stock_corporate_action", "record_date")).isEqualTo(1);
        assertThat(columnCount(
                jdbcTemplate,
                "stock_corporate_action",
                "entitlement_close_cycle_id"
        )).isEqualTo(1);
        assertThat(columnCount(
                jdbcTemplate,
                "stock_corporate_action",
                "entitlement_close_run_id"
        )).isEqualTo(1);
        assertThat(columnCount(
                jdbcTemplate,
                "stock_corporate_action_entitlement",
                "forfeited_share_quantity"
        )).isEqualTo(1);
        assertThat(columnCount(jdbcTemplate, "stock_account_cash_flow", "corporate_action_id")).isEqualTo(1);
        assertThat(columnCount(
                jdbcTemplate,
                "stock_account_cash_flow",
                "corporate_action_entitlement_id"
        )).isEqualTo(1);
        assertThat(columnCount(
                jdbcTemplate,
                "stock_account_cash_flow",
                "effective_business_date"
        )).isEqualTo(1);
        assertThat(indexNamedCount(
                jdbcTemplate,
                "stock_corporate_action",
                "idx_stock_corporate_action_entitlement_close"
        )).isEqualTo(1);
        assertThat(indexNamedCount(
                jdbcTemplate,
                "stock_account_cash_flow",
                "idx_stock_account_cash_flow_corporate_action"
        )).isEqualTo(1);

        assertThat(showCreateTable(jdbcTemplate, "stock_corporate_action"))
                .contains(
                        "chk_stock_corporate_action_entitlement_close_pair",
                        "`record_date` > `ex_rights_date`"
                );
        assertThat(showCreateTable(jdbcTemplate, "stock_corporate_action_entitlement"))
                .contains(
                        "PARTIALLY_SUBSCRIBED",
                        "chk_stock_corporate_action_entitlement_forfeited_share",
                        "chk_stock_corporate_action_entitlement_finalized_share_limit"
                );
    }

    @Test
    void investorTypeCleanupAlter_reappliesAndPreservesDataAndHotLedgers() throws IOException {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        resetToCanonicalSchema(dataSource, jdbcTemplate);
        addObsoleteInvestorTypeColumns(jdbcTemplate);
        seedInvestorTypeCleanupSentinels(jdbcTemplate);
        String orderDdlBefore = showCreateTable(jdbcTemplate, "stock_order");
        String executionDdlBefore = showCreateTable(jdbcTemplate, "stock_execution");

        executeDelimiterScript(jdbcTemplate, ddlPath("stock_investor_type_cleanup_alter.sql"));
        executeDelimiterScript(jdbcTemplate, ddlPath("stock_investor_type_cleanup_alter.sql"));

        for (String tableName : List.of(
                "stock_account",
                "stock_auto_participant",
                "stock_close_account_snapshot",
                "stock_execution_account_day_summary",
                "stock_execution_daily_account_snapshot"
        )) {
            assertThat(columnCount(jdbcTemplate, tableName, "investor_type")).isZero();
        }
        assertThat(requiredCount(
                jdbcTemplate,
                """
                select count(*)
                  from information_schema.table_constraints
                 where table_schema = database()
                   and constraint_name in (
                       'chk_stock_account_investor_type',
                       'chk_stock_auto_participant_investor_type',
                       'chk_stock_close_account_snapshot_investor_type',
                       'chk_stock_execution_account_day_investor_type',
                       'chk_stock_execution_daily_account_investor_type'
                   )
                """
        )).isZero();
        assertThat(requiredCount(
                jdbcTemplate,
                """
                select count(*)
                  from information_schema.routines
                 where routine_schema = database()
                   and routine_name = 'stock_drop_obsolete_investor_type'
                """
        )).isZero();
        assertThat(requiredCount(jdbcTemplate, "select count(*) from stock_account where id = 9001")).isEqualTo(1);
        assertThat(requiredCount(jdbcTemplate, "select count(*) from stock_auto_participant where user_key = 'cleanup-auto'")).isEqualTo(1);
        assertThat(requiredCount(jdbcTemplate, "select count(*) from stock_close_account_snapshot where id = 9001")).isEqualTo(1);
        assertThat(requiredCount(jdbcTemplate, "select count(*) from stock_execution_account_day_summary where account_id = 9001")).isEqualTo(1);
        assertThat(requiredCount(jdbcTemplate, "select count(*) from stock_execution_daily_account_snapshot where id = 9001")).isEqualTo(1);
        assertThat(showCreateTable(jdbcTemplate, "stock_order")).isEqualTo(orderDdlBefore);
        assertThat(showCreateTable(jdbcTemplate, "stock_execution")).isEqualTo(executionDdlBefore);
        assertThat(requiredCount(jdbcTemplate, "select count(*) from stock_order where id = 9001")).isEqualTo(1);
        assertThat(requiredCount(jdbcTemplate, "select count(*) from stock_execution where id = 9001")).isEqualTo(1);
    }

    @Test
    void profilePricePressureSensitivityAlter_backfillsProfileDefaultsAndReappliesAsNoOp() throws IOException {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        resetToCanonicalSchema(dataSource, jdbcTemplate);
        jdbcTemplate.execute(
                """
                alter table stock_auto_participant_profile_config
                  drop check chk_stock_auto_profile_price_pressure_sensitivity,
                  drop column price_pressure_sensitivity
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_auto_participant_profile_config(
                    profile_type, order_multiplier, aggression_multiplier,
                    order_ttl_multiplier, quantity_multiplier,
                    holding_patience_weight, deep_loss_hold_weight, profit_taking_weight,
                    recurring_deposit_amount, recurring_deposit_interval_days,
                    recurring_deposit_interval_value, recurring_deposit_interval_unit, updated_at
                ) values
                  ('NEWS_REACTIVE', 1, 1, 1, 1, 0, 0, 0, 0, 30, 30, 'DAY', current_timestamp),
                  ('MARKET_MAKER', 1, 1, 1, 1, 0, 0, 0, 0, 30, 30, 'DAY', current_timestamp)
                """
        );

        executeScript(
                dataSource,
                ddlPath("stock_auto_participant_profile_price_pressure_sensitivity_alter.sql"),
                false
        );
        executeScript(
                dataSource,
                ddlPath("stock_auto_participant_profile_price_pressure_sensitivity_alter.sql"),
                false
        );

        assertThat(jdbcTemplate.queryForObject(
                "select price_pressure_sensitivity from stock_auto_participant_profile_config where profile_type = 'NEWS_REACTIVE'",
                BigDecimal.class
        )).isEqualByComparingTo("1.3000");
        assertThat(jdbcTemplate.queryForObject(
                "select price_pressure_sensitivity from stock_auto_participant_profile_config where profile_type = 'MARKET_MAKER'",
                BigDecimal.class
        )).isEqualByComparingTo("0.3000");
        assertThat(columnCount(
                jdbcTemplate,
                "stock_auto_participant_profile_config",
                "price_pressure_sensitivity"
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*)
                  from information_schema.table_constraints
                 where constraint_schema = database()
                   and table_name = 'stock_auto_participant_profile_config'
                   and constraint_name = 'chk_stock_auto_profile_price_pressure_sensitivity'
                """,
                Integer.class
        )).isEqualTo(1);
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
    void portfolioPostCloseCashDataFix_rewritesOnlyReconciledSnapshotsAndReappliesAsNoOp()
            throws IOException {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        resetToCanonicalSchema(dataSource, jdbcTemplate);
        jdbcTemplate.execute(
                """
                alter table portfolio_snapshot
                  drop check chk_portfolio_snapshot_pending_subscription_non_negative,
                  drop check chk_portfolio_snapshot_asset_composition,
                  drop column pending_subscription_asset
                """
        );

        jdbcTemplate.update(
                """
                insert into stock_close_account_snapshot(
                    close_cycle_id, close_run_id, account_id, user_key, account_status,
                    settlement_target, pre_cancel_cash, pre_cancel_order_reserved_cash,
                    subscription_reserved_cash, post_cancel_cash, external_net_cash_flow,
                    holding_market_value, holding_quantity, reserved_sell_quantity,
                    holding_position_count, reconciliation_status, snapshot_at, created_at
                ) values (
                    9001, 9002, 9101, 'data-fix-v2', 'ACTIVE', true,
                    600.00, 150.00, 50.00, 750.00, 500.00,
                    200.00, 10, 2, 1, 'MATCHED',
                    '2026-07-19 18:00:00', '2026-07-19 18:00:00'
                )
                """
        );
        jdbcTemplate.update(
                """
                insert into portfolio_snapshot(
                    close_cycle_id, close_run_id, account_id, snapshot_date,
                    total_asset, cash_balance, market_value,
                    holding_quantity, reserved_sell_quantity, holding_position_count,
                    return_rate, input_hash, calculation_version, data_quality_status, created_at
                ) values (
                    9001, 9002, 9101, '2026-07-19',
                    1000.00, 600.00, 200.00, 10, 2, 1,
                    100.0000, repeat('a', 64), 'portfolio-v2-frozen-close', 'VERIFIED',
                    '2026-07-19 18:00:00'
                )
                """
        );
        jdbcTemplate.update(
                """
                insert into portfolio_snapshot(
                    account_id, snapshot_date, total_asset, cash_balance, market_value,
                    return_rate, created_at
                ) values (
                    9103, '2026-07-18', 1500.00, 1000.00, 300.00,
                    50.0000, '2026-07-18 18:00:00'
                )
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_corporate_action_entitlement(
                    action_id, account_id, symbol, quantity, share_quantity,
                    subscribed_share_quantity, subscribed_cash_amount, status,
                    created_at, subscribed_at, paid_at
                ) values (
                    9901, 9103, 'DATAFIX', 1, 1,
                    1, 50.00, 'PAID',
                    '2026-07-17 09:00:00', '2026-07-17 09:00:00', '2026-07-19 09:00:00'
                )
                """
        );
        jdbcTemplate.update(
                """
                insert into portfolio_snapshot(
                    account_id, snapshot_date, total_asset, cash_balance, market_value,
                    return_rate, created_at
                ) values (
                    9102, '2026-07-18', 1500.00, 1000.00, 300.00,
                    50.0000, '2026-07-18 18:00:00'
                )
                """
        );

        executeScript(
                dataSource,
                ddlPath("stock_portfolio_snapshot_post_close_cash_data_fix.sql"),
                false
        );
        executeScript(
                dataSource,
                ddlPath("stock_portfolio_snapshot_post_close_cash_data_fix.sql"),
                false
        );

        String expectedV4Hash = new PortfolioSnapshotProcessor().inputHash(
                new AccountSettlementTarget(
                        9001L,
                        9002L,
                        9101L,
                        "data-fix-v2",
                        new BigDecimal("750.00"),
                        new BigDecimal("500.00"),
                        new BigDecimal("200.00"),
                        new BigDecimal("50.00"),
                        10L,
                        2L,
                        1L
                )
        );
        List<PortfolioCorrectionRow> corrected = jdbcTemplate.query(
                """
                select account_id, cash_balance, pending_subscription_asset,
                       input_hash, calculation_version, data_quality_status
                  from portfolio_snapshot
                 where account_id in (9101, 9102, 9103)
                 order by account_id
                """,
                (resultSet, rowNumber) -> new PortfolioCorrectionRow(
                        resultSet.getLong("account_id"),
                        resultSet.getBigDecimal("cash_balance"),
                        resultSet.getBigDecimal("pending_subscription_asset"),
                        resultSet.getString("input_hash"),
                        resultSet.getString("calculation_version"),
                        resultSet.getString("data_quality_status")
                )
        );

        assertThat(corrected).containsExactly(
                new PortfolioCorrectionRow(
                        9101L,
                        new BigDecimal("750.00"),
                        new BigDecimal("50.00"),
                        expectedV4Hash,
                        "portfolio-v4-explicit-subscription-asset",
                        "VERIFIED"
                ),
                new PortfolioCorrectionRow(
                        9102L,
                        new BigDecimal("1200.00"),
                        new BigDecimal("0.00"),
                        null,
                        "portfolio-v1-explicit-asset-backfill",
                        "WARNING"
                ),
                new PortfolioCorrectionRow(
                        9103L,
                        new BigDecimal("1150.00"),
                        new BigDecimal("50.00"),
                        null,
                        "portfolio-v1-explicit-asset-backfill",
                        "WARNING"
                )
        );
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
                  drop check chk_portfolio_snapshot_pending_subscription_non_negative,
                  drop check chk_portfolio_snapshot_asset_composition,
                  drop check chk_portfolio_snapshot_input_hash,
                  drop check chk_portfolio_snapshot_data_quality,
                  drop index uk_portfolio_snapshot_cycle_account,
                  drop index idx_portfolio_snapshot_close_run,
                  drop column source_build_version,
                  drop column data_quality_status,
                  drop column calculation_version,
                  drop column input_hash,
                  drop column close_run_id,
                  drop column close_cycle_id,
                  drop column pending_subscription_asset
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

    private void downgradeCapitalIncreaseLifecycleSchema(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute(
                """
                alter table stock_corporate_action
                  drop check chk_stock_corporate_action_paid_date_order,
                  drop check chk_stock_corporate_action_paid_schedule_required,
                  drop check chk_stock_corporate_action_field_scope,
                  drop check chk_stock_corporate_action_entitlement_close_pair,
                  drop index idx_stock_corporate_action_entitlement_close,
                  drop column entitlement_close_run_id,
                  drop column entitlement_close_cycle_id,
                  drop column record_date
                """
        );
        jdbcTemplate.execute(
                """
                alter table stock_corporate_action_entitlement
                  drop check chk_stock_corporate_action_entitlement_subscription_complete,
                  drop check chk_stock_corporate_action_entitlement_status,
                  drop check chk_stock_corporate_action_entitlement_forfeited_share,
                  drop check chk_stock_corporate_action_entitlement_finalized_share_limit,
                  drop column forfeited_share_quantity
                """
        );
        jdbcTemplate.execute(
                """
                alter table stock_account_cash_flow
                  drop index idx_stock_account_cash_flow_corporate_action,
                  drop column effective_business_date,
                  drop column corporate_action_entitlement_id,
                  drop column corporate_action_id
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

    private void addObsoleteInvestorTypeColumns(JdbcTemplate jdbcTemplate) {
        addObsoleteInvestorTypeColumn(jdbcTemplate, "stock_account", "chk_stock_account_investor_type");
        addObsoleteInvestorTypeColumn(
                jdbcTemplate,
                "stock_auto_participant",
                "chk_stock_auto_participant_investor_type"
        );
        addObsoleteInvestorTypeColumn(
                jdbcTemplate,
                "stock_close_account_snapshot",
                "chk_stock_close_account_snapshot_investor_type"
        );
        addObsoleteInvestorTypeColumn(
                jdbcTemplate,
                "stock_execution_account_day_summary",
                "chk_stock_execution_account_day_investor_type"
        );
        addObsoleteInvestorTypeColumn(
                jdbcTemplate,
                "stock_execution_daily_account_snapshot",
                "chk_stock_execution_daily_account_investor_type"
        );
    }

    private void addObsoleteInvestorTypeColumn(
            JdbcTemplate jdbcTemplate,
            String tableName,
            String constraintName
    ) {
        jdbcTemplate.execute(
                "alter table `%s` add column investor_type varchar(30) not null default 'INDIVIDUAL', "
                        .formatted(tableName)
                        + "add constraint `%s` check (investor_type in ('INDIVIDUAL', 'FOREIGN', 'INSTITUTION', 'OTHER_CORPORATION'))"
                        .formatted(constraintName)
        );
    }

    private void seedInvestorTypeCleanupSentinels(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                """
                insert into stock_account(id, user_key, status, cash_balance, created_at, updated_at)
                values (9001, 'cleanup-user', 'ACTIVE', 1000000, '2026-07-21 00:00:00', '2026-07-21 00:00:00')
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_auto_participant(
                    user_key, display_name, enabled, profile_type, created_at, updated_at
                )
                values ('cleanup-auto', 'cleanup auto', true, 'NOISE_TRADER',
                        '2026-07-21 00:00:00', '2026-07-21 00:00:00')
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_close_account_snapshot(
                    id, close_cycle_id, close_run_id, account_id, user_key, account_status,
                    participant_category, settlement_target, pre_cancel_cash,
                    snapshot_at, created_at
                )
                values (9001, 9001, 9001, 9001, 'cleanup-user', 'ACTIVE',
                        'MANUAL_PARTICIPANT', true, 1000000,
                        '2026-07-21 18:00:00', '2026-07-21 18:00:00')
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_execution_account_day_summary(
                    simulation_trade_date, account_id, updated_at
                )
                values ('2026-07-21', 9001, '2026-07-21 18:00:00')
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_execution_daily_account_snapshot(
                    id, close_run_id, symbol, simulation_trade_date, account_id,
                    participant_category, created_at
                )
                values (9001, 9001, 'CLEANUP', '2026-07-21', 9001,
                        'MANUAL_PARTICIPANT', '2026-07-21 18:00:00')
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_order(
                    id, client_order_id, account_id, symbol, market_type, side, order_type,
                    status, limit_price, quantity, filled_quantity, average_fill_price,
                    reserved_cash, created_at, updated_at
                )
                values (9001, 'cleanup-order', 9001, 'CLEANUP', 'ORDER_BOOK', 'BUY', 'LIMIT',
                        'FILLED', 100, 1, 1, 100, 0,
                        '2026-07-21 12:00:00', '2026-07-21 12:00:00')
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_execution(
                    id, order_id, account_id, symbol, side, quantity, price,
                    gross_amount, fee_amount, tax_amount, net_amount, source, executed_at
                )
                values (9001, 9001, 9001, 'CLEANUP', 'BUY', 1, 100,
                        100, 0, 0, 100, 'INTERNAL_ORDER_BOOK', '2026-07-21 12:00:00')
                """
        );
    }

    private void executeDelimiterScript(JdbcTemplate jdbcTemplate, Path scriptPath) throws IOException {
        String delimiter = ";";
        StringBuilder statement = new StringBuilder();
        for (String line : Files.readAllLines(scriptPath, StandardCharsets.UTF_8)) {
            String trimmedLine = line.trim();
            if (trimmedLine.startsWith("DELIMITER ")) {
                delimiter = trimmedLine.substring("DELIMITER ".length()).trim();
                continue;
            }
            statement.append(line).append('\n');
            if (!trimmedLine.endsWith(delimiter)) {
                continue;
            }
            String sql = statement.toString().trim();
            sql = sql.substring(0, sql.length() - delimiter.length()).trim();
            if (!sql.isBlank()) {
                jdbcTemplate.execute(sql);
            }
            statement.setLength(0);
        }
        assertThat(statement.toString().trim()).isEmpty();
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

    private record PortfolioCorrectionRow(
            long accountId,
            BigDecimal cashBalance,
            BigDecimal pendingSubscriptionAsset,
            String inputHash,
            String calculationVersion,
            String dataQualityStatus
    ) {
    }
}
