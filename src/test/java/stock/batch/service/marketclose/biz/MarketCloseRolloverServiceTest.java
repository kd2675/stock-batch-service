package stock.batch.service.marketclose.biz;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import stock.batch.service.batch.marketclose.writer.MarketCloseRolloverWriter;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class MarketCloseRolloverServiceTest {

    private static final long POST_CLOSE_ACCUMULATED_REAL_SECONDS = 5_550L;

    @Autowired
    private MarketCloseRolloverService marketCloseRolloverService;

    @Autowired
    private MarketCloseRolloverWriter marketCloseRolloverWriter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from stock_execution");
        jdbcTemplate.update("delete from stock_order");
        jdbcTemplate.update("delete from stock_holding_snapshot");
        jdbcTemplate.update("delete from stock_execution_daily_account_snapshot");
        jdbcTemplate.update("delete from stock_order_book_daily_snapshot");
        jdbcTemplate.update("delete from stock_market_close_run");
        jdbcTemplate.update("delete from stock_holding");
        jdbcTemplate.update("delete from stock_account where user_key like 'market-close-%'");
        jdbcTemplate.update("delete from stock_price_tick");
        jdbcTemplate.update("delete from stock_price");
        jdbcTemplate.update("delete from stock_order_book_market_config");
        jdbcTemplate.update("delete from stock_order_book_instrument");
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

    @Test
    void findCloseLockSymbols_withoutSymbol_includesAllFullCloseWriteTargets() {
        insertPrice("MC_PRICE_ONLY", "73500.00", "70000.00", "internal-order-book");
        insertOrderBookInstrument("MC_INSTRUMENT_ONLY");
        insertOrderBookMarketConfig("MC_MARKET_ONLY");
        insertAccount("market-close-lock-buyer", "1000000.00");
        insertReservedBuyOrder("market-close-lock-order", "market-close-lock-buyer", "MC_ORDER_ONLY", "147000.00");

        List<String> lockSymbols = marketCloseRolloverWriter.findCloseLockSymbols(null);

        assertThat(lockSymbols)
                .contains("MC_PRICE_ONLY", "MC_INSTRUMENT_ONLY", "MC_MARKET_ONLY", "MC_ORDER_ONLY");
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

        assertThat(processedCount).isEqualTo(7);
        Long closeRunId = queryLong("select max(id) from stock_market_close_run");
        assertThat(queryLong("select cancelled_order_count from stock_market_close_run where id = " + closeRunId))
                .isEqualTo(2L);
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
                .isZero();
        assertThat(queryLong("select holder_count from stock_order_book_daily_snapshot where close_run_id = ?", closeRunId))
                .isEqualTo(1L);
        assertThat(queryLong("select holding_quantity from stock_order_book_daily_snapshot where close_run_id = ?", closeRunId))
                .isEqualTo(10L);
        assertThat(queryLong("select count(*) from stock_order where symbol = 'MC001' and status = 'CANCELLED' and reserved_cash = 0"))
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
                """, closeRunId)).isZero();
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
        assertThat(processedCount).isEqualTo(5);
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
                """, closeRunId)).isEqualTo(1L);
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
    void rolloverClosingPrices_sameDayMultipleCloses_createSeparateHoldingSnapshots() {
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

        assertThat(firstProcessedCount).isEqualTo(3);
        assertThat(secondProcessedCount).isEqualTo(2);
        assertThat(secondCloseRunId).isGreaterThan(firstCloseRunId);
        assertThat(queryLong("select count(*) from stock_market_close_run where business_date = ?", simulationDate))
                .isEqualTo(2L);
        assertThat(queryLong("""
                select quantity
                  from stock_holding_snapshot
                 where symbol = 'MC002'
                   and close_run_id = ?
                """, firstCloseRunId)).isEqualTo(10L);
        assertThat(queryLong("""
                select quantity
                  from stock_holding_snapshot
                 where symbol = 'MC002'
                   and close_run_id = ?
                """, secondCloseRunId)).isEqualTo(25L);
        assertThat(queryLong("""
                select count(*)
                  from stock_order_book_daily_snapshot
                 where symbol = 'MC002'
                   and simulation_trade_date = ?
                """, simulationDate)).isEqualTo(2L);
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
