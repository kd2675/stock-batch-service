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
import stock.batch.service.execution.lock.OrderBookSymbolLock;
import stock.batch.service.execution.queue.NoopOrderBookReadySymbolQueue;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class InstitutionOrderIntentProcessorIntegrationTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2027, 1, 27);
    private static final LocalDateTime NOW = TRADE_DATE.atTime(10, 0);

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private InstitutionOrderIntentRepository repository;
    private InstitutionOrderIntentProcessor processor;
    private AutoMarketOrderExecutor orderExecutor;
    private MarketSessionFenceService marketSessionFenceService;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:institution_intent_" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                "",
                true
        );
        new ResourceDatabasePopulator(new ClassPathResource("db/ddl/stock_h2.sql"))
                .execute(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        repository = new InstitutionOrderIntentRepository(jdbcTemplate);

        AutoMarketOrderReader orderReader = new AutoMarketOrderReader(jdbcTemplate);
        AutoMarketWriter orderWriter = new AutoMarketWriter(
                jdbcTemplate,
                new NoopOrderBookReadySymbolQueue(),
                new SimpleMeterRegistry()
        );
        marketSessionFenceService = mock(MarketSessionFenceService.class);
        orderExecutor = new AutoMarketOrderExecutor(
                orderReader,
                orderWriter,
                marketSessionFenceService,
                mock(AutoParticipantFundingBudgetService.class)
        );
        processor = new InstitutionOrderIntentProcessor(
                repository,
                new InstitutionOrderExecutionPlanner(),
                orderExecutor
        );
        seedLiveIntent();
    }

    @Test
    void process_pendingLiveIntent_reservesAndLinksExactlyOneInstitutionOrder() {
        InstitutionOrderIntentProcessor.ProcessResult result = process();

        assertThat(result.processed()).isTrue();
        assertThat(result.submitted()).isTrue();
        Map<String, Object> order = jdbcTemplate.queryForMap(
                """
                select id, side, limit_price, quantity, reserved_cash,
                       origin_type, self_trade_group_id
                  from stock_order
                 where account_id = 900
                """
        );
        assertThat(order.get("side")).isEqualTo("BUY");
        assertThat(order.get("quantity")).isEqualTo(50L);
        assertThat(order.get("reserved_cash")).isEqualTo(new BigDecimal("4950.00"));
        assertThat(order.get("origin_type")).isEqualTo("INSTITUTIONAL_INVESTOR");
        assertThat(order.get("self_trade_group_id")).isEqualTo("INSTITUTION:LIVE");

        assertThat(jdbcTemplate.queryForMap(
                """
                select intent.status, intent.attempt_count, intent.submitted_order_id,
                       intent.submitted_price, intent.submitted_quantity,
                       strategy_origin.origin_type,
                       strategy_origin.participant_id,
                       strategy_origin.portfolio_id,
                       strategy_origin.decision_run_id,
                       strategy_origin.policy_version
                  from stock_institution_order_intent intent
                  join stock_order_strategy_origin strategy_origin
                    on strategy_origin.order_id = intent.submitted_order_id
                 where intent.decision_run_id = 900
                   and intent.symbol = 'DEMO001'
                """
        )).containsEntry("status", "SUBMITTED")
                .containsEntry("attempt_count", 0)
                .containsEntry("submitted_order_id", order.get("id"))
                .containsEntry("submitted_price", new BigDecimal("99.00"))
                .containsEntry("submitted_quantity", 50L)
                .containsEntry("origin_type", "INSTITUTIONAL_INVESTOR")
                .containsEntry("participant_id", 90L)
                .containsEntry("portfolio_id", 900L)
                .containsEntry("decision_run_id", 900L)
                .containsEntry("policy_version", 1L);
        assertThat(jdbcTemplate.queryForObject(
                "select cash_balance from stock_account where id = 900",
                BigDecimal.class
        )).isEqualByComparingTo("995050.00");
        assertThat(jdbcTemplate.queryForMap(
                """
                select planned_buy_amount, submitted_buy_amount, version
                  from stock_institution_daily_budget
                 where simulation_trade_date = date '2027-01-27'
                   and portfolio_id = 900
                   and symbol = 'DEMO001'
                """
        )).containsEntry("planned_buy_amount", new BigDecimal("4950.00"))
                .containsEntry("submitted_buy_amount", new BigDecimal("4950.00"))
                .containsEntry("version", 1L);
    }

    @Test
    void process_alreadySubmittedIntent_isIdempotentlySkipped() {
        process();

        InstitutionOrderIntentProcessor.ProcessResult second = process();

        assertThat(second).isEqualTo(InstitutionOrderIntentProcessor.ProcessResult.SKIPPED);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_order where account_id = 900",
                Integer.class
        )).isEqualTo(1);
    }

    @Test
    void markRejected_releasesUnsubmittedQuantityAndNotionalCapacity() {
        transactionTemplate.executeWithoutResult(status -> {
            InstitutionOrderIntent intent =
                    repository.lockIntent(900L, "DEMO001", TRADE_DATE).orElseThrow();
            repository.markRejected(
                    intent,
                    "EXECUTION_PRICE_OR_DEPTH_UNAVAILABLE",
                    TRADE_DATE,
                    NOW
            );
        });

        assertThat(jdbcTemplate.queryForMap(
                """
                select planned_buy_quantity, planned_buy_amount, version
                  from stock_institution_daily_budget
                 where simulation_trade_date = date '2027-01-27'
                   and portfolio_id = 900
                   and symbol = 'DEMO001'
                """
        )).containsEntry("planned_buy_quantity", 0L)
                .containsEntry("planned_buy_amount", new BigDecimal("0.00"))
                .containsEntry("version", 1L);
    }

    @Test
    void reconcileClosedSubmittedIntent_releasesUnfilledCapacityExactlyOnce() {
        process();
        jdbcTemplate.update(
                """
                update stock_order
                   set status = 'CANCELLED',
                       reserved_cash = 0,
                       updated_at = ?
                 where account_id = 900
                """,
                NOW.plusMinutes(10)
        );

        int first = transactionTemplate.execute(status ->
                repository.reconcileClosedSubmittedIntents(
                        TRADE_DATE,
                        NOW.plusMinutes(10)
                )
        );
        int second = transactionTemplate.execute(status ->
                repository.reconcileClosedSubmittedIntents(
                        TRADE_DATE,
                        NOW.plusMinutes(11)
                )
        );

        assertThat(first).isOne();
        assertThat(second).isZero();
        assertThat(jdbcTemplate.queryForObject(
                """
                select status
                  from stock_institution_order_intent
                 where decision_run_id = 900
                   and symbol = 'DEMO001'
                """,
                String.class
        )).isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForMap(
                """
                select planned_buy_quantity, planned_buy_amount,
                       submitted_buy_amount, executed_buy_amount, version
                  from stock_institution_daily_budget
                 where simulation_trade_date = date '2027-01-27'
                   and portfolio_id = 900
                   and symbol = 'DEMO001'
                """
        )).containsEntry("planned_buy_quantity", 0L)
                .containsEntry("planned_buy_amount", new BigDecimal("0.00"))
                .containsEntry("submitted_buy_amount", new BigDecimal("4950.00"))
                .containsEntry("executed_buy_amount", new BigDecimal("0.00"))
                .containsEntry("version", 2L);
    }

    @Test
    void externalBook_excludesEveryOrderInTheInstitutionSelfTradeGroup() {
        seedExternalOrder(901L, "INSTITUTION:LIVE", "BUY", "98.00", 1_000L);
        seedExternalOrder(901L, "INSTITUTION:LIVE", "SELL", "102.00", 1_000L);
        seedExternalOrder(902L, "OTHER:ONE", "BUY", "97.00", 40L);
        seedExternalOrder(902L, "OTHER:ONE", "SELL", "103.00", 60L);
        InstitutionOrderIntent intent = transactionTemplate.execute(status ->
                repository.lockIntent(900L, "DEMO001", TRADE_DATE).orElseThrow()
        );

        InstitutionExternalBook book = repository.findExternalBook(intent);

        assertThat(book.bestBid()).isEqualByComparingTo("97.00");
        assertThat(book.bestAsk()).isEqualByComparingTo("103.00");
        assertThat(book.buyDepthQuantity()).isEqualTo(40L);
        assertThat(book.sellDepthQuantity()).isEqualTo(60L);
    }

    @Test
    void externalBook_excludesSameAccountEvenWhenStoredGroupDiffers() {
        seedExternalOrder(900L, "STALE:GROUP", "BUY", "99.00", 1_000L);
        seedExternalOrder(902L, "OTHER:ONE", "BUY", "97.00", 40L);
        InstitutionOrderIntent intent = transactionTemplate.execute(status ->
                repository.lockIntent(900L, "DEMO001", TRADE_DATE).orElseThrow()
        );

        InstitutionExternalBook book = repository.findExternalBook(intent);

        assertThat(book.bestBid()).isEqualByComparingTo("97.00");
    }

    @Test
    void recordFailure_thirdAttemptMarksIntentFailedBeforeCoordinatedSuspension() {
        InstitutionOrderIntentRepository.IntentReference reference =
                new InstitutionOrderIntentRepository.IntentReference(900L, "DEMO001");

        InstitutionOrderIntentRepository.FailureResult first = transactionTemplate.execute(
                status -> repository.recordFailure(reference, "TEMPORARY_ONE", NOW)
        );
        InstitutionOrderIntentRepository.FailureResult second = transactionTemplate.execute(
                status -> repository.recordFailure(reference, "TEMPORARY_TWO", NOW.plusSeconds(1))
        );
        InstitutionOrderIntentRepository.FailureResult third = transactionTemplate.execute(
                status -> repository.recordFailure(reference, "TERMINAL_THREE", NOW.plusSeconds(2))
        );

        assertThat(first).isEqualTo(
                new InstitutionOrderIntentRepository.FailureResult(true, false, 1, 900L)
        );
        assertThat(second).isEqualTo(
                new InstitutionOrderIntentRepository.FailureResult(true, false, 2, 900L)
        );
        assertThat(third).isEqualTo(
                new InstitutionOrderIntentRepository.FailureResult(true, true, 3, 900L)
        );
        assertThat(jdbcTemplate.queryForMap(
                """
                select status, attempt_count, submission_reason
                  from stock_institution_order_intent
                 where decision_run_id = 900
                   and symbol = 'DEMO001'
                """
        )).containsEntry("status", "FAILED")
                .containsEntry("attempt_count", 3)
                .containsEntry("submission_reason", "TERMINAL_THREE");
        assertThat(jdbcTemplate.queryForObject(
                "select status from stock_institution_portfolio where id = 900",
                String.class
        )).isEqualTo("ACTIVE");
    }

    @Test
    void runPendingIntents_thirdFailureSuspendsAndCancelsExistingPortfolioOrders() {
        seedInstitutionOpenBuyOrder();
        jdbcTemplate.update(
                """
                insert into stock_institution_order_intent(
                    decision_run_id, symbol, portfolio_id, participant_id, account_id,
                    side, requested_quantity, planned_amount, reference_daily_volume,
                    execution_aggression_pressure, policy_version, status, attempt_count,
                    submitted_order_id, submitted_price, submitted_quantity,
                    submission_reason, created_at, updated_at, submitted_at
                )
                select decision_run_id, 'DEMO002', portfolio_id, participant_id, account_id,
                       side, requested_quantity, planned_amount, reference_daily_volume,
                       execution_aggression_pressure, policy_version, 'PENDING', 0,
                       null, null, 0, null, created_at, updated_at, null
                  from stock_institution_order_intent
                 where decision_run_id = 900
                   and symbol = 'DEMO001'
                """
        );
        InstitutionOrderIntentExecutionService executionService =
                new InstitutionOrderIntentExecutionService(
                        repository,
                        processor,
                        orderExecutor,
                        mock(OrderBookSymbolLock.class),
                        marketSessionFenceService,
                        transactionTemplate,
                        20,
                        3,
                        0L
                );

        executionService.runPendingIntents(Map.of(), TRADE_DATE, NOW);
        executionService.runPendingIntents(Map.of(), TRADE_DATE, NOW.plusSeconds(1));
        executionService.runPendingIntents(Map.of(), TRADE_DATE, NOW.plusSeconds(2));

        assertThat(jdbcTemplate.queryForMap(
                """
                select status, attempt_count, submission_reason
                  from stock_institution_order_intent
                 where decision_run_id = 900
                   and symbol = 'DEMO001'
                """
        )).containsEntry("status", "FAILED")
                .containsEntry("attempt_count", 3)
                .containsEntry("submission_reason", "ACTIVE_MARKET_CONFIG_MISSING");
        assertThat(jdbcTemplate.queryForMap(
                """
                select status, submission_reason
                  from stock_institution_order_intent
                 where decision_run_id = 900
                   and symbol = 'DEMO002'
                """
        )).containsEntry("status", "REJECTED")
                .containsEntry(
                        "submission_reason",
                        "PORTFOLIO_SUSPENDED_AFTER_TERMINAL_FAILURE:900:DEMO001"
                );
        assertThat(jdbcTemplate.queryForMap(
                """
                select status, policy_version, next_decision_at
                  from stock_institution_portfolio
                 where id = 900
                """
        )).containsEntry("status", "SUSPENDED")
                .containsEntry("policy_version", 2L)
                .containsEntry("next_decision_at", null);
        assertThat(jdbcTemplate.queryForMap(
                """
                select status, reserved_cash
                  from stock_order
                 where client_order_id = 'INST-OPEN-BUY'
                """
        )).containsEntry("status", "CANCELLED")
                .containsEntry("reserved_cash", new BigDecimal("0.00"));
        assertThat(jdbcTemplate.queryForObject(
                "select cash_balance from stock_account where id = 900",
                BigDecimal.class
        )).isEqualByComparingTo("1000000.00");
        assertThat(jdbcTemplate.queryForMap(
                """
                select version_no, status, changed_by
                  from stock_market_policy_version
                 where policy_scope = 'INSTITUTIONAL_PORTFOLIO'
                   and scope_key = 'LIVE'
                   and version_no = 2
                """
        )).containsEntry("version_no", 2L)
                .containsEntry("status", "ACTIVE")
                .containsEntry("changed_by", "STOCK_BATCH");
    }

    @Test
    void rejectStalePendingIntents_priorTradeDate_releasesPortfolioWithoutSuspension() {
        jdbcTemplate.update(
                """
                update stock_institution_decision_run
                   set simulation_trade_date = date '2027-01-26'
                 where id = 900
                """
        );

        int rejected = transactionTemplate.execute(status ->
                repository.rejectStalePendingIntents(TRADE_DATE, NOW)
        );

        assertThat(rejected).isOne();
        assertThat(jdbcTemplate.queryForMap(
                """
                select status, submission_reason
                  from stock_institution_order_intent
                 where decision_run_id = 900
                   and symbol = 'DEMO001'
                """
        )).containsEntry("status", "REJECTED")
                .containsEntry("submission_reason", "STALE_SIMULATION_TRADE_DATE");
        assertThat(repository.findPendingIntents(TRADE_DATE, 20)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "select status from stock_institution_portfolio where id = 900",
                String.class
        )).isEqualTo("ACTIVE");
    }

    private InstitutionOrderIntentProcessor.ProcessResult process() {
        return transactionTemplate.execute(status -> processor.process(
                new InstitutionOrderIntentRepository.IntentReference(900L, "DEMO001"),
                marketConfig(),
                TRADE_DATE,
                new MarketSessionFenceService.MarketSessionApproval(
                        TRADE_DATE,
                        Map.of("DEMO001", 1L),
                        NOW
                )
        ));
    }

    private void seedLiveIntent() {
        jdbcTemplate.update(
                """
                insert into stock_account(
                    id, user_key, account_code, status, participant_category,
                    self_trade_group_id, cash_balance, created_at, updated_at
                ) values (
                    900, 'institution-live', 'INST-LIVE', 'ACTIVE',
                    'INSTITUTIONAL_INVESTOR', 'INSTITUTION:LIVE',
                    1000000.00, ?, ?
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
                    90, 'INSTITUTION_LIVE', 'Institution Live',
                    'INSTITUTIONAL_INVESTOR', 'ACTIVE',
                    'INSTITUTION:LIVE', ?, ?
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
                    90, 90, 900, 'INSTITUTIONAL_INVESTOR', 'LIVE',
                    date '2027-01-01', null, 'ACTIVE', ?, ?
                )
                """,
                NOW.minusHours(1),
                NOW.minusHours(1)
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
                    900, 90, 900, 'LIVE', 'Institution Live',
                    'BALANCED_LONG_TERM', 'LIVE', 'ACTIVE',
                    0.600000, 0.300000, 0.800000, 0.700000,
                    0.020000, 0.020000, 0.005000, 0.002000,
                    0.010000, 0.002000, 60, null, 1, ?, ?
                )
                """,
                NOW.minusHours(1),
                NOW.minusHours(1)
        );
        jdbcTemplate.update(
                """
                insert into stock_institution_decision_run(
                    id, decision_slot, simulation_trade_date, portfolio_id,
                    execution_mode, policy_version, deterministic_seed,
                    status, error_message, created_at, completed_at
                ) values (
                    900, ?, date '2027-01-27', 900,
                    'LIVE', 1, 1234,
                    'COMPLETED', null, ?, ?
                )
                """,
                NOW,
                NOW,
                NOW
        );
        jdbcTemplate.update(
                """
                insert into stock_institution_daily_budget(
                    simulation_trade_date, portfolio_id, symbol,
                    reference_daily_volume, gross_quantity_limit,
                    gross_notional_limit, planned_buy_quantity,
                    planned_sell_quantity, planned_buy_amount, planned_sell_amount,
                    submitted_buy_amount, submitted_sell_amount,
                    executed_buy_amount, executed_sell_amount,
                    policy_version, version, created_at, updated_at
                ) values (
                    date '2027-01-27', 900, 'DEMO001',
                    10000, 200, 100000.00, 50,
                    0, 5000.00, 0.00,
                    0.00, 0.00, 0.00, 0.00,
                    1, 0, ?, ?
                )
                """,
                NOW,
                NOW
        );
        jdbcTemplate.update(
                """
                insert into stock_institution_order_intent(
                    decision_run_id, symbol, portfolio_id, participant_id,
                    account_id, side, requested_quantity, planned_amount,
                    reference_daily_volume, execution_aggression_pressure,
                    policy_version, status, attempt_count,
                    submitted_order_id, submitted_price, submitted_quantity,
                    submission_reason, created_at, updated_at, submitted_at
                ) values (
                    900, 'DEMO001', 900, 90,
                    900, 'BUY', 50, 5000.00,
                    10000, 0.000000,
                    1, 'PENDING', 0,
                    null, null, 0,
                    null, ?, ?, null
                )
                """,
                NOW,
                NOW
        );
    }

    private void seedExternalOrder(
            long accountId,
            String selfTradeGroupId,
            String side,
            String price,
            long quantity
    ) {
        if (jdbcTemplate.queryForObject(
                "select count(*) from stock_account where id = ?",
                Integer.class,
                accountId
        ) == 0) {
            jdbcTemplate.update(
                    """
                    insert into stock_account(
                        id, user_key, account_code, status, participant_category,
                        self_trade_group_id, cash_balance, created_at, updated_at
                    ) values (?, ?, ?, 'ACTIVE', 'MANUAL_PARTICIPANT', ?, 1000000.00, ?, ?)
                    """,
                    accountId,
                    "external-" + accountId,
                    "EXT-" + accountId,
                    selfTradeGroupId,
                    NOW.minusHours(1),
                    NOW.minusHours(1)
            );
        }
        jdbcTemplate.update(
                """
                insert into stock_order(
                    client_order_id, account_id, origin_type, self_trade_group_id,
                    symbol, market_type, side, order_type, status, limit_price,
                    quantity, filled_quantity, reserved_cash, created_at, updated_at
                ) values (
                    ?, ?, 'MANUAL_PARTICIPANT', ?,
                    'DEMO001', 'ORDER_BOOK', ?, 'LIMIT', 'PENDING', ?,
                    ?, 0, 0.00, ?, ?
                )
                """,
                "EXT-" + accountId + "-" + side,
                accountId,
                selfTradeGroupId,
                side,
                new BigDecimal(price),
                quantity,
                NOW.minusMinutes(1),
                NOW.minusMinutes(1)
        );
    }

    private void seedInstitutionOpenBuyOrder() {
        jdbcTemplate.update(
                "update stock_account set cash_balance = 999500.00 where id = 900"
        );
        jdbcTemplate.update(
                """
                insert into stock_order(
                    client_order_id, account_id, origin_type, self_trade_group_id,
                    symbol, market_type, side, order_type, status, limit_price,
                    quantity, filled_quantity, reserved_cash, created_at, updated_at
                ) values (
                    'INST-OPEN-BUY', 900, 'INSTITUTIONAL_INVESTOR', 'INSTITUTION:LIVE',
                    'DEMO001', 'ORDER_BOOK', 'BUY', 'LIMIT', 'PENDING', 100.00,
                    5, 0, 500.00, ?, ?
                )
                """,
                NOW.minusMinutes(5),
                NOW.minusMinutes(5)
        );
    }

    private AutoMarketConfig marketConfig() {
        return new AutoMarketConfig(
                "DEMO001",
                "KOSPI",
                1_000,
                300,
                500_000L,
                BigDecimal.ONE,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                new BigDecimal("30.00"),
                null,
                AutoMarketDistributionBias.NEUTRAL,
                AutoMarketDistributionBias.NEUTRAL
        );
    }
}
