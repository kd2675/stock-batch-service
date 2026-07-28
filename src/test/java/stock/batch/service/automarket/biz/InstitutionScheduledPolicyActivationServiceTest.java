package stock.batch.service.automarket.biz;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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

import stock.batch.service.simulation.SimulationMarketSessionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InstitutionScheduledPolicyActivationServiceTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2027, 2, 1);
    private static final LocalDateTime ACTIVATED_AT = BUSINESS_DATE.atTime(5, 30);

    private JdbcTemplate jdbcTemplate;
    private InstitutionScheduledPolicyActivationService service;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:institution_policy_activation_" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                "",
                true
        );
        new ResourceDatabasePopulator(new ClassPathResource("db/ddl/stock_h2.sql"))
                .execute(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);
        SimulationMarketSessionService marketSessionService =
                mock(SimulationMarketSessionService.class);
        when(marketSessionService.openTime()).thenReturn(LocalTime.of(6, 0));
        service = new InstitutionScheduledPolicyActivationService(
                jdbcTemplate,
                new ObjectMapper(),
                marketSessionService,
                new DataSourceTransactionManager(dataSource)
        );
        seedPortfolioAndPolicies();
    }

    @Test
    void activateDuePolicies_newSymbolAndRemovedHolding_appliesAndStartsLiquidation() {
        int activated = service.activateDuePolicies(BUSINESS_DATE, ACTIVATED_AT);

        assertThat(activated).isOne();
        assertThat(jdbcTemplate.queryForMap(
                """
                select display_name, investment_style,
                       base_stock_allocation_rate, daily_turnover_limit_rate,
                       decision_interval_minutes, next_decision_at, policy_version
                  from stock_institution_portfolio
                 where id = 1
                """
        )).containsEntry("display_name", "기관 성장형")
                .containsEntry("investment_style", "MOMENTUM")
                .containsEntry("base_stock_allocation_rate", new BigDecimal("0.600000"))
                .containsEntry("daily_turnover_limit_rate", new BigDecimal("0.015000"))
                .containsEntry("decision_interval_minutes", 60)
                .containsEntry(
                        "next_decision_at",
                        java.sql.Timestamp.valueOf(BUSINESS_DATE.atTime(6, 0))
                )
                .containsEntry("policy_version", 2L);
        assertThat(jdbcTemplate.queryForObject(
                "select display_name from stock_market_participant where id = 10",
                String.class
        )).isEqualTo("기관 성장형");
        assertThat(jdbcTemplate.query(
                """
                select symbol, base_symbol_weight, max_portfolio_allocation_rate,
                       reference_daily_volume, daily_participation_rate, enabled
                  from stock_institution_symbol_mandate
                 where portfolio_id = 1
                 order by symbol
                """,
                (rs, rowNum) -> Map.of(
                        "symbol", rs.getString("symbol"),
                        "baseWeight", rs.getBigDecimal("base_symbol_weight"),
                        "maxAllocation", rs.getBigDecimal("max_portfolio_allocation_rate"),
                        "referenceVolume", rs.getLong("reference_daily_volume"),
                        "participation", rs.getBigDecimal("daily_participation_rate"),
                        "enabled", rs.getBoolean("enabled")
                )
        )).containsExactly(
                Map.of(
                        "symbol", "NEW001",
                        "baseWeight", new BigDecimal("1.000000"),
                        "maxAllocation", new BigDecimal("0.850000"),
                        "referenceVolume", 30_000L,
                        "participation", new BigDecimal("0.020000"),
                        "enabled", true
                ),
                Map.of(
                        "symbol", "OLD001",
                        "baseWeight", new BigDecimal("0.000001"),
                        "maxAllocation", new BigDecimal("0.000000"),
                        "referenceVolume", 20_000L,
                        "participation", new BigDecimal("0.010000"),
                        "enabled", true
                )
        );
        assertThat(jdbcTemplate.query(
                """
                select version_no, status
                  from stock_market_policy_version
                 where policy_scope = 'INSTITUTIONAL_PORTFOLIO'
                   and scope_key = 'INSTITUTION_1'
                 order by version_no
                """,
                (rs, rowNum) -> Map.of(
                        "version", rs.getLong("version_no"),
                        "status", rs.getString("status")
                )
        )).containsExactly(
                Map.of("version", 1L, "status", "RETIRED"),
                Map.of("version", 2L, "status", "ACTIVE")
        );
    }

    @Test
    void activateDuePolicies_openOrderExists_rollsBackPolicy() {
        jdbcTemplate.update(
                """
                insert into stock_order(
                    client_order_id, account_id, origin_type, self_trade_group_id,
                    symbol, market_type, side, order_type, status,
                    limit_price, quantity, filled_quantity, reserved_cash,
                    created_at, updated_at
                ) values (
                    'OPEN-1', 200, 'INSTITUTIONAL_INVESTOR', 'INSTITUTION:1',
                    'OLD001', 'ORDER_BOOK', 'SELL', 'LIMIT', 'PENDING',
                    1000.00, 1, 0, 0.00, ?, ?
                )
                """,
                ACTIVATED_AT.minusMinutes(1),
                ACTIVATED_AT.minusMinutes(1)
        );

        assertThatThrownBy(() ->
                service.activateDuePolicies(BUSINESS_DATE, ACTIVATED_AT)
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("open orders remain");

        assertThat(jdbcTemplate.queryForObject(
                "select policy_version from stock_institution_portfolio where id = 1",
                Long.class
        )).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                """
                select status
                  from stock_market_policy_version
                 where policy_scope = 'INSTITUTIONAL_PORTFOLIO'
                   and scope_key = 'INSTITUTION_1'
                   and version_no = 2
                """,
                String.class
        )).isEqualTo("SCHEDULED");
    }

    @Test
    void activateDuePolicies_invalidRiskValue_rollsBackPolicy() {
        jdbcTemplate.update(
                """
                update stock_market_policy_version
                   set config_json = ?
                 where policy_scope = 'INSTITUTIONAL_PORTFOLIO'
                   and scope_key = 'INSTITUTION_1'
                   and version_no = 2
                """,
                scheduledPolicyJson().replace(
                        "\"dailyParticipationRate\":0.02",
                        "\"dailyParticipationRate\":0.50"
                )
        );

        assertThatThrownBy(() ->
                service.activateDuePolicies(BUSINESS_DATE, ACTIVATED_AT)
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside the policy contract");

        assertThat(jdbcTemplate.queryForObject(
                "select policy_version from stock_institution_portfolio where id = 1",
                Long.class
        )).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                """
                select status
                  from stock_market_policy_version
                 where policy_scope = 'INSTITUTIONAL_PORTFOLIO'
                   and scope_key = 'INSTITUTION_1'
                   and version_no = 2
                """,
                String.class
        )).isEqualTo("SCHEDULED");
    }

    @Test
    void completedLiquidationMandate_withoutHoldingOrOrder_isDisabled() {
        service.activateDuePolicies(BUSINESS_DATE, ACTIVATED_AT);
        jdbcTemplate.update(
                "delete from stock_holding where account_id = 200 and symbol = 'OLD001'"
        );

        new InstitutionPortfolioRepository(jdbcTemplate)
                .disableCompletedLiquidationMandates(1L, 200L, ACTIVATED_AT.plusMinutes(1));

        assertThat(jdbcTemplate.queryForObject(
                """
                select enabled
                  from stock_institution_symbol_mandate
                 where portfolio_id = 1
                   and symbol = 'OLD001'
                """,
                Boolean.class
        )).isFalse();
    }

    private void seedPortfolioAndPolicies() {
        jdbcTemplate.update(
                """
                insert into stock_market_participant(
                    id, participant_code, display_name, participant_type,
                    status, self_trade_group_id, created_at, updated_at
                ) values (
                    10, 'INSTITUTION_INSTITUTION_1', '기관 균형형',
                    'INSTITUTIONAL_INVESTOR', 'ACTIVE', 'INSTITUTION:1', ?, ?
                )
                """,
                BUSINESS_DATE.minusDays(1).atStartOfDay(),
                BUSINESS_DATE.minusDays(1).atStartOfDay()
        );
        jdbcTemplate.update(
                """
                insert into stock_account(
                    id, user_key, account_code, status, participant_category,
                    self_trade_group_id, cash_balance, created_at, updated_at
                ) values (
                    200, 'stock-institution-institution-1', 'INST-INSTITUTION_1',
                    'ACTIVE', 'INSTITUTIONAL_INVESTOR', 'INSTITUTION:1',
                    1000000.00, ?, ?
                )
                """,
                BUSINESS_DATE.minusDays(1).atStartOfDay(),
                BUSINESS_DATE.minusDays(1).atStartOfDay()
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
                    1, 10, 200, 'INSTITUTION_1', '기관 균형형',
                    'BALANCED_LONG_TERM', 'LIVE', 'ACTIVE',
                    0.600000, 0.500000, 0.700000, 0.800000,
                    0.015000, 0.020000, 0.005000, 0.002000,
                    0.005000, 0.001000, 120, null, 1, ?, ?
                )
                """,
                BUSINESS_DATE.minusDays(1).atStartOfDay(),
                BUSINESS_DATE.minusDays(1).atStartOfDay()
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
                    1, 1, 'OLD001', 1.000000, 0, 0.700000,
                    0.020000, 0.020000, 0.020000, 0.020000,
                    20000, 0.010000, true, ?, ?
                )
                """,
                BUSINESS_DATE.minusDays(1).atStartOfDay(),
                BUSINESS_DATE.minusDays(1).atStartOfDay()
        );
        jdbcTemplate.update(
                """
                insert into stock_holding(
                    account_id, symbol, quantity, reserved_quantity,
                    average_price, updated_at
                ) values (200, 'OLD001', 100, 0, 1000.00, ?)
                """,
                BUSINESS_DATE.minusDays(1).atStartOfDay()
        );
        jdbcTemplate.update(
                """
                insert into stock_order_book_instrument(
                    symbol, name, market, initial_price, issued_shares,
                    tradable_shares, tick_size, price_limit_rate,
                    enabled, created_at, updated_at
                ) values (
                    'NEW001', '신규 종목', 'ORDERBOOK', 2000.00, 2000000,
                    1000000, 1.00, 30.00, true, ?, ?
                )
                """,
                BUSINESS_DATE.minusDays(1).atStartOfDay(),
                BUSINESS_DATE.minusDays(1).atStartOfDay()
        );
        jdbcTemplate.update(
                """
                insert into stock_order_book_market_config(
                    symbol, enabled, market_status, updated_at
                ) values ('NEW001', true, 'CLOSED', ?)
                """,
                BUSINESS_DATE.minusDays(1).atStartOfDay()
        );
        jdbcTemplate.update(
                """
                insert into stock_price(
                    symbol, current_price, previous_close, price_time, provider
                ) values ('NEW001', 2000.00, 1900.00, ?, 'TEST')
                """,
                BUSINESS_DATE.minusDays(1).atStartOfDay()
        );
        jdbcTemplate.update(
                """
                insert into stock_market_policy_version(
                    policy_scope, scope_key, version_no,
                    effective_business_date, status, config_json,
                    change_reason, changed_by, created_at, updated_at
                ) values (
                    'INSTITUTIONAL_PORTFOLIO', 'INSTITUTION_1', 1, date '2027-01-31',
                    'ACTIVE', '{}', '현재 정책', 'admin-test', ?, ?
                )
                """,
                BUSINESS_DATE.minusDays(1).atStartOfDay(),
                BUSINESS_DATE.minusDays(1).atStartOfDay()
        );
        jdbcTemplate.update(
                """
                insert into stock_market_policy_version(
                    policy_scope, scope_key, version_no,
                    effective_business_date, status, config_json,
                    change_reason, changed_by, created_at, updated_at
                ) values (
                    'INSTITUTIONAL_PORTFOLIO', 'INSTITUTION_1', 2, ?,
                    'SCHEDULED', ?, '성장형 전환', 'stock-admin', ?, ?
                )
                """,
                BUSINESS_DATE,
                scheduledPolicyJson(),
                BUSINESS_DATE.minusDays(1).atTime(12, 0),
                BUSINESS_DATE.minusDays(1).atTime(12, 0)
        );
    }

    private String scheduledPolicyJson() {
        return """
                {
                  "preset":"INDEPENDENT_INSTITUTION_PORTFOLIO_V2",
                  "portfolioCode":"INSTITUTION_1",
                  "executionMode":"LIVE",
                  "displayName":"기관 성장형",
                  "investmentStyle":"MOMENTUM",
                  "baseStockAllocationRate":0.60,
                  "minStockAllocationRate":0.35,
                  "maxStockAllocationRate":0.85,
                  "primaryRegimeWeight":0.60,
                  "assetPreferenceSensitivity":0.025,
                  "volatilitySensitivity":0.025,
                  "entryThresholdRate":0.004,
                  "exitThresholdRate":0.0015,
                  "dailyTurnoverLimitRate":0.015,
                  "maxDecisionTurnoverRate":0.003,
                  "decisionIntervalMinutes":60,
                  "mandates":[{
                    "symbol":"NEW001",
                    "baseSymbolWeight":1.0,
                    "minPortfolioAllocationRate":0,
                    "maxPortfolioAllocationRate":0.85,
                    "pricePressureSensitivity":0.15,
                    "momentumSensitivity":0.25,
                    "valueSensitivity":-0.03,
                    "reportSensitivity":0.10,
                    "referenceDailyVolume":30000,
                    "dailyParticipationRate":0.02
                  }]
                }
                """;
    }
}
