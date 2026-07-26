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

class IssueUnderwriterSupplyProcessorIntegrationTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2027, 1, 27);
    private static final LocalDateTime NOW = TRADE_DATE.atTime(10, 0);

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private IssueUnderwriterSupplyRepository repository;
    private IssueUnderwriterSupplyProcessor processor;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:issue_underwriter_" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa",
                "",
                true
        );
        new ResourceDatabasePopulator(
                new ClassPathResource("db/ddl/stock_h2.sql")
        ).execute(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        repository = new IssueUnderwriterSupplyRepository(jdbcTemplate);

        AutoMarketOrderExecutor orderExecutor = new AutoMarketOrderExecutor(
                new AutoMarketOrderReader(jdbcTemplate),
                new AutoMarketWriter(
                        jdbcTemplate,
                        new NoopOrderBookReadySymbolQueue(),
                        new SimpleMeterRegistry()
                ),
                mock(MarketSessionFenceService.class),
                mock(AutoParticipantFundingBudgetService.class)
        );
        processor = new IssueUnderwriterSupplyProcessor(
                repository,
                new IssueUnderwriterSupplyPlanner(),
                orderExecutor
        );
        seedActiveContract();
    }

    @Test
    void process_activeContract_createsOnePassiveSellWithOriginAndFiniteBudget() {
        seedExternalOrder(
                301L,
                "OTHER:BUY",
                "BUY",
                new BigDecimal("9900.00"),
                10_000L
        );
        seedExternalOrder(
                302L,
                "OTHER:SELL",
                "SELL",
                new BigDecimal("10100.00"),
                10_000L
        );

        IssueUnderwriterSupplyProcessor.ProcessResult result = process(NOW);

        assertThat(result.processed()).isTrue();
        assertThat(result.generatedOrderCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap(
                """
                select side, quantity, limit_price, origin_type,
                       self_trade_group_id, status
                  from stock_order
                 where account_id = 200
                """
        )).containsEntry("side", "SELL")
                .containsEntry("quantity", 300L)
                .containsEntry("limit_price", new BigDecimal("10100.00"))
                .containsEntry("origin_type", "ISSUE_UNDERWRITER")
                .containsEntry("self_trade_group_id", "UW:ONE")
                .containsEntry("status", "PENDING");
        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_order
                 where account_id = 200
                   and side = 'BUY'
                """,
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                """
                select reserved_quantity
                  from stock_holding
                 where account_id = 200
                   and symbol = 'DEMO001'
                """,
                Long.class
        )).isEqualTo(300L);
        assertThat(jdbcTemplate.queryForMap(
                """
                select strategy_origin.origin_type,
                       strategy_origin.participant_id,
                       strategy_origin.underwriting_contract_id,
                       strategy_origin.policy_version
                  from stock_order_strategy_origin strategy_origin
                  join stock_order order_row
                    on order_row.id = strategy_origin.order_id
                 where order_row.account_id = 200
                """
        )).containsEntry("origin_type", "ISSUE_UNDERWRITER")
                .containsEntry("participant_id", 10L)
                .containsEntry("underwriting_contract_id", 1L)
                .containsEntry("policy_version", 1L);
        assertThat(jdbcTemplate.queryForMap(
                """
                select reference_daily_volume, submission_quantity_limit,
                       submitted_quantity, submitted_amount,
                       generated_order_count, cancelled_order_count,
                       state_status, gate_reason
                  from stock_underwriting_daily_supply_state
                 where simulation_trade_date = date '2027-01-27'
                   and underwriting_contract_id = 1
                """
        )).containsEntry("reference_daily_volume", 15_000L)
                .containsEntry("submission_quantity_limit", 1_500L)
                .containsEntry("submitted_quantity", 300L)
                .containsEntry("submitted_amount", new BigDecimal("3030000.00"))
                .containsEntry("generated_order_count", 1L)
                .containsEntry("cancelled_order_count", 0L)
                .containsEntry("state_status", "ACTIVE")
                .containsEntry("gate_reason", "WITHIN_LIMITS");
    }

    @Test
    void process_existingOrder_retainsOneOrderWithoutDuplicateSubmission() {
        seedExternalOrder(
                301L,
                "OTHER:BUY",
                "BUY",
                new BigDecimal("9900.00"),
                10_000L
        );
        process(NOW);

        IssueUnderwriterSupplyProcessor.ProcessResult second =
                process(NOW.plusSeconds(30));

        assertThat(second.generatedOrderCount()).isZero();
        assertThat(second.cancelledOrderCount()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_order where account_id = 200",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap(
                """
                select submitted_quantity, generated_order_count,
                       cancelled_order_count, state_status, gate_reason
                  from stock_underwriting_daily_supply_state
                 where simulation_trade_date = date '2027-01-27'
                   and underwriting_contract_id = 1
                """
        )).containsEntry("submitted_quantity", 300L)
                .containsEntry("generated_order_count", 1L)
                .containsEntry("cancelled_order_count", 0L)
                .containsEntry("state_status", "ACTIVE")
                .containsEntry("gate_reason", "OPEN_ORDER_RETAINED");
    }

    @Test
    void process_expiredOrder_releasesReservationButNeverRefundsSubmittedBudget() {
        seedExternalOrder(
                301L,
                "OTHER:BUY",
                "BUY",
                new BigDecimal("9900.00"),
                10_000L
        );
        process(NOW);

        IssueUnderwriterSupplyProcessor.ProcessResult expired =
                process(NOW.plusSeconds(601));

        assertThat(expired.cancelledOrderCount()).isEqualTo(1);
        assertThat(expired.generatedOrderCount()).isZero();
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
                select submitted_quantity, generated_order_count,
                       cancelled_order_count, gate_reason
                  from stock_underwriting_daily_supply_state
                 where simulation_trade_date = date '2027-01-27'
                   and underwriting_contract_id = 1
                """
        )).containsEntry("submitted_quantity", 300L)
                .containsEntry("generated_order_count", 1L)
                .containsEntry("cancelled_order_count", 1L)
                .containsEntry("gate_reason", "EXPIRED_ORDER_CANCELLED");

        IssueUnderwriterSupplyProcessor.ProcessResult replacement =
                process(NOW.plusSeconds(602));

        assertThat(replacement.generatedOrderCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                """
                select submitted_quantity
                  from stock_underwriting_daily_supply_state
                 where simulation_trade_date = date '2027-01-27'
                   and underwriting_contract_id = 1
                """,
                Long.class
        )).isEqualTo(600L);
    }

    @Test
    void externalBook_sameSelfTradeGroupOrdersNeverContributeToDepth() {
        seedExternalOrder(
                301L,
                "UW:ONE",
                "BUY",
                new BigDecimal("9950.00"),
                10_000L
        );
        seedExternalOrder(
                302L,
                "OTHER:BUY",
                "BUY",
                new BigDecimal("9900.00"),
                500L
        );
        IssueUnderwriterSupplyRepository.ContractSnapshot contract =
                transactionTemplate.execute(
                        status -> repository.lockContract(1L).orElseThrow()
                );

        IssueUnderwriterSupplyRepository.ExternalBook externalBook =
                repository.findExternalBook(contract, "UW:ONE");

        assertThat(externalBook.bestBid()).isEqualByComparingTo("9900.00");
        assertThat(externalBook.topFiveBidDepth()).isEqualTo(500L);
    }

    @Test
    void process_lifetimeLimitAfterFinalOrderTerminal_completesContractAndRetiresPolicy() {
        seedExternalOrder(
                301L,
                "OTHER:BUY",
                "BUY",
                new BigDecimal("9900.00"),
                10_000L
        );
        process(NOW);
        long submittedQuantity = jdbcTemplate.queryForObject(
                """
                select submitted_quantity
                  from stock_underwriting_daily_supply_state
                 where simulation_trade_date = date '2027-01-27'
                   and underwriting_contract_id = 1
                """,
                Long.class
        );
        BigDecimal submittedAmount = jdbcTemplate.queryForObject(
                """
                select submitted_amount
                  from stock_underwriting_daily_supply_state
                 where simulation_trade_date = date '2027-01-27'
                   and underwriting_contract_id = 1
                """,
                BigDecimal.class
        );
        jdbcTemplate.update(
                """
                update stock_order
                   set status = 'CANCELLED',
                       updated_at = ?
                 where account_id = 200
                   and status = 'PENDING'
                """,
                NOW.plusSeconds(1)
        );
        jdbcTemplate.update(
                """
                update stock_holding
                   set reserved_quantity = 0,
                       updated_at = ?
                 where account_id = 200
                   and symbol = 'DEMO001'
                """,
                NOW.plusSeconds(1)
        );
        jdbcTemplate.update(
                """
                update stock_underwriting_contract
                   set stabilization_quantity_limit = ?,
                       stabilization_amount_limit = ?
                 where id = 1
                """,
                submittedQuantity,
                submittedAmount
        );

        IssueUnderwriterSupplyProcessor.ProcessResult completed =
                process(NOW.plusSeconds(2));

        assertThat(completed.gateReason()).isEqualTo("LIFETIME_LIMIT_REACHED");
        assertThat(jdbcTemplate.queryForObject(
                "select status from stock_underwriting_contract where id = 1",
                String.class
        )).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                """
                select status
                  from stock_market_policy_version
                 where policy_scope = 'UNDERWRITING_CONTRACT'
                   and scope_key = 'UW-DEMO001-20270127'
                   and version_no = 1
                """,
                String.class
        )).isEqualTo("RETIRED");
        assertThat(jdbcTemplate.queryForObject(
                """
                select state_status
                  from stock_underwriting_daily_supply_state
                 where simulation_trade_date = date '2027-01-27'
                   and underwriting_contract_id = 1
                """,
                String.class
        )).isEqualTo("COMPLETED");
    }

    @Test
    void process_mismatchedContractOrder_gatesWithoutCancellingOrDuplicating() {
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
                    id, client_order_id, account_id, origin_type,
                    self_trade_group_id, symbol, market_type, side,
                    order_type, status, limit_price, quantity,
                    filled_quantity, reserved_cash, expires_at,
                    created_at, updated_at
                ) values (
                    901, 'mismatched-origin', 200, 'AUTO_PARTICIPANT',
                    'UW:ONE', 'DEMO001', 'ORDER_BOOK', 'SELL',
                    'LIMIT', 'PENDING', 10100.00, 10,
                    0, 0.00, ?, ?, ?
                )
                """,
                NOW.plusMinutes(10),
                NOW.minusMinutes(1),
                NOW.minusMinutes(1)
        );
        jdbcTemplate.update(
                """
                insert into stock_order_strategy_origin(
                    order_id, origin_type, participant_id,
                    underwriting_contract_id, policy_version, created_at
                ) values (
                    901, 'ISSUE_UNDERWRITER', 10, 1, 1, ?
                )
                """,
                NOW.minusMinutes(1)
        );

        IssueUnderwriterSupplyProcessor.ProcessResult result = process(NOW);

        assertThat(result.generatedOrderCount()).isZero();
        assertThat(result.cancelledOrderCount()).isZero();
        assertThat(result.gateReason())
                .isEqualTo("OPEN_ORDER_RECONCILIATION_FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "select status from stock_order where id = 901",
                String.class
        )).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_order where account_id = 200",
                Integer.class
        )).isOne();
    }

    @Test
    void process_issuedShareHoldingMismatch_gatesBeforeCreatingOrder() {
        jdbcTemplate.update(
                """
                update stock_holding
                   set quantity = quantity - 1
                 where account_id = 201
                   and symbol = 'DEMO001'
                """
        );

        IssueUnderwriterSupplyProcessor.ProcessResult result = process(NOW);

        assertThat(result.generatedOrderCount()).isZero();
        assertThat(result.gateReason())
                .isEqualTo("SUPPLY_RECONCILIATION_FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from stock_order where account_id = 200",
                Integer.class
        )).isZero();
    }

    private IssueUnderwriterSupplyProcessor.ProcessResult process(LocalDateTime now) {
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

    private void seedActiveContract() {
        jdbcTemplate.update(
                """
                insert into stock_account(
                    id, user_key, account_code, status, participant_category,
                    self_trade_group_id, cash_balance, created_at, updated_at
                ) values (
                    200, 'underwriter-one', 'UW-DEMO001', 'ACTIVE',
                    'ISSUE_UNDERWRITER', 'UW:ONE', 0.00, ?, ?
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
                    10, 'UW_ONE', 'Underwriter One', 'ISSUE_UNDERWRITER',
                    'ACTIVE', 'UW:ONE', ?, ?
                )
                """,
                NOW.minusHours(1),
                NOW.minusHours(1)
        );
        jdbcTemplate.update(
                """
                insert into stock_account(
                    id, user_key, account_code, status, participant_category,
                    self_trade_group_id, cash_balance, created_at, updated_at
                ) values (
                    201, 'issuance-lockup-demo001', 'LOCK-DEMO001', 'ACTIVE',
                    'SYSTEM_CUSTODY', 'SYSTEM_CUSTODY:DEFAULT', 0.00, ?, ?
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
                    10, 10, 200, 'ISSUE_UNDERWRITER', 'DEMO001',
                    date '2027-01-01', null, 'ACTIVE', ?, ?
                )
                """,
                NOW.minusHours(1),
                NOW.minusHours(1)
        );
        jdbcTemplate.update(
                """
                insert into stock_order_book_instrument(
                    symbol, name, market, initial_price,
                    issued_shares, tradable_shares,
                    tick_size, price_limit_rate, enabled,
                    created_at, updated_at
                ) values (
                    'DEMO001', 'Demo One', 'KOSPI', 10000.00,
                    1000000, 500000, 10.00, 30.00, true, ?, ?
                )
                """,
                NOW.minusHours(1),
                NOW.minusHours(1)
        );
        jdbcTemplate.update(
                """
                insert into stock_holding(
                    account_id, symbol, quantity, reserved_quantity,
                    average_price, updated_at
                ) values (201, 'DEMO001', 500000, 0, 10000.00, ?)
                """,
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
                    account_id, symbol, quantity, reserved_quantity,
                    average_price, updated_at
                ) values (200, 'DEMO001', 500000, 0, 10000.00, ?)
                """,
                NOW.minusHours(1)
        );
        jdbcTemplate.update(
                """
                insert into stock_underwriting_contract(
                    id, contract_code, symbol, participant_id, account_id,
                    total_issue_quantity, tradable_allocation_quantity,
                    locked_allocation_quantity, external_allocation_quantity,
                    underwritten_quantity, issue_price, underwriting_type,
                    stabilization_start_date, stabilization_end_date,
                    stabilization_quantity_limit, stabilization_amount_limit,
                    status, policy_version, created_at, updated_at
                ) values (
                    1, 'UW-DEMO001-20270127', 'DEMO001', 10, 200,
                    1000000, 500000, 500000, 0,
                    500000, 10000.00, 'FIRM_COMMITMENT',
                    date '2027-01-27', date '2027-02-15',
                    5000, 50000000.00,
                    'STABILIZING', 1, ?, ?
                )
                """,
                NOW.minusHours(1),
                NOW.minusHours(1)
        );
        jdbcTemplate.update(
                """
                insert into stock_security_allocation_ledger(
                    idempotency_key, event_type, underwriting_contract_id,
                    source_account_id, destination_account_id, symbol,
                    quantity, unit_price, allocation_reason,
                    tradability_status, effective_business_date, created_at
                ) values
                    (
                        'UW-DEMO001-20270127:UNDERWRITER',
                        'INITIAL_ISSUE', 1, null, 200, 'DEMO001',
                        500000, 10000.00, 'INITIAL_FLOAT_UNDERWRITER',
                        'TRADABLE', date '2027-01-27', ?
                    ),
                    (
                        'UW-DEMO001-20270127:LOCKED',
                        'INITIAL_ISSUE', 1, null, 201, 'DEMO001',
                        500000, 10000.00, 'INITIAL_LOCKED_CUSTODY',
                        'LOCKED', date '2027-01-27', ?
                    )
                """,
                NOW.minusHours(1),
                NOW.minusHours(1)
        );
        jdbcTemplate.update(
                """
                insert into stock_market_policy_version(
                    policy_scope, scope_key, version_no,
                    effective_business_date, status, config_json,
                    change_reason, changed_by, created_at, updated_at
                ) values (
                    'UNDERWRITING_CONTRACT', 'UW-DEMO001-20270127', 1,
                    date '2027-01-27', 'ACTIVE', '{}',
                    'integration test seed', 'test', ?, ?
                )
                """,
                NOW.minusHours(1),
                NOW.minusHours(1)
        );
    }

    private void seedExternalOrder(
            long accountId,
            String selfTradeGroupId,
            String side,
            BigDecimal price,
            long quantity
    ) {
        jdbcTemplate.update(
                """
                insert into stock_account(
                    id, user_key, account_code, status, participant_category,
                    self_trade_group_id, cash_balance, created_at, updated_at
                ) values (
                    ?, ?, ?, 'ACTIVE', 'MANUAL_PARTICIPANT',
                    ?, 10000000.00, ?, ?
                )
                """,
                accountId,
                "external-" + accountId,
                "EXT-" + accountId,
                selfTradeGroupId,
                NOW.minusHours(1),
                NOW.minusHours(1)
        );
        jdbcTemplate.update(
                """
                insert into stock_order(
                    client_order_id, account_id, origin_type,
                    self_trade_group_id, symbol, market_type,
                    side, order_type, status, limit_price,
                    quantity, filled_quantity, reserved_cash,
                    created_at, updated_at
                ) values (
                    ?, ?, 'MANUAL_PARTICIPANT',
                    ?, 'DEMO001', 'ORDER_BOOK',
                    ?, 'LIMIT', 'PENDING', ?,
                    ?, 0, 0.00, ?, ?
                )
                """,
                "EXT-" + accountId + "-" + side,
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
