package stock.batch.service.marketclose.biz;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import stock.batch.service.batch.marketclose.writer.MarketCloseRolloverWriter;
import stock.batch.service.marketclose.model.PostCloseCycle;
import stock.batch.service.marketclose.model.PostClosePhase;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "stock.batch.market-close.order-capture-chunk-size=1",
        "stock.batch.market-close.order-cancel-chunk-size=1",
        "stock.batch.market-close.holding-snapshot-account-chunk-size=1",
        "stock.batch.market-close.account-snapshot-chunk-size=1",
        "stock.batch.market-close.reconciliation-account-chunk-size=1"
})
class MarketCloseRolloverServiceTest {

    private static final long POST_CLOSE_ACCUMULATED_REAL_SECONDS = 5_550L;

    @Autowired
    private MarketCloseRolloverService marketCloseRolloverService;

    @Autowired
    private MarketCloseRolloverWriter marketCloseRolloverWriter;

    @Autowired
    private PostCloseReportAggregationService postCloseReportAggregationService;

    @Autowired
    private PostCloseCycleService postCloseCycleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from stock_execution");
        jdbcTemplate.update("delete from stock_order");
        jdbcTemplate.update("delete from stock_close_open_order_snapshot");
        jdbcTemplate.update("delete from stock_close_open_order_summary");
        jdbcTemplate.update("delete from stock_close_price_snapshot");
        jdbcTemplate.update("delete from stock_close_account_snapshot");
        jdbcTemplate.update("delete from stock_post_close_cycle_metric");
        jdbcTemplate.update("delete from stock_holding_snapshot");
        jdbcTemplate.update("delete from stock_execution_daily_account_snapshot");
        jdbcTemplate.update("delete from stock_order_book_daily_snapshot");
        jdbcTemplate.update("delete from stock_post_close_phase_attempt");
        jdbcTemplate.update("delete from stock_post_close_cycle");
        jdbcTemplate.update("delete from stock_market_close_run");
        jdbcTemplate.update("delete from stock_holding");
        jdbcTemplate.update("delete from stock_account where user_key like 'market-close-%'");
        jdbcTemplate.update("delete from stock_price_tick");
        jdbcTemplate.update("delete from stock_price");
        jdbcTemplate.update("delete from stock_order_book_market_config");
        jdbcTemplate.update("delete from stock_order_book_instrument");
        jdbcTemplate.update("delete from stock_market_session_fence");
        jdbcTemplate.update("delete from stock_market_business_state");
        jdbcTemplate.update("delete from stock_simulation_clock");
        setSimulationDate(LocalDate.of(2026, 1, 1));
    }

    @Test
    void rolloverClosingPrices_copiesCurrentPriceToPreviousCloseOnlyForChangedPrices() {
        insertPrice("005930", "73500.00", "70000.00", "internal-order-book");
        insertPrice("000660", "120000.00", "120000.00", "kis");

        int processedCount = marketCloseRolloverService.rolloverClosingPrices();

        assertThat(processedCount).isEqualTo(1);
        assertThat(queryDecimal("select previous_close from stock_price where symbol = '005930'"))
                .isEqualByComparingTo(new BigDecimal("73500.00"));
        assertThat(queryDecimal("select current_price from stock_price where symbol = '005930'"))
                .isEqualByComparingTo(new BigDecimal("73500.00"));
        assertThat(queryString("select provider from stock_price where symbol = '005930'"))
                .isEqualTo("internal-order-book");
        assertThat(queryDecimal("select previous_close from stock_price where symbol = '000660'"))
                .isEqualByComparingTo(new BigDecimal("120000.00"));
    }

    @Test
    void rolloverClosingPrices_afterAlreadyRolledOver_isIdempotent() {
        insertPrice("005930", "73500.00", "70000.00", "internal-order-book");
        marketCloseRolloverService.rolloverClosingPrices();

        int processedCount = marketCloseRolloverService.rolloverClosingPrices();

        assertThat(processedCount).isZero();
    }

    @ParameterizedTest
    @EnumSource(
            value = PostClosePhase.class,
            names = {"ORDER_ENTRY_CLOSED", "EXECUTION_DRAINED"}
    )
    void rolloverClosingPrices_fromLegacyInternalClosePhase_resumesLedgerFreeze(PostClosePhase legacyPhase) {
        LocalDate businessDate = LocalDate.of(2026, 1, 2);
        LocalDateTime closedAt = businessDate.atTime(18, 0);
        setSimulationDate(businessDate);
        insertOrderBookInstrument("MC_LEGACY");
        insertPrice("MC_LEGACY", "73500.00", "70000.00", "internal-order-book");
        PostCloseCycle cycle = postCloseCycleService.ensureFullMarketCycle(businessDate, closedAt);
        jdbcTemplate.update(
                "update stock_post_close_cycle set phase = ?, status = 'PENDING' where id = ?",
                legacyPhase.name(),
                cycle.id()
        );

        marketCloseRolloverService.rolloverClosingPrices(businessDate, closedAt);

        assertThat(jdbcTemplate.queryForMap(
                """
                select c.phase, c.status, r.status as close_run_status
                  from stock_post_close_cycle c
                  join stock_market_close_run r on r.id = c.close_run_id
                 where c.id = ?
                """,
                cycle.id()
        )).containsEntry("PHASE", "LEDGER_FROZEN")
                .containsEntry("STATUS", "PENDING")
                .containsEntry("CLOSE_RUN_STATUS", "COMPLETED");
    }

    @Test
    void rolloverClosingPrices_persistsPollingMetricsWithoutRuntimeLedgerScans() {
        insertPrice("EOD_METRIC", "73500.00", "70000.00", "internal-order-book");

        marketCloseRolloverService.rolloverClosingPrices();

        assertThat(jdbcTemplate.queryForMap(
                """
                select captured_open_order_count, cancelled_order_count,
                       released_buy_cash, released_sell_quantity,
                       reconciliation_mismatch_count, settlement_missing_account_count
                  from stock_post_close_cycle_metric
                """
        )).containsEntry("CAPTURED_OPEN_ORDER_COUNT", 0L)
                .containsEntry("CANCELLED_ORDER_COUNT", 0L)
                .containsEntry("RELEASED_BUY_CASH", new BigDecimal("0.00"))
                .containsEntry("RELEASED_SELL_QUANTITY", 0L)
                .containsEntry("RECONCILIATION_MISMATCH_COUNT", 0L);
    }

    @Test
    void findCloseLockSymbols_withoutSymbol_readsOnlyOrderBookControlTables() {
        insertPrice("MC_PRICE_ONLY", "73500.00", "70000.00", "internal-order-book");
        insertOrderBookInstrument("MC_INSTRUMENT_ONLY");
        insertOrderBookMarketConfig("MC_MARKET_ONLY");
        insertAccount("market-close-lock-buyer", "1000000.00");
        insertReservedBuyOrder("market-close-lock-order", "market-close-lock-buyer", "MC_ORDER_ONLY", "147000.00");
        jdbcTemplate.update(
                """
                insert into stock_market_session_fence(
                    market_type, symbol, business_date, session_epoch, session_state,
                    state_changed_at, version, created_at, updated_at
                )
                values ('ORDER_BOOK', 'MC_FENCE_ONLY', date '2026-07-01', 1, 'CLOSED',
                        timestamp '2026-07-01 18:00:00', 0,
                        timestamp '2026-07-01 18:00:00', timestamp '2026-07-01 18:00:00')
                """
        );

        List<String> lockSymbols = marketCloseRolloverWriter.findCloseLockSymbols(null);

        assertThat(lockSymbols)
                .contains("MC_FENCE_ONLY", "MC_INSTRUMENT_ONLY", "MC_MARKET_ONLY")
                .doesNotContain("MC_PRICE_ONLY", "MC_ORDER_ONLY");
    }

    @Test
    void openOrderCapture_streamsByStatusAndSymbol_withoutRecapturingTerminalHistory() {
        LocalDateTime capturedAt = LocalDateTime.of(2026, 7, 3, 18, 0);
        insertAccount("market-close-capture", "1000000.00");
        insertReservedBuyOrder(
                "market-close-terminal-order",
                "market-close-capture",
                "MC_CAPTURE",
                "147000.00"
        );
        jdbcTemplate.update(
                "update stock_order set status = 'CANCELLED', reserved_cash = 0 where client_order_id = ?",
                "market-close-terminal-order"
        );
        insertReservedBuyOrder(
                "market-close-partial-order",
                "market-close-capture",
                "MC_CAPTURE",
                "73500.00"
        );
        jdbcTemplate.update(
                "update stock_order set status = 'PARTIALLY_FILLED', filled_quantity = 1 where client_order_id = ?",
                "market-close-partial-order"
        );

        assertThat(marketCloseRolloverWriter.findOpenOrderCaptureSymbols())
                .containsExactly("MC_CAPTURE");
        assertThat(marketCloseRolloverWriter.captureOpenOrdersChunk(
                77L, 88L, "MC_CAPTURE", "PENDING", capturedAt, 0L, 1
        )).isZero();
        assertThat(marketCloseRolloverWriter.captureOpenOrdersChunk(
                77L, 88L, "MC_CAPTURE", "PARTIALLY_FILLED", capturedAt, 0L, 1
        )).isEqualTo(1);
        assertThat(marketCloseRolloverWriter.findLastCapturedOrderId(
                77L, "MC_CAPTURE", "PARTIALLY_FILLED"
        )).isPositive();
        assertThat(queryString(
                "select source_order_status from stock_close_open_order_snapshot where close_cycle_id = 77"
        )).isEqualTo("PARTIALLY_FILLED");
        assertThat(queryLong(
                "select count(*) from stock_close_open_order_snapshot where close_cycle_id = 77"
        )).isEqualTo(1L);
    }

    @Test
    void rolloverClosingPrices_cancelsOpenOrderBookOrdersAndSnapshotsHoldingsPerCloseRun() {
        LocalDate simulationDate = LocalDate.of(2026, 7, 3);
        setSimulationDate(simulationDate);
        insertOrderBookInstrument("MC001");
        insertPrice("MC001", "73500.00", "70000.00", "internal-order-book");
        insertAccount("market-close-buyer", "1000000.00");
        insertAccount("market-close-seller", "500000.00");
        insertExecution("market-close-buyer", "MC001", "BUY", 2L, "73500.00", "147000.00", simulationDate.atTime(10, 0));
        insertExecution("market-close-seller", "MC001", "SELL", 2L, "73500.00", "147000.00", simulationDate.atTime(10, 0));
        insertHolding("market-close-seller", "MC001", 10L, 4L, "70000.00");
        insertReservedBuyOrder("market-close-buy-order", "market-close-buyer", "MC001", "147000.00");
        insertReservedSellOrder("market-close-sell-order", "market-close-seller", "MC001", 4L);

        int processedCount = marketCloseRolloverService.rolloverClosingPrices();

        assertThat(processedCount).isEqualTo(4);
        Long closeRunId = queryLong("select max(id) from stock_market_close_run");
        assertThat(queryLong("select count(*) from stock_order_book_daily_snapshot where close_run_id = ?", closeRunId))
                .as("18:00 freeze must not run the stock_execution-backed symbol report aggregation")
                .isZero();
        assertThat(queryLong("select count(*) from stock_execution_daily_account_snapshot where close_run_id = ?", closeRunId))
                .as("18:00 freeze must not run the stock_execution-backed account report aggregation")
                .isZero();
        aggregateFullMarketReports(simulationDate);
        assertThat(queryLong("select cancelled_order_count from stock_market_close_run where id = " + closeRunId))
                .isEqualTo(2L);
        assertThat(queryDecimal(
                "select released_buy_cash from stock_post_close_cycle_metric where close_run_id = ?",
                closeRunId
        )).isEqualByComparingTo(new BigDecimal("147000.00"));
        assertThat(queryLong(
                "select released_sell_quantity from stock_post_close_cycle_metric where close_run_id = ?",
                closeRunId
        )).isEqualTo(4L);
        assertThat(queryLong("select holding_snapshot_count from stock_market_close_run where id = " + closeRunId))
                .isEqualTo(1L);
        assertThat(queryLong("select price_rollover_count from stock_market_close_run where id = " + closeRunId))
                .isEqualTo(1L);
        assertThat(queryLong("select count(*) from stock_order_book_daily_snapshot where close_run_id = ?", closeRunId))
                .isEqualTo(1L);
        assertThat(queryDecimal("select close_price from stock_order_book_daily_snapshot where close_run_id = ?", closeRunId))
                .isEqualByComparingTo(new BigDecimal("73500.00"));
        assertThat(queryDecimal("select previous_close from stock_order_book_daily_snapshot where close_run_id = ?", closeRunId))
                .isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(queryDecimal("select change_rate from stock_order_book_daily_snapshot where close_run_id = ?", closeRunId))
                .isEqualByComparingTo(new BigDecimal("5.0000"));
        assertThat(queryLong("select execution_count from stock_order_book_daily_snapshot where close_run_id = ?", closeRunId))
                .isEqualTo(1L);
        assertThat(queryLong("select execution_quantity from stock_order_book_daily_snapshot where close_run_id = ?", closeRunId))
                .isEqualTo(2L);
        assertThat(queryDecimal("select turnover_amount from stock_order_book_daily_snapshot where close_run_id = ?", closeRunId))
                .isEqualByComparingTo(new BigDecimal("147000.00"));
        assertThat(queryLong("select buy_quantity from stock_order_book_daily_snapshot where close_run_id = ?", closeRunId))
                .isEqualTo(2L);
        assertThat(queryLong("select sell_quantity from stock_order_book_daily_snapshot where close_run_id = ?", closeRunId))
                .isEqualTo(2L);
        assertThat(queryDecimal("select open_price from stock_order_book_daily_snapshot where close_run_id = ?", closeRunId))
                .isEqualByComparingTo(new BigDecimal("73500.00"));
        assertThat(queryDecimal("select high_price from stock_order_book_daily_snapshot where close_run_id = ?", closeRunId))
                .isEqualByComparingTo(new BigDecimal("73500.00"));
        assertThat(queryDecimal("select low_price from stock_order_book_daily_snapshot where close_run_id = ?", closeRunId))
                .isEqualByComparingTo(new BigDecimal("73500.00"));
        assertThat(queryDecimal("select last_execution_price from stock_order_book_daily_snapshot where close_run_id = ?", closeRunId))
                .isEqualByComparingTo(new BigDecimal("73500.00"));
        assertThat(queryLong("select count(*) from stock_execution_daily_account_snapshot where close_run_id = ?", closeRunId))
                .isEqualTo(2L);
        assertThat(queryLong("select open_order_count from stock_order_book_daily_snapshot where close_run_id = ?", closeRunId))
                .isEqualTo(2L);
        assertThat(queryDecimal("select reserved_buy_cash from stock_order_book_daily_snapshot where close_run_id = ?", closeRunId))
                .isEqualByComparingTo(new BigDecimal("147000.00"));
        assertThat(queryLong("select holder_count from stock_order_book_daily_snapshot where close_run_id = ?", closeRunId))
                .isEqualTo(1L);
        assertThat(queryLong("select holding_quantity from stock_order_book_daily_snapshot where close_run_id = ?", closeRunId))
                .isEqualTo(10L);
        assertThat(queryLong("select count(*) from stock_order where symbol = 'MC001' and status = 'CANCELLED' and reserved_cash = 0"))
                .isEqualTo(2L);
        assertThat(queryLong("select count(*) from stock_close_open_order_snapshot where released_at is not null"))
                .as("every one-order transaction must persist its release checkpoint")
                .isEqualTo(2L);
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'market-close-buyer'"))
                .isEqualByComparingTo(new BigDecimal("1000000.00"));
        assertThat(queryLong("""
                select reserved_quantity
                  from stock_holding h
                  join stock_account a on a.id = h.account_id
                where a.user_key = 'market-close-seller'
                   and h.symbol = 'MC001'
                """)).isZero();
        assertThat(queryLong("""
                select quantity
                  from stock_holding_snapshot s
                  join stock_account a on a.id = s.account_id
                 where a.user_key = 'market-close-seller'
                   and s.symbol = 'MC001'
                   and s.close_run_id = ?
                """, closeRunId)).isEqualTo(10L);
        assertThat(queryLong("""
                select reserved_quantity
                  from stock_holding_snapshot s
                  join stock_account a on a.id = s.account_id
                 where a.user_key = 'market-close-seller'
                   and s.symbol = 'MC001'
                   and s.close_run_id = ?
                """, closeRunId)).isEqualTo(4L);
    }

    @Test
    void rolloverClosingPrices_multipleDailyExecutions_snapshotsBuySideOhlcInExecutionOrder() {
        LocalDate simulationDate = LocalDate.of(2026, 7, 4);
        setSimulationDate(simulationDate);
        insertOrderBookInstrument("MC_OHLC");
        insertPrice("MC_OHLC", "107.00", "100.00", "internal-order-book");
        insertAccount("market-close-ohlc-buyer", "1000000.00");
        insertAccount("market-close-ohlc-seller", "1000000.00");
        insertExecution(
                "market-close-ohlc-buyer", "MC_OHLC", "BUY", 1L, "999.00", "999.00",
                simulationDate.minusDays(1).atTime(15, 0)
        );
        insertExecution(
                "market-close-ohlc-seller", "MC_OHLC", "SELL", 1L, "999.00", "999.00",
                simulationDate.atTime(8, 0)
        );
        insertExecution(
                "market-close-ohlc-buyer", "MC_OHLC", "BUY", 1L, "100.00", "100.00",
                simulationDate.atTime(9, 0)
        );
        insertExecution(
                "market-close-ohlc-buyer", "MC_OHLC", "BUY", 1L, "130.00", "130.00",
                simulationDate.atTime(10, 0)
        );
        insertExecution(
                "market-close-ohlc-buyer", "MC_OHLC", "BUY", 1L, "90.00", "90.00",
                simulationDate.atTime(11, 0)
        );
        insertExecution(
                "market-close-ohlc-buyer", "MC_OHLC", "BUY", 1L, "105.00", "105.00",
                simulationDate.atTime(15, 0)
        );
        insertExecution(
                "market-close-ohlc-buyer", "MC_OHLC", "BUY", 1L, "107.00", "107.00",
                simulationDate.atTime(15, 0)
        );
        insertExecution(
                "market-close-ohlc-seller", "MC_OHLC", "SELL", 1L, "1.00", "1.00",
                simulationDate.atTime(16, 0)
        );
        insertExecution(
                "market-close-ohlc-buyer", "MC_OHLC", "BUY", 1L, "777.00", "777.00",
                simulationDate.plusDays(1).atTime(9, 0)
        );

        marketCloseRolloverService.rolloverClosingPrices();
        aggregateFullMarketReports(simulationDate);

        Long closeRunId = queryLong("select max(id) from stock_market_close_run");
        List<BigDecimal> ohlc = jdbcTemplate.queryForObject(
                """
                select open_price, high_price, low_price, last_execution_price
                  from stock_order_book_daily_snapshot
                 where close_run_id = ?
                   and symbol = 'MC_OHLC'
                """,
                (rs, rowNum) -> List.of(
                        rs.getBigDecimal("open_price"),
                        rs.getBigDecimal("high_price"),
                        rs.getBigDecimal("low_price"),
                        rs.getBigDecimal("last_execution_price")
                ),
                closeRunId
        );

        assertThat(ohlc).containsExactly(
                new BigDecimal("100.00"),
                new BigDecimal("130.00"),
                new BigDecimal("90.00"),
                new BigDecimal("107.00")
        );
    }

    @Test
    void rolloverClosingPrices_withSymbol_closesOnlyThatOrderBookSymbol() {
        insertOrderBookInstrument("MC101");
        insertOrderBookInstrument("MC102");
        insertPrice("MC101", "73500.00", "70000.00", "internal-order-book");
        insertPrice("MC102", "88000.00", "80000.00", "internal-order-book");
        insertAccount("market-close-buyer-a", "1000000.00");
        insertAccount("market-close-seller-a", "500000.00");
        insertAccount("market-close-buyer-b", "1000000.00");
        insertAccount("market-close-seller-b", "500000.00");
        insertHolding("market-close-seller-a", "MC101", 10L, 4L, "70000.00");
        insertHolding("market-close-seller-b", "MC102", 20L, 6L, "80000.00");
        insertReservedBuyOrder("market-close-buy-order-a", "market-close-buyer-a", "MC101", "147000.00");
        insertReservedSellOrder("market-close-sell-order-a", "market-close-seller-a", "MC101", 4L);
        insertReservedBuyOrder("market-close-buy-order-b", "market-close-buyer-b", "MC102", "176000.00");
        insertReservedSellOrder("market-close-sell-order-b", "market-close-seller-b", "MC102", 6L);

        int processedCount = marketCloseRolloverService.rolloverClosingPrices("mc101");

        Long closeRunId = queryLong("select max(id) from stock_market_close_run");
        assertThat(processedCount).isEqualTo(4);
        assertThat(queryString("select symbol from stock_market_close_run where id = " + closeRunId))
                .isEqualTo("MC101");
        assertThat(queryLong("select cancelled_order_count from stock_market_close_run where id = " + closeRunId))
                .isEqualTo(2L);
        assertThat(queryLong("select holding_snapshot_count from stock_market_close_run where id = " + closeRunId))
                .isEqualTo(1L);
        assertThat(queryLong("select price_rollover_count from stock_market_close_run where id = " + closeRunId))
                .isEqualTo(1L);
        assertThat(queryLong("select count(*) from stock_order where symbol = 'MC101' and status = 'CANCELLED'"))
                .isEqualTo(2L);
        assertThat(queryLong("select count(*) from stock_order where symbol = 'MC102' and status = 'PENDING'"))
                .isEqualTo(2L);
        assertThat(queryDecimal("select previous_close from stock_price where symbol = 'MC101'"))
                .isEqualByComparingTo(new BigDecimal("73500.00"));
        assertThat(queryDecimal("select previous_close from stock_price where symbol = 'MC102'"))
                .isEqualByComparingTo(new BigDecimal("80000.00"));
        assertThat(queryLong("""
                select count(*)
                  from stock_holding_snapshot
                 where close_run_id = ?
                   and symbol = 'MC101'
                """, closeRunId)).isEqualTo(1L);
        assertThat(queryLong("""
                select count(*)
                  from stock_holding_snapshot
                 where close_run_id = ?
                   and symbol = 'MC102'
                """, closeRunId)).isZero();
        assertThat(queryLong("""
                select count(*)
                  from stock_order_book_daily_snapshot
                 where close_run_id = ?
                   and symbol = 'MC101'
                """, closeRunId)).isZero();
        assertThat(queryLong("""
                select count(*)
                  from stock_order_book_daily_snapshot
                 where close_run_id = ?
                   and symbol = 'MC102'
                """, closeRunId)).isZero();
    }

    @Test
    void cancelOpenOrderBookOrders_cancelsOrdersAndReleasesReservationsWithoutCloseRun() {
        insertPrice("MC201", "73500.00", "70000.00", "internal-order-book");
        insertAccount("market-halt-buyer", "1000000.00");
        insertAccount("market-halt-seller", "500000.00");
        insertHolding("market-halt-seller", "MC201", 10L, 4L, "70000.00");
        insertReservedBuyOrder("market-halt-buy-order", "market-halt-buyer", "MC201", "147000.00");
        insertReservedSellOrder("market-halt-sell-order", "market-halt-seller", "MC201", 4L);

        int processedCount = marketCloseRolloverService.cancelOpenOrderBookOrders("mc201");

        assertThat(processedCount).isEqualTo(2);
        assertThat(queryLong("select count(*) from stock_order where symbol = 'MC201' and status = 'CANCELLED' and reserved_cash = 0"))
                .isEqualTo(2L);
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'market-halt-buyer'"))
                .isEqualByComparingTo(new BigDecimal("1000000.00"));
        assertThat(queryLong("""
                select reserved_quantity
                  from stock_holding h
                  join stock_account a on a.id = h.account_id
                 where a.user_key = 'market-halt-seller'
                   and h.symbol = 'MC201'
                """)).isZero();
        assertThat(queryLong("select count(*) from stock_market_close_run"))
                .isZero();
        assertThat(queryLong("select count(*) from stock_holding_snapshot"))
                .isZero();
        assertThat(queryDecimal("select previous_close from stock_price where symbol = 'MC201'"))
                .isEqualByComparingTo(new BigDecimal("70000.00"));
    }

    @Test
    void rolloverClosingPrices_sameDaySecondClose_reusesCompletedLogicalCycle() {
        LocalDate simulationDate = LocalDate.of(2026, 1, 3);
        setSimulationDate(simulationDate);
        insertOrderBookInstrument("MC002");
        insertPrice("MC002", "73500.00", "70000.00", "internal-order-book");
        insertAccount("market-close-holder", "500000.00");
        insertHolding("market-close-holder", "MC002", 10L, 0L, "70000.00");

        int firstProcessedCount = marketCloseRolloverService.rolloverClosingPrices();
        Long firstCloseRunId = queryLong("select max(id) from stock_market_close_run");
        jdbcTemplate.update(
                """
                update stock_holding
                   set quantity = 25,
                       updated_at = ?
                 where symbol = 'MC002'
                """,
                LocalDateTime.now()
        );
        int secondProcessedCount = marketCloseRolloverService.rolloverClosingPrices();
        Long secondCloseRunId = queryLong("select max(id) from stock_market_close_run");
        aggregateFullMarketReports(simulationDate);

        assertThat(firstProcessedCount).isEqualTo(2);
        assertThat(secondProcessedCount).isZero();
        assertThat(secondCloseRunId).isEqualTo(firstCloseRunId);
        assertThat(queryLong("select count(*) from stock_market_close_run where business_date = ?", simulationDate))
                .isOne();
        assertThat(queryLong("""
                select count(*)
                  from stock_post_close_cycle
                 where business_date = ?
                   and scope_type = 'FULL_MARKET'
                   and scope_key = 'ALL'
                """, simulationDate)).isOne();
        assertThat(queryLong("""
                select quantity
                  from stock_holding_snapshot
                 where symbol = 'MC002'
                   and close_run_id = ?
                """, firstCloseRunId)).isEqualTo(10L);
        assertThat(queryLong("select count(*) from stock_holding_snapshot where symbol = 'MC002'"))
                .isOne();
        assertThat(queryLong("""
                select count(*)
                  from stock_order_book_daily_snapshot
                 where symbol = 'MC002'
                   and simulation_trade_date = ?
                """, simulationDate)).isOne();
    }

    private void aggregateFullMarketReports(LocalDate businessDate) {
        Long closeCycleId = queryLong("""
                select id
                  from stock_post_close_cycle
                 where business_date = ?
                   and scope_type = 'FULL_MARKET'
                   and scope_key = 'ALL'
                """, businessDate);
        jdbcTemplate.update(
                "update stock_post_close_cycle set phase = 'CORPORATE_CASH_APPLIED' where id = ?",
                closeCycleId
        );
        LocalDateTime aggregatedAt = businessDate.plusDays(1).atTime(0, 10);
        aggregateReportChunks(closeCycleId, aggregatedAt, true);
        aggregateReportChunks(closeCycleId, aggregatedAt, false);
    }

    private void aggregateReportChunks(long closeCycleId, LocalDateTime aggregatedAt, boolean symbolReport) {
        String afterSymbol = "";
        while (true) {
            PostCloseReportAggregationService.ReportAggregationChunk chunk = symbolReport
                    ? postCloseReportAggregationService.aggregateOrderBookDailySnapshotChunk(
                            closeCycleId,
                            aggregatedAt,
                            afterSymbol
                    )
                    : postCloseReportAggregationService.aggregateAccountDailySnapshotChunk(
                            closeCycleId,
                            aggregatedAt,
                            afterSymbol
                    );
            if (chunk.finished()) {
                return;
            }
            afterSymbol = chunk.lastSymbol();
        }
    }

    private void setSimulationDate(LocalDate simulationDate) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                """
                merge into stock_simulation_clock(
                    clock_id,
                    base_simulation_date,
                    real_seconds_per_simulation_day,
                    accumulated_real_seconds,
                    running,
                    last_started_at,
                    last_heartbeat_at,
                    timezone,
                    created_at,
                    updated_at
                ) key(clock_id) values ('DEFAULT', ?, 7200, ?, false, null, null, 'Asia/Seoul', ?, ?)
                """,
                simulationDate,
                POST_CLOSE_ACCUMULATED_REAL_SECONDS,
                now,
                now
        );
    }

    private void insertPrice(String symbol, String currentPrice, String previousClose, String provider) {
        jdbcTemplate.update(
                """
                insert into stock_price(symbol, current_price, previous_close, price_time, provider)
                values (?, ?, ?, ?, ?)
                """,
                symbol,
                new BigDecimal(currentPrice),
                new BigDecimal(previousClose),
                LocalDateTime.now(),
                provider
        );
    }

    private void insertOrderBookInstrument(String symbol) {
        jdbcTemplate.update(
                """
                insert into stock_order_book_instrument(
                    symbol, name, market, initial_price, issued_shares, tradable_shares, enabled, created_at, updated_at
                )
                values (?, ?, 'ORDERBOOK', 70000.00, 1000, 1000, true, ?, ?)
                """,
                symbol,
                symbol + " 주문장",
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private void insertOrderBookMarketConfig(String symbol) {
        jdbcTemplate.update(
                """
                insert into stock_order_book_market_config(symbol, enabled, market_status, updated_at)
                values (?, true, 'OPEN', ?)
                """,
                symbol,
                LocalDateTime.now()
        );
    }

    private void insertAccount(String userKey, String cashBalance) {
        jdbcTemplate.update(
                "insert into stock_account(user_key, cash_balance, created_at, updated_at) values (?, ?, ?, ?)",
                userKey,
                new BigDecimal(cashBalance),
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private void insertHolding(String userKey, String symbol, long quantity, long reservedQuantity, String averagePrice) {
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at)
                select id, ?, ?, ?, ?, ?
                  from stock_account
                 where user_key = ?
                """,
                symbol,
                quantity,
                reservedQuantity,
                new BigDecimal(averagePrice),
                LocalDateTime.now(),
                userKey
        );
    }

    private void insertReservedBuyOrder(String clientOrderId, String userKey, String symbol, String reservedCash) {
        BigDecimal reservedCashAmount = new BigDecimal(reservedCash);
        jdbcTemplate.update(
                "update stock_account set cash_balance = cash_balance - ?, updated_at = ? where user_key = ?",
                reservedCashAmount,
                LocalDateTime.now(),
                userKey
        );
        jdbcTemplate.update(
                """
                insert into stock_order(
                  client_order_id, account_id, symbol, market_type, side, order_type, status, limit_price,
                  quantity, filled_quantity, reserved_cash, created_at, updated_at
                )
                select ?, id, ?, 'ORDER_BOOK', 'BUY', 'LIMIT', 'PENDING', 73500.00, 2, 0, ?, ?, ?
                  from stock_account
                 where user_key = ?
                """,
                clientOrderId,
                symbol,
                reservedCashAmount,
                LocalDateTime.now(),
                LocalDateTime.now(),
                userKey
        );
    }

    private void insertReservedSellOrder(String clientOrderId, String userKey, String symbol, long quantity) {
        jdbcTemplate.update(
                """
                insert into stock_order(
                  client_order_id, account_id, symbol, market_type, side, order_type, status, limit_price,
                  quantity, filled_quantity, reserved_cash, created_at, updated_at
                )
                select ?, id, ?, 'ORDER_BOOK', 'SELL', 'LIMIT', 'PENDING', 73500.00, ?, 0, 0, ?, ?
                  from stock_account
                 where user_key = ?
                """,
                clientOrderId,
                symbol,
                quantity,
                LocalDateTime.now(),
                LocalDateTime.now(),
                userKey
        );
    }

    private void insertExecution(
            String userKey,
            String symbol,
            String side,
            long quantity,
            String price,
            String grossAmount,
            LocalDateTime executedAt
    ) {
        jdbcTemplate.update(
                """
                insert into stock_execution(
                    order_id, account_id, symbol, side, quantity, price, gross_amount, net_amount,
                    fee_amount, tax_amount, realized_profit, source, executed_at
                )
                select ?, id, ?, ?, ?, ?, ?, ?, 0, 0, 0, 'INTERNAL_ORDER_BOOK', ?
                  from stock_account
                 where user_key = ?
                """,
                Math.abs((symbol + side + executedAt).hashCode()),
                symbol,
                side,
                quantity,
                new BigDecimal(price),
                new BigDecimal(grossAmount),
                new BigDecimal(grossAmount),
                executedAt,
                userKey
        );
    }

    private BigDecimal queryDecimal(String sql) {
        return jdbcTemplate.queryForObject(sql, BigDecimal.class);
    }

    private BigDecimal queryDecimal(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, BigDecimal.class, args);
    }

    private Long queryLong(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    private Long queryLong(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private String queryString(String sql) {
        return jdbcTemplate.queryForObject(sql, String.class);
    }
}
