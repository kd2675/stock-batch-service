package stock.batch.service.automarket.biz;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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

class LiquidityProviderScheduledPolicyActivationServiceTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2027, 1, 28);
    private static final LocalDateTime ACTIVATED_AT = BUSINESS_DATE.atTime(5, 30);

    private JdbcTemplate jdbcTemplate;
    private LiquidityProviderScheduledPolicyActivationService service;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:liquidity_policy_activation_" + UUID.randomUUID()
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
        service = new LiquidityProviderScheduledPolicyActivationService(
                jdbcTemplate,
                new ObjectMapper(),
                marketSessionService,
                new DataSourceTransactionManager(dataSource)
        );
        seedMandateAndPolicies();
    }

    @Test
    void activateDuePolicies_unusedBusinessDate_appliesPolicyAndRetiresCurrentVersion() {
        seedEmptyDailyState();

        int activated = service.activateDuePolicies(BUSINESS_DATE, ACTIVATED_AT);

        assertThat(activated).isOne();
        assertThat(jdbcTemplate.queryForMap(
                """
                select target_spread_ticks, max_spread_ticks,
                       max_order_quantity, reference_daily_volume,
                       target_inventory_quantity, inventory_band_quantity,
                       daily_loss_limit_amount, next_quote_at, policy_version
                  from stock_liquidity_mandate
                 where id = 1
                """
        )).containsEntry("target_spread_ticks", 3)
                .containsEntry("max_spread_ticks", 10)
                .containsEntry("max_order_quantity", 150L)
                .containsEntry("reference_daily_volume", 30_000L)
                .containsEntry("target_inventory_quantity", 1_200L)
                .containsEntry("inventory_band_quantity", 300L)
                .containsEntry("daily_loss_limit_amount", new BigDecimal("5000.00"))
                .containsEntry(
                        "next_quote_at",
                        java.sql.Timestamp.valueOf(BUSINESS_DATE.atTime(6, 0))
                )
                .containsEntry("policy_version", 4L);
        assertThat(jdbcTemplate.query(
                """
                select version_no, status
                  from stock_market_policy_version
                 where policy_scope = 'LIQUIDITY_MANDATE'
                   and scope_key = 'DEMO001'
                 order by version_no
                """,
                (rs, rowNum) -> java.util.Map.of(
                        "version", rs.getLong("version_no"),
                        "status", rs.getString("status")
                )
        )).containsExactly(
                java.util.Map.of("version", 3L, "status", "RETIRED"),
                java.util.Map.of("version", 4L, "status", "ACTIVE")
        );
        assertThat(jdbcTemplate.queryForObject(
                "select policy_version from stock_liquidity_transition where mandate_id = 1",
                Long.class
        )).isEqualTo(4L);
        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_liquidity_daily_state
                 where simulation_trade_date = ?
                   and mandate_id = 1
                """,
                Long.class,
                BUSINESS_DATE
        )).isZero();
        assertThat(service.activateDuePolicies(BUSINESS_DATE, ACTIVATED_AT.plusMinutes(1)))
                .isZero();
    }

    @Test
    void activateDuePolicies_dailyUsageAlreadyStarted_rollsBackEveryPolicyMutation() {
        seedEmptyDailyState();
        jdbcTemplate.update(
                """
                update stock_liquidity_daily_state
                   set quote_run_count = 1
                 where simulation_trade_date = ?
                   and mandate_id = 1
                """,
                BUSINESS_DATE
        );

        assertThatThrownBy(() ->
                service.activateDuePolicies(BUSINESS_DATE, ACTIVATED_AT)
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("daily usage started");

        assertThat(jdbcTemplate.queryForMap(
                """
                select target_spread_ticks, reference_daily_volume,
                       next_quote_at, policy_version
                  from stock_liquidity_mandate
                 where id = 1
                """
        )).containsEntry("target_spread_ticks", 4)
                .containsEntry("reference_daily_volume", 20_000L)
                .containsEntry("next_quote_at", null)
                .containsEntry("policy_version", 3L);
        assertThat(jdbcTemplate.query(
                """
                select version_no, status
                  from stock_market_policy_version
                 where policy_scope = 'LIQUIDITY_MANDATE'
                   and scope_key = 'DEMO001'
                 order by version_no
                """,
                (rs, rowNum) -> java.util.Map.of(
                        "version", rs.getLong("version_no"),
                        "status", rs.getString("status")
                )
        )).containsExactly(
                java.util.Map.of("version", 3L, "status", "ACTIVE"),
                java.util.Map.of("version", 4L, "status", "SCHEDULED")
        );
        assertThat(jdbcTemplate.queryForObject(
                "select policy_version from stock_liquidity_transition where mandate_id = 1",
                Long.class
        )).isEqualTo(3L);
    }

    private void seedMandateAndPolicies() {
        jdbcTemplate.update(
                """
                insert into stock_liquidity_mandate(
                    id, participant_id, account_id, symbol, mandate_code,
                    execution_mode, status, contract_start_date,
                    target_spread_ticks, max_spread_ticks, max_order_quantity,
                    reference_daily_volume, target_open_participation_rate,
                    max_open_participation_rate, max_single_order_participation_rate,
                    external_depth_levels, max_external_depth_participation_rate,
                    daily_execution_participation_rate, daily_submission_multiplier,
                    target_inventory_quantity, inventory_band_quantity,
                    inventory_skew_ticks, primary_regime_weight,
                    liquidity_size_sensitivity, volatility_spread_max_ticks,
                    price_regime_max_skew_ticks, passive_only,
                    minimum_quote_lifetime_seconds, reprice_threshold_ticks,
                    order_ttl_seconds, quote_interval_seconds,
                    daily_loss_limit_amount, policy_version, created_at, updated_at
                ) values (
                    1, 10, 200, 'DEMO001', 'LP-DEMO001',
                    'LIVE', 'ACTIVE', date '2027-01-01',
                    4, 12, 100, 20000, 0.050000, 0.080000, 0.010000,
                    5, 0.100000, 0.100000, 2.0000,
                    1000, 200, 3, 0.700000, 0.250000, 4, 1, true,
                    30, 2, 300, 30, 10000.00, 3, ?, ?
                )
                """,
                BUSINESS_DATE.minusDays(1).atStartOfDay(),
                BUSINESS_DATE.minusDays(1).atStartOfDay()
        );
        jdbcTemplate.update(
                """
                insert into stock_liquidity_transition(
                    transition_key, symbol, mandate_id, participant_id,
                    liquidity_account_id, source_account_id, stage,
                    reference_daily_volume, seed_inventory_quantity,
                    seed_cash_amount, effective_business_date, activated_at,
                    requested_by, change_reason, policy_version,
                    created_at, updated_at
                ) values (
                    'LIQUIDITY-TRANSITION:DEMO001', 'DEMO001', 1, 10,
                    200, 201, 'LIVE_ACTIVE', 20000, 1000,
                    500000.00, date '2027-01-01', ?,
                    'admin-test', 'LP 전환', 3, ?, ?
                )
                """,
                BUSINESS_DATE.minusDays(1).atStartOfDay(),
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
                    'LIQUIDITY_MANDATE', 'DEMO001', 3, date '2027-01-27',
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
                    'LIQUIDITY_MANDATE', 'DEMO001', 4, ?,
                    'SCHEDULED', ?, '다음 거래일 정책', 'stock-admin', ?, ?
                )
                """,
                BUSINESS_DATE,
                scheduledPolicyJson(),
                BUSINESS_DATE.minusDays(1).atTime(12, 0),
                BUSINESS_DATE.minusDays(1).atTime(12, 0)
        );
    }

    private void seedEmptyDailyState() {
        jdbcTemplate.update(
                """
                insert into stock_liquidity_daily_state(
                    simulation_trade_date, mandate_id,
                    reference_daily_volume, execution_quantity_limit,
                    submission_quantity_limit, policy_version,
                    created_at, updated_at
                ) values (?, 1, 20000, 2000, 4000, 3, ?, ?)
                """,
                BUSINESS_DATE,
                ACTIVATED_AT.minusMinutes(1),
                ACTIVATED_AT.minusMinutes(1)
        );
    }

    private String scheduledPolicyJson() {
        return """
                {
                  "symbol":"DEMO001",
                  "executionMode":"LIVE",
                  "targetSpreadTicks":3,
                  "maxSpreadTicks":10,
                  "maxOrderQuantity":150,
                  "referenceDailyVolume":30000,
                  "targetOpenParticipationRate":0.04,
                  "maxOpenParticipationRate":0.07,
                  "maxSingleOrderParticipationRate":0.005,
                  "externalDepthLevels":5,
                  "maxExternalDepthParticipationRate":0.08,
                  "dailyExecutionParticipationRate":0.08,
                  "dailySubmissionMultiplier":2.0,
                  "targetInventoryQuantity":1200,
                  "inventoryBandQuantity":300,
                  "inventorySkewTicks":4,
                  "primaryRegimeWeight":0.7,
                  "liquiditySizeSensitivity":0.25,
                  "volatilitySpreadMaxTicks":5,
                  "priceRegimeMaxSkewTicks":1,
                  "passiveOnly":true,
                  "minimumQuoteLifetimeSeconds":60,
                  "repriceThresholdTicks":3,
                  "orderTtlSeconds":600,
                  "quoteIntervalSeconds":30,
                  "dailyLossLimitAmount":5000
                }
                """;
    }
}
