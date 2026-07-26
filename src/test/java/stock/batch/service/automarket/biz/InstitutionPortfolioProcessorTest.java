package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.batch.automarket.model.AutoMarketPressure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstitutionPortfolioProcessorTest {

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private InstitutionPortfolioProcessor processor;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:institution_live_" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                "",
                true
        );
        new ResourceDatabasePopulator(new ClassPathResource("db/ddl/stock_h2.sql")).execute(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        InstitutionPortfolioRepository repository =
                new InstitutionPortfolioRepository(jdbcTemplate);
        processor = new InstitutionPortfolioProcessor(
                repository,
                new InstitutionPortfolioPlanner()
        );
        seedPortfolio();
    }

    @Test
    void process_liveMode_writesAuditBudgetAndPendingIntentWithoutDirectOrderWrite() {
        LocalDateTime now = LocalDateTime.of(2027, 1, 27, 9, 0);
        InstitutionPortfolioProcessor.ProcessResult result = transactionTemplate.execute(
                status -> processor.process(700L, now, Map.of("DEMO001", marketInput()))
        );

        assertThat(result).isEqualTo(InstitutionPortfolioProcessor.ProcessResult.COMPLETED);
        assertThat(jdbcTemplate.queryForObject(
                "select status from stock_institution_decision_run where portfolio_id = 700",
                String.class
        )).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                "select action from stock_institution_decision_item",
                String.class
        )).isEqualTo("BUY");
        assertThat(jdbcTemplate.queryForObject(
                "select gated_quantity from stock_institution_decision_item",
                Long.class
        )).isEqualTo(20L);
        assertThat(jdbcTemplate.queryForObject(
                "select planned_buy_quantity from stock_institution_daily_budget",
                Long.class
        )).isEqualTo(20L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_institution_order_intent "
                        + "where portfolio_id = 700 and status = 'PENDING'",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_order where account_id = 700",
                Integer.class
        )).isZero();
    }

    @Test
    void process_sameDecisionWindow_isIdempotentlySkipped() {
        LocalDateTime now = LocalDateTime.of(2027, 1, 27, 9, 0);
        transactionTemplate.execute(status -> processor.process(
                700L,
                now,
                Map.of("DEMO001", marketInput())
        ));
        InstitutionPortfolioProcessor.ProcessResult second = transactionTemplate.execute(
                status -> processor.process(
                        700L,
                        now.plusMinutes(30),
                        Map.of("DEMO001", marketInput())
                )
        );

        assertThat(second).isEqualTo(InstitutionPortfolioProcessor.ProcessResult.SKIPPED);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_institution_decision_run where portfolio_id = 700",
                Integer.class
        )).isEqualTo(1);
    }

    @Test
    void process_liveMultiSymbolPortfolio_createsOneIntentPerActionableSymbol() {
        LocalDateTime now = LocalDateTime.of(2027, 1, 27, 8, 30);
        jdbcTemplate.update(
                """
                insert into stock_institution_symbol_mandate(
                    id, portfolio_id, symbol, base_symbol_weight,
                    min_portfolio_allocation_rate, max_portfolio_allocation_rate,
                    price_pressure_sensitivity, momentum_sensitivity,
                    value_sensitivity, report_sensitivity,
                    reference_daily_volume, daily_participation_rate,
                    enabled, created_at, updated_at
                ) values (
                    701, 700, 'DEMO002', 1.000000, 0.000000, 1.000000,
                    0.100000, 0.100000, 0.100000, 0.100000,
                    10000, 0.020000, true, ?, ?
                )
                """,
                now,
                now
        );

        InstitutionPortfolioProcessor.ProcessResult result = transactionTemplate.execute(
                status -> processor.process(
                        700L,
                        LocalDateTime.of(2027, 1, 27, 9, 0),
                        Map.of(
                                "DEMO001",
                                marketInput(),
                                "DEMO002",
                                new InstitutionMarketInput(
                                        "DEMO002",
                                        new BigDecimal("200.00"),
                                        AutoMarketPressure.NEUTRAL,
                                        AutoMarketPressure.NEUTRAL,
                                        0.0,
                                        0.0,
                                        0.0
                                )
                        )
                )
        );

        assertThat(result).isEqualTo(InstitutionPortfolioProcessor.ProcessResult.COMPLETED);
        assertThat(jdbcTemplate.queryForList(
                """
                select symbol
                  from stock_institution_order_intent
                 where portfolio_id = 700
                   and status = 'PENDING'
                 order by symbol
                """,
                String.class
        )).containsExactly("DEMO001", "DEMO002");
    }

    @Test
    void process_missingMarketConfig_recordsFailedRunWithoutBudgetMutation() {
        LocalDateTime now = LocalDateTime.of(2027, 1, 27, 9, 0);
        InstitutionPortfolioProcessor.ProcessResult result = transactionTemplate.execute(
                status -> processor.process(700L, now, Map.of())
        );

        assertThat(result).isEqualTo(InstitutionPortfolioProcessor.ProcessResult.FAILED);
        assertThat(jdbcTemplate.queryForObject(
                "select status from stock_institution_decision_run where portfolio_id = 700",
                String.class
        )).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_institution_daily_budget",
                Integer.class
        )).isZero();
    }

    @Test
    void process_openOrderWithoutInstitutionOrigin_failsClosedBeforeBudgetMutation() {
        LocalDateTime now = LocalDateTime.of(2027, 1, 27, 8, 30);
        jdbcTemplate.update(
                """
                insert into stock_order(
                    client_order_id, account_id, origin_type, self_trade_group_id,
                    symbol, market_type, side, order_type, status, limit_price,
                    quantity, filled_quantity, reserved_cash, created_at, updated_at
                ) values (
                    'rogue-institution-order', 700, null, 'INSTITUTION:PENSION',
                    'DEMO001', 'ORDER_BOOK', 'BUY', 'LIMIT', 'PENDING', 100,
                    1, 0, 100, ?, ?
                )
                """,
                now,
                now
        );

        InstitutionPortfolioProcessor.ProcessResult result = transactionTemplate.execute(
                status -> processor.process(
                        700L,
                        LocalDateTime.of(2027, 1, 27, 9, 0),
                        Map.of("DEMO001", marketInput())
                )
        );

        assertThat(result).isEqualTo(InstitutionPortfolioProcessor.ProcessResult.FAILED);
        assertThat(jdbcTemplate.queryForObject(
                """
                select error_message
                  from stock_institution_decision_run
                 where portfolio_id = 700
                   and status = 'FAILED'
                """,
                String.class
        )).contains("outside its portfolio origin");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_institution_daily_budget",
                Integer.class
        )).isZero();
    }

    @Test
    void process_openOrderFromStaleInstitutionPolicy_failsClosedBeforeBudgetMutation() {
        LocalDateTime now = LocalDateTime.of(2027, 1, 27, 8, 30);
        jdbcTemplate.update(
                """
                insert into stock_institution_decision_run(
                    id, decision_slot, simulation_trade_date, portfolio_id,
                    execution_mode, policy_version, deterministic_seed,
                    status, created_at, completed_at
                ) values (
                    899, ?, date '2027-01-27', 700,
                    'LIVE', 2, 899,
                    'COMPLETED', ?, ?
                )
                """,
                now.minusMinutes(30),
                now,
                now
        );
        jdbcTemplate.update(
                """
                insert into stock_order(
                    id, client_order_id, account_id, origin_type, self_trade_group_id,
                    symbol, market_type, side, order_type, status, limit_price,
                    quantity, filled_quantity, reserved_cash, created_at, updated_at
                ) values (
                    900, 'stale-institution-order', 700, 'INSTITUTIONAL_INVESTOR',
                    'INSTITUTION:PENSION', 'DEMO001', 'ORDER_BOOK', 'BUY', 'LIMIT',
                    'PENDING', 100, 1, 0, 100, ?, ?
                )
                """,
                now,
                now
        );
        jdbcTemplate.update(
                """
                insert into stock_order_strategy_origin(
                    order_id, origin_type, participant_id,
                    portfolio_id, decision_run_id, policy_version, created_at
                ) values (
                    900, 'INSTITUTIONAL_INVESTOR', 700,
                    700, 899, 2, ?
                )
                """,
                now
        );

        InstitutionPortfolioProcessor.ProcessResult result = transactionTemplate.execute(
                status -> processor.process(
                        700L,
                        LocalDateTime.of(2027, 1, 27, 9, 0),
                        Map.of("DEMO001", marketInput())
                )
        );

        assertThat(result).isEqualTo(InstitutionPortfolioProcessor.ProcessResult.FAILED);
        assertThat(jdbcTemplate.queryForObject(
                """
                select error_message
                  from stock_institution_decision_run
                 where portfolio_id = 700
                   and status = 'FAILED'
                """,
                String.class
        )).contains("outside its portfolio origin");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_institution_daily_budget",
                Integer.class
        )).isZero();
    }

    @Test
    void process_missingEffectivePolicyVersion_rollsBackBeforeDecisionAudit() {
        jdbcTemplate.update(
                """
                delete from stock_market_policy_version
                 where policy_scope = 'INSTITUTIONAL_PORTFOLIO'
                   and scope_key = 'PENSION'
                """
        );

        assertThatThrownBy(() -> transactionTemplate.execute(status ->
                processor.process(
                        700L,
                        LocalDateTime.of(2027, 1, 27, 9, 0),
                        Map.of("DEMO001", marketInput())
                )
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one effective active policy version");

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_institution_decision_run where portfolio_id = 700",
                Integer.class
        )).isZero();
    }

    private void seedPortfolio() {
        LocalDateTime now = LocalDateTime.of(2027, 1, 27, 8, 0);
        jdbcTemplate.update(
                """
                insert into stock_account(
                    id, user_key, account_code, status, participant_category,
                    self_trade_group_id, cash_balance, created_at, updated_at
                ) values (700, 'institution-pension', 'INST-PENSION', 'ACTIVE',
                          'INSTITUTIONAL_INVESTOR', 'INSTITUTION:PENSION',
                          1000000.00, ?, ?)
                """,
                now,
                now
        );
        jdbcTemplate.update(
                """
                insert into stock_market_participant(
                    id, participant_code, display_name, participant_type,
                    status, self_trade_group_id, created_at, updated_at
                ) values (700, 'INSTITUTION_PENSION', 'Pension',
                          'INSTITUTIONAL_INVESTOR', 'ACTIVE',
                          'INSTITUTION:PENSION', ?, ?)
                """,
                now,
                now
        );
        jdbcTemplate.update(
                """
                insert into stock_market_participant_account(
                    id, participant_id, account_id, account_role, desk_code,
                    effective_from, effective_to, status, created_at, updated_at
                ) values (700, 700, 700, 'INSTITUTIONAL_INVESTOR', 'BALANCED',
                          date '2027-01-01', null, 'ACTIVE', ?, ?)
                """,
                now,
                now
        );
        jdbcTemplate.update(
                """
                insert into stock_institution_portfolio(
                    id, participant_id, account_id, portfolio_code, display_name,
                    investment_style, execution_mode, status,
                    base_stock_allocation_rate, min_stock_allocation_rate,
                    max_stock_allocation_rate, primary_regime_weight,
                    asset_preference_sensitivity, volatility_sensitivity,
                    entry_threshold_rate, exit_threshold_rate,
                    daily_turnover_limit_rate, max_decision_turnover_rate,
                    decision_interval_minutes, next_decision_at, policy_version,
                    created_at, updated_at
                ) values (
                    700, 700, 700, 'PENSION', 'Pension', 'BALANCED_LONG_TERM',
                    'LIVE', 'ACTIVE', 0.600000, 0.300000, 0.800000, 0.700000,
                    0.020000, 0.020000, 0.005000, 0.002000,
                    0.010000, 0.002000, 60, null, 1, ?, ?
                )
                """,
                now,
                now
        );
        jdbcTemplate.update(
                """
                insert into stock_institution_symbol_mandate(
                    id, portfolio_id, symbol, base_symbol_weight,
                    min_portfolio_allocation_rate, max_portfolio_allocation_rate,
                    price_pressure_sensitivity, momentum_sensitivity,
                    value_sensitivity, report_sensitivity,
                    reference_daily_volume, daily_participation_rate,
                    enabled, created_at, updated_at
                ) values (
                    700, 700, 'DEMO001', 1.000000, 0.000000, 1.000000,
                    0.100000, 0.100000, 0.100000, 0.100000,
                    10000, 0.020000, true, ?, ?
                )
                """,
                now,
                now
        );
        jdbcTemplate.update(
                """
                insert into stock_market_policy_version(
                    policy_scope, scope_key, version_no,
                    effective_business_date, status, config_json,
                    change_reason, changed_by, created_at, updated_at
                ) values (
                    'INSTITUTIONAL_PORTFOLIO', 'PENSION', 1,
                    date '2027-01-01', 'ACTIVE', '{}',
                    'test policy', 'test', ?, ?
                )
                """,
                now,
                now
        );
    }

    private InstitutionMarketInput marketInput() {
        return new InstitutionMarketInput(
                "DEMO001",
                new BigDecimal("100.00"),
                AutoMarketPressure.NEUTRAL,
                AutoMarketPressure.NEUTRAL,
                0.0,
                0.0,
                0.0
        );
    }
}
