package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoMarketDistributionBias;
import stock.batch.service.batch.automarket.reader.AutoMarketOrderReader;
import stock.batch.service.batch.automarket.writer.AutoMarketWriter;
import stock.batch.service.execution.queue.NoopOrderBookReadySymbolQueue;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LiquidityProviderQuoteProcessorIntegrationTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2027, 1, 27);
    private static final LocalDateTime NOW = TRADE_DATE.atTime(10, 0);

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private LiquidityProviderRepository repository;
    private LiquidityProviderQuoteProcessor processor;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:liquidity_provider_" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                "",
                true
        );
        new ResourceDatabasePopulator(new ClassPathResource("db/ddl/stock_h2.sql")).execute(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        repository = new LiquidityProviderRepository(jdbcTemplate);

        AutoMarketOrderReader orderReader = new AutoMarketOrderReader(jdbcTemplate);
        AutoMarketWriter orderWriter = new AutoMarketWriter(
                jdbcTemplate,
                new NoopOrderBookReadySymbolQueue(),
                new SimpleMeterRegistry()
        );
        AutoMarketOrderExecutor orderExecutor = new AutoMarketOrderExecutor(
                orderReader,
                orderWriter,
                mock(MarketSessionFenceService.class),
                mock(AutoParticipantFundingBudgetService.class)
        );
        processor = new LiquidityProviderQuoteProcessor(
                repository,
                new LiquidityProviderQuotePlanner(),
                orderExecutor
        );
    }

    @Test
    void process_liveMandatePersistsOrdersOriginReservationsAndDailyState() {
        seedMandate("LIVE");
        seedExternalDepth("OTHER:ONE", 10_000L);

        LiquidityProviderQuoteProcessor.ProcessResult result = process(NOW);

        assertThat(result.processed()).isTrue();
        assertThat(result.generatedOrderCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList(
                """
                select side, quantity, origin_type, self_trade_group_id
                  from stock_order
                 where account_id = 200
                 order by side
                """
        )).allSatisfy(row -> {
            assertThat(row.get("quantity")).isEqualTo(100L);
            assertThat(row.get("origin_type")).isEqualTo("LIQUIDITY_PROVIDER");
            assertThat(row.get("self_trade_group_id")).isEqualTo("LP:ONE");
        });
        assertThat(jdbcTemplate.queryForObject(
                "select cash_balance from stock_account where id = 200",
                BigDecimal.class
        )).isLessThan(new BigDecimal("10000000.00"));
        assertThat(jdbcTemplate.queryForObject(
                "select reserved_quantity from stock_holding where account_id = 200 and symbol = 'DEMO001'",
                Long.class
        )).isEqualTo(100L);
        assertThat(jdbcTemplate.queryForList(
                """
                select strategy_origin.origin_type,
                       strategy_origin.participant_id,
                       strategy_origin.liquidity_mandate_id,
                       strategy_origin.policy_version
                  from stock_order_strategy_origin strategy_origin
                  join stock_order order_row on order_row.id = strategy_origin.order_id
                 where order_row.account_id = 200
                 order by order_row.side
                """
        )).hasSize(2).allSatisfy(row -> {
            assertThat(row.get("origin_type")).isEqualTo("LIQUIDITY_PROVIDER");
            assertThat(row.get("participant_id")).isEqualTo(10L);
            assertThat(row.get("liquidity_mandate_id")).isEqualTo(1L);
            assertThat(row.get("policy_version")).isEqualTo(1L);
        });
        assertThat(jdbcTemplate.queryForMap(
                """
                select submitted_buy_quantity, submitted_sell_quantity,
                       last_open_buy_quantity, last_open_sell_quantity,
                       opening_net_asset_value, current_net_asset_value,
                       risk_profit, state_status, gate_reason, quote_run_count, version
                  from stock_liquidity_daily_state
                 where simulation_trade_date = date '2027-01-27'
                   and mandate_id = 1
                """
        )).containsEntry("submitted_buy_quantity", 100L)
                .containsEntry("submitted_sell_quantity", 100L)
                .containsEntry("last_open_buy_quantity", 100L)
                .containsEntry("last_open_sell_quantity", 100L)
                .containsEntry("opening_net_asset_value", new BigDecimal("20000000.00"))
                .containsEntry("current_net_asset_value", new BigDecimal("20000000.00"))
                .containsEntry("risk_profit", new BigDecimal("0.00"))
                .containsEntry("state_status", "QUOTING")
                .containsEntry("gate_reason", "WITHIN_LIMITS")
                .containsEntry("quote_run_count", 1L)
                .containsEntry("version", 0L);
    }

    @Test
    void process_secondQuoteRunRetainsOpeningNavAndUpdatesStateWithoutDuplicateOrders() {
        seedMandate("LIVE");
        seedExternalDepth("OTHER:ONE", 10_000L);
        process(NOW);

        LiquidityProviderQuoteProcessor.ProcessResult second = process(NOW.plusSeconds(30));

        assertThat(second.generatedOrderCount()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_order where account_id = 200",
                Integer.class
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForMap(
                """
                select opening_net_asset_value, current_net_asset_value,
                       risk_profit, quote_run_count, version
                  from stock_liquidity_daily_state
                 where simulation_trade_date = date '2027-01-27'
                   and mandate_id = 1
                """
        )).containsEntry("opening_net_asset_value", new BigDecimal("20000000.00"))
                .containsEntry("current_net_asset_value", new BigDecimal("20000000.00"))
                .containsEntry("risk_profit", new BigDecimal("0.00"))
                .containsEntry("quote_run_count", 2L)
                .containsEntry("version", 1L);
    }

    @Test
    void process_shadowMandateWritesHypotheticalAuditButNeverReservesOrOrders() {
        seedMandate("SHADOW");
        seedExternalDepth("OTHER:ONE", 10_000L);

        LiquidityProviderQuoteProcessor.ProcessResult result = process(NOW);

        assertThat(result.generatedOrderCount()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_order where account_id = 200",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select cash_balance from stock_account where id = 200",
                BigDecimal.class
        )).isEqualByComparingTo("10000000.00");
        assertThat(jdbcTemplate.queryForMap(
                """
                select submitted_buy_quantity, submitted_sell_quantity,
                       target_buy_open_quantity, target_sell_open_quantity,
                       state_status, gate_reason
                  from stock_liquidity_daily_state
                 where simulation_trade_date = date '2027-01-27'
                   and mandate_id = 1
                """
        )).containsEntry("submitted_buy_quantity", 0L)
                .containsEntry("submitted_sell_quantity", 0L)
                .containsEntry("target_buy_open_quantity", 100L)
                .containsEntry("target_sell_open_quantity", 100L)
                .containsEntry("state_status", "SHADOW")
                .containsEntry("gate_reason", "SHADOW_ONLY");
    }

    @Test
    void process_suspendedMandateCancelsAndReleasesEveryLiveQuote() {
        seedMandate("LIVE");
        seedExternalDepth("OTHER:ONE", 10_000L);
        process(NOW);
        jdbcTemplate.update(
                """
                update stock_liquidity_mandate
                   set status = 'SUSPENDED',
                       next_quote_at = null,
                       updated_at = ?
                 where id = 1
                """,
                NOW.plusSeconds(1)
        );

        LiquidityProviderQuoteProcessor.ProcessResult result = process(NOW.plusSeconds(1));

        assertThat(result.cancelledOrderCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_order where account_id = 200 and status <> 'CANCELLED'",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select cash_balance from stock_account where id = 200",
                BigDecimal.class
        )).isEqualByComparingTo("10000000.00");
        assertThat(jdbcTemplate.queryForObject(
                "select reserved_quantity from stock_holding where account_id = 200 and symbol = 'DEMO001'",
                Long.class
        )).isZero();
        assertThat(jdbcTemplate.queryForMap(
                """
                select cancelled_buy_quantity, cancelled_sell_quantity,
                       state_status, gate_reason
                  from stock_liquidity_daily_state
                 where simulation_trade_date = date '2027-01-27'
                   and mandate_id = 1
                """
        )).containsEntry("cancelled_buy_quantity", 100L)
                .containsEntry("cancelled_sell_quantity", 100L)
                .containsEntry("state_status", "EXEMPT")
                .containsEntry("gate_reason", "CONTRACT_NOT_ACTIVE");
    }

    @Test
    void externalBookExcludesEveryOrderInTheParticipantSelfTradeGroup() {
        seedMandate("SHADOW");
        seedExternalDepth("LP:ONE", 10_000L);
        seedExternalDepth("OTHER:ONE", 50L);
        LiquidityProviderMandate mandate = transactionTemplate.execute(
                status -> repository.lockMandate(1L).orElseThrow()
        );

        LiquidityProviderExternalBook externalBook = repository.findExternalBook(
                mandate,
                "LP:ONE"
        );

        assertThat(externalBook.buyDepthQuantity()).isEqualTo(50L);
        assertThat(externalBook.sellDepthQuantity()).isEqualTo(50L);
    }

    @Test
    void process_nullOriginOrderOnDedicatedAccount_haltsWithoutAddingLiquidityOrders() {
        seedMandate("LIVE");
        seedExternalDepth("OTHER:ONE", 10_000L);
        jdbcTemplate.update(
                """
                insert into stock_order(
                    client_order_id, account_id, origin_type, self_trade_group_id,
                    symbol, market_type, side, order_type, status, limit_price,
                    quantity, filled_quantity, reserved_cash, created_at, updated_at
                ) values (
                    'LP-UNKNOWN-ORIGIN', 200, null, 'LP:ONE',
                    'DEMO001', 'ORDER_BOOK', 'BUY', 'LIMIT', 'PENDING', 9800,
                    10, 0, 0, ?, ?
                )
                """,
                NOW.minusMinutes(1),
                NOW.minusMinutes(1)
        );

        LiquidityProviderQuoteProcessor.ProcessResult result = process(NOW);

        assertThat(result.generatedOrderCount()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_order
                 where account_id = 200
                   and origin_type = 'LIQUIDITY_PROVIDER'
                """,
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForMap(
                """
                select state_status, gate_reason, limit_breached
                  from stock_liquidity_daily_state
                 where simulation_trade_date = date '2027-01-27'
                   and mandate_id = 1
                """
        )).containsEntry("state_status", "HALTED")
                .containsEntry("gate_reason", "NON_LP_OPEN_ORDER_ON_DEDICATED_ACCOUNT")
                .containsEntry("limit_breached", true);
    }

    @Test
    void process_liquidityOrderWithoutStrategyOrigin_haltsAndCancelsUnownedQuote() {
        seedMandate("LIVE");
        seedExternalDepth("OTHER:ONE", 10_000L);
        jdbcTemplate.update(
                """
                update stock_holding
                   set reserved_quantity = 10
                 where account_id = 200
                   and symbol = 'DEMO001'
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_order(
                    client_order_id, account_id, origin_type, self_trade_group_id,
                    symbol, market_type, side, order_type, status, limit_price,
                    quantity, filled_quantity, reserved_cash, created_at, updated_at
                ) values (
                    'LP-MISSING-STRATEGY-ORIGIN', 200, 'LIQUIDITY_PROVIDER', 'LP:ONE',
                    'DEMO001', 'ORDER_BOOK', 'SELL', 'LIMIT', 'PENDING', 10200,
                    10, 0, 0, ?, ?
                )
                """,
                NOW.minusMinutes(1),
                NOW.minusMinutes(1)
        );

        LiquidityProviderQuoteProcessor.ProcessResult result = process(NOW);

        assertThat(result.generatedOrderCount()).isZero();
        assertThat(result.cancelledOrderCount()).isOne();
        assertThat(jdbcTemplate.queryForObject(
                """
                select status
                  from stock_order
                 where client_order_id = 'LP-MISSING-STRATEGY-ORIGIN'
                """,
                String.class
        )).isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject(
                """
                select reserved_quantity
                  from stock_holding
                 where account_id = 200
                   and symbol = 'DEMO001'
                """,
                Long.class
        )).isZero();
        assertThat(jdbcTemplate.queryForMap(
                """
                select state_status, gate_reason, limit_breached
                  from stock_liquidity_daily_state
                 where simulation_trade_date = date '2027-01-27'
                   and mandate_id = 1
                """
        )).containsEntry("state_status", "HALTED")
                .containsEntry("gate_reason", "NON_LP_OPEN_ORDER_ON_DEDICATED_ACCOUNT")
                .containsEntry("limit_breached", true);
    }

    @Test
    void process_virtualPriceOrderOnDedicatedAccount_haltsWithoutAddingLiquidityOrders() {
        seedMandate("LIVE");
        seedExternalDepth("OTHER:ONE", 10_000L);
        jdbcTemplate.update(
                """
                insert into stock_order(
                    client_order_id, account_id, origin_type, self_trade_group_id,
                    symbol, market_type, side, order_type, status, limit_price,
                    quantity, filled_quantity, reserved_cash, created_at, updated_at
                ) values (
                    'LP-WRONG-MARKET', 200, 'LIQUIDITY_PROVIDER', 'LP:ONE',
                    'DEMO001', 'VIRTUAL_PRICE', 'BUY', 'LIMIT', 'PENDING', 9800,
                    10, 0, 0, ?, ?
                )
                """,
                NOW.minusMinutes(1),
                NOW.minusMinutes(1)
        );

        LiquidityProviderQuoteProcessor.ProcessResult result = process(NOW);

        assertThat(result.generatedOrderCount()).isZero();
        assertThat(jdbcTemplate.queryForMap(
                """
                select state_status, gate_reason, limit_breached
                  from stock_liquidity_daily_state
                 where simulation_trade_date = date '2027-01-27'
                   and mandate_id = 1
                """
        )).containsEntry("state_status", "HALTED")
                .containsEntry("gate_reason", "NON_LP_OPEN_ORDER_ON_DEDICATED_ACCOUNT")
                .containsEntry("limit_breached", true);
    }

    @Test
    void process_unmanagedHoldingOnDedicatedAccount_haltsWithoutAddingLiquidityOrders() {
        seedMandate("LIVE");
        seedExternalDepth("OTHER:ONE", 10_000L);
        jdbcTemplate.update(
                """
                insert into stock_holding(
                    account_id, symbol, quantity, reserved_quantity, average_price, updated_at
                ) values (200, 'DEMO999', 10, 0, 10000, ?)
                """,
                NOW.minusMinutes(1)
        );

        LiquidityProviderQuoteProcessor.ProcessResult result = process(NOW);

        assertThat(result.generatedOrderCount()).isZero();
        assertThat(jdbcTemplate.queryForMap(
                """
                select state_status, gate_reason, limit_breached
                  from stock_liquidity_daily_state
                 where simulation_trade_date = date '2027-01-27'
                   and mandate_id = 1
                """
        )).containsEntry("state_status", "HALTED")
                .containsEntry("gate_reason", "UNMANAGED_HOLDING_ON_DEDICATED_ACCOUNT")
                .containsEntry("limit_breached", true);
    }

    private LiquidityProviderQuoteProcessor.ProcessResult process(LocalDateTime now) {
        return transactionTemplate.execute(status -> processor.process(
                1L,
                marketConfig(),
                true,
                TRADE_DATE,
                new MarketSessionFenceService.MarketSessionApproval(
                        TRADE_DATE,
                        Map.of("DEMO001", 1L),
                        now
                )
        ));
    }

    private void seedMandate(String executionMode) {
        jdbcTemplate.update(
                """
                insert into stock_account(
                    id, user_key, account_code, status, participant_category,
                    self_trade_group_id, cash_balance, created_at, updated_at
                ) values (
                    200, 'lp-one', 'LP-DEMO001', 'ACTIVE', 'LIQUIDITY_PROVIDER',
                    'LP:ONE', 10000000.00, ?, ?
                )
                """,
                NOW.minusHours(1),
                NOW.minusHours(1)
        );
        jdbcTemplate.update(
                """
                insert into stock_market_participant(
                    id, participant_code, display_name, participant_type,
                    status, self_trade_group_id, created_at, updated_at
                ) values (
                    10, 'LP_ONE', 'LP One', 'LIQUIDITY_PROVIDER',
                    'ACTIVE', 'LP:ONE', ?, ?
                )
                """,
                NOW.minusHours(1),
                NOW.minusHours(1)
        );
        jdbcTemplate.update(
                """
                insert into stock_market_participant_account(
                    id, participant_id, account_id, account_role, desk_code,
                    effective_from, effective_to, status, created_at, updated_at
                ) values (
                    10, 10, 200, 'LIQUIDITY_PROVIDER', 'DEMO001',
                    date '2027-01-01', null, 'ACTIVE', ?, ?
                )
                """,
                NOW.minusHours(1),
                NOW.minusHours(1)
        );
        jdbcTemplate.update(
                """
                insert into stock_order_book_instrument(
                    symbol, name, market, initial_price, issued_shares, tradable_shares,
                    tick_size, price_limit_rate, enabled, created_at, updated_at
                ) values (
                    'DEMO001', 'Demo One', 'KOSPI', 10000.00, 1000000, 500000,
                    10.00, 30.00, true, ?, ?
                )
                """,
                NOW.minusHours(1),
                NOW.minusHours(1)
        );
        jdbcTemplate.update(
                """
                insert into stock_price(
                    symbol, current_price, previous_close, price_time, provider
                ) values ('DEMO001', 10000.00, 10000.00, ?, 'TEST')
                """,
                NOW
        );
        jdbcTemplate.update(
                """
                insert into stock_holding(
                    account_id, symbol, quantity, reserved_quantity, average_price, updated_at
                ) values (200, 'DEMO001', 1000, 0, 10000.00, ?)
                """,
                NOW.minusHours(1)
        );
        jdbcTemplate.update(
                """
                insert into stock_liquidity_mandate(
                    id, participant_id, account_id, symbol, mandate_code,
                    execution_mode, status, contract_start_date, contract_end_date,
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
                    daily_loss_limit_amount, next_quote_at, policy_version,
                    created_at, updated_at
                ) values (
                    1, 10, 200, 'DEMO001', 'LP-DEMO001',
                    ?, 'ACTIVE', date '2027-01-01', null,
                    4, 12, 100, 10000, 0.050000, 0.080000, 0.010000,
                    5, 0.100000, 0.100000, 2.0000,
                    1000, 500, 3, 0.700000, 0.250000, 4, 1, true,
                    30, 2, 300, 30, 100000.00, null, 1, ?, ?
                )
                """,
                executionMode,
                NOW.minusHours(1),
                NOW.minusHours(1)
        );
    }

    private void seedExternalDepth(String selfTradeGroupId, long quantity) {
        long accountId = jdbcTemplate.queryForObject(
                "select coalesce(max(id), 300) + 1 from stock_account",
                Long.class
        );
        jdbcTemplate.update(
                """
                insert into stock_account(
                    id, user_key, account_code, status, participant_category,
                    self_trade_group_id, cash_balance, created_at, updated_at
                ) values (?, ?, ?, 'ACTIVE', 'MANUAL_PARTICIPANT', ?, 10000000.00, ?, ?)
                """,
                accountId,
                "external-" + accountId,
                "EXT-" + accountId,
                selfTradeGroupId,
                NOW.minusHours(1),
                NOW.minusHours(1)
        );
        insertExternalOrder(accountId, selfTradeGroupId, "BUY", new BigDecimal("9900.00"), quantity);
        insertExternalOrder(accountId, selfTradeGroupId, "SELL", new BigDecimal("10100.00"), quantity);
    }

    private void insertExternalOrder(
            long accountId,
            String selfTradeGroupId,
            String side,
            BigDecimal price,
            long quantity
    ) {
        String clientOrderId = "EXT-" + accountId + "-" + side;
        jdbcTemplate.update(
                """
                insert into stock_order(
                    client_order_id, account_id, origin_type, self_trade_group_id,
                    symbol, market_type, side, order_type, status, limit_price,
                    quantity, filled_quantity, reserved_cash, created_at, updated_at
                ) values (
                    ?, ?, 'MANUAL_PARTICIPANT', ?, 'DEMO001', 'ORDER_BOOK',
                    ?, 'LIMIT', 'PENDING', ?, ?, 0, 0.00, ?, ?
                )
                """,
                clientOrderId,
                accountId,
                selfTradeGroupId,
                side,
                price,
                quantity,
                NOW.minusMinutes(1),
                NOW.minusMinutes(1)
        );
    }

    private AutoMarketConfig marketConfig() {
        return new AutoMarketConfig(
                "DEMO001",
                "KOSPI",
                1_000,
                300,
                500_000L,
                new BigDecimal("10.00"),
                new BigDecimal("10000.00"),
                new BigDecimal("10000.00"),
                new BigDecimal("30.00"),
                null,
                AutoMarketDistributionBias.NEUTRAL,
                AutoMarketDistributionBias.NEUTRAL
        );
    }
}
