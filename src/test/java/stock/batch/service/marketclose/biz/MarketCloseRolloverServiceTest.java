package stock.batch.service.marketclose.biz;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class MarketCloseRolloverServiceTest {

    @Autowired
    private MarketCloseRolloverService marketCloseRolloverService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from stock_execution");
        jdbcTemplate.update("delete from stock_order");
        jdbcTemplate.update("delete from stock_holding_snapshot");
        jdbcTemplate.update("delete from stock_market_close_run");
        jdbcTemplate.update("delete from stock_holding");
        jdbcTemplate.update("delete from stock_account where user_key like 'market-close-%'");
        jdbcTemplate.update("delete from stock_price_tick");
        jdbcTemplate.update("delete from stock_price");
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
    void rolloverClosingPrices_cancelsOpenOrderBookOrdersAndSnapshotsHoldingsPerCloseRun() {
        insertPrice("MC001", "73500.00", "70000.00", "internal-order-book");
        insertAccount("market-close-buyer", "1000000.00");
        insertAccount("market-close-seller", "500000.00");
        insertHolding("market-close-seller", "MC001", 10L, 4L, "70000.00");
        insertReservedBuyOrder("market-close-buy-order", "market-close-buyer", "MC001", "147000.00");
        insertReservedSellOrder("market-close-sell-order", "market-close-seller", "MC001", 4L);

        int processedCount = marketCloseRolloverService.rolloverClosingPrices();

        assertThat(processedCount).isEqualTo(4);
        Long closeRunId = queryLong("select max(id) from stock_market_close_run");
        assertThat(queryLong("select cancelled_order_count from stock_market_close_run where id = " + closeRunId))
                .isEqualTo(2L);
        assertThat(queryLong("select holding_snapshot_count from stock_market_close_run where id = " + closeRunId))
                .isEqualTo(1L);
        assertThat(queryLong("select price_rollover_count from stock_market_close_run where id = " + closeRunId))
                .isEqualTo(1L);
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
    }

    @Test
    void rolloverClosingPrices_sameDayMultipleCloses_createSeparateHoldingSnapshots() {
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

        assertThat(firstProcessedCount).isEqualTo(2);
        assertThat(secondProcessedCount).isEqualTo(1);
        assertThat(secondCloseRunId).isGreaterThan(firstCloseRunId);
        assertThat(queryLong("select count(*) from stock_market_close_run where business_date = current_date"))
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

    private BigDecimal queryDecimal(String sql) {
        return jdbcTemplate.queryForObject(sql, BigDecimal.class);
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
