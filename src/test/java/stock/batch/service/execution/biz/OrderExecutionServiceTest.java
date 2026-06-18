package stock.batch.service.execution.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class OrderExecutionServiceTest {

    @Autowired
    private OrderExecutionService orderExecutionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from stock_execution");
        jdbcTemplate.update("delete from stock_order");
        jdbcTemplate.update("delete from stock_holding");
        jdbcTemplate.update("delete from stock_price");
        jdbcTemplate.update("delete from stock_account");
    }

    @Test
    void executeEligibleOrders_buyLimit_fillsOrderReleasesCashAndCreatesHolding() {
        insertAccount("buyer", "9860000.00", "10000000.00");
        insertPrice("005930", "69000.00");
        insertOrder("buyer-buy", "buyer", "005930", "BUY", "LIMIT", "PENDING", "70000.00", 2, 0, null, "140000.00");

        int executedCount = orderExecutionService.executeEligibleOrders();

        assertThat(executedCount).isEqualTo(1);
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'buyer'"))
                .isEqualByComparingTo(new BigDecimal("9862000.00"));
        assertThat(queryString("select status from stock_order where client_order_id = 'buyer-buy'"))
                .isEqualTo("FILLED");
        assertThat(queryLong("select quantity from stock_holding where user_key = 'buyer' and symbol = '005930'"))
                .isEqualTo(2L);
        assertThat(queryDecimal("select average_price from stock_holding where user_key = 'buyer' and symbol = '005930'"))
                .isEqualByComparingTo(new BigDecimal("69000.00"));
        assertThat(queryLong("select count(*) from stock_execution where user_key = 'buyer'"))
                .isEqualTo(1L);
    }

    @Test
    void executeEligibleOrders_concurrentRuns_locksOrderAndExecutesOnce() throws Exception {
        insertAccount("concurrent-buyer", "9930000.00", "10000000.00");
        insertPrice("005930", "69000.00");
        insertOrder("concurrent-buy", "concurrent-buyer", "005930", "BUY", "LIMIT", "PENDING", "70000.00", 1, 0, null, "70000.00");
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Future<Integer> first = executor.submit(() -> executeAfterStart(start));
            Future<Integer> second = executor.submit(() -> executeAfterStart(start));

            start.countDown();

            int executedCount = first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS);

            assertThat(executedCount).isEqualTo(1);
            assertThat(queryLong("select count(*) from stock_execution where order_id = (select id from stock_order where client_order_id = 'concurrent-buy')"))
                    .isEqualTo(1L);
            assertThat(queryLong("select quantity from stock_holding where user_key = 'concurrent-buyer' and symbol = '005930'"))
                    .isEqualTo(1L);
            assertThat(queryString("select status from stock_order where client_order_id = 'concurrent-buy'"))
                    .isEqualTo("FILLED");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void executeEligibleOrders_sellLimit_fillsOrderCreditsCashAndDecreasesHolding() {
        insertAccount("seller", "100000.00", "10000000.00");
        insertPrice("005930", "80000.00");
        insertHolding("seller", "005930", 5, 3, "50000.00");
        insertOrder("seller-sell", "seller", "005930", "SELL", "LIMIT", "PENDING", "75000.00", 3, 0, null, "0.00");

        int executedCount = orderExecutionService.executeEligibleOrders();

        assertThat(executedCount).isEqualTo(1);
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'seller'"))
                .isEqualByComparingTo(new BigDecimal("340000.00"));
        assertThat(queryString("select status from stock_order where client_order_id = 'seller-sell'"))
                .isEqualTo("FILLED");
        assertThat(queryLong("select quantity from stock_holding where user_key = 'seller' and symbol = '005930'"))
                .isEqualTo(2L);
        assertThat(queryLong("select reserved_quantity from stock_holding where user_key = 'seller' and symbol = '005930'"))
                .isZero();
        assertThat(queryLong("select count(*) from stock_execution where user_key = 'seller' and side = 'SELL'"))
                .isEqualTo(1L);
    }

    @Test
    void executeEligibleOrders_sellLimitWithInsufficientReservedHolding_rejectsWithoutCreditingCash() {
        insertAccount("broken-seller", "100000.00", "10000000.00");
        insertPrice("005930", "80000.00");
        insertHolding("broken-seller", "005930", 5, 1, "50000.00");
        insertOrder("broken-sell", "broken-seller", "005930", "SELL", "LIMIT", "PENDING", "75000.00", 3, 0, null, "0.00");

        int executedCount = orderExecutionService.executeEligibleOrders();

        assertThat(executedCount).isEqualTo(1);
        assertThat(queryString("select status from stock_order where client_order_id = 'broken-sell'"))
                .isEqualTo("REJECTED");
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'broken-seller'"))
                .isEqualByComparingTo(new BigDecimal("100000.00"));
        assertThat(queryLong("select quantity from stock_holding where user_key = 'broken-seller' and symbol = '005930'"))
                .isEqualTo(5L);
        assertThat(queryLong("select reserved_quantity from stock_holding where user_key = 'broken-seller' and symbol = '005930'"))
                .isZero();
        assertThat(queryLong("select count(*) from stock_execution where user_key = 'broken-seller'"))
                .isZero();
    }

    @Test
    void executeEligibleOrders_buyLimitAboveMarket_keepsPending() {
        insertAccount("waiter", "9860000.00", "10000000.00");
        insertPrice("005930", "71000.00");
        insertOrder("wait-buy", "waiter", "005930", "BUY", "LIMIT", "PENDING", "70000.00", 2, 0, null, "140000.00");

        int executedCount = orderExecutionService.executeEligibleOrders();

        assertThat(executedCount).isZero();
        assertThat(queryString("select status from stock_order where client_order_id = 'wait-buy'"))
                .isEqualTo("PENDING");
        assertThat(queryLong("select count(*) from stock_execution where user_key = 'waiter'"))
                .isZero();
    }

    @Test
    void executeEligibleOrders_partiallyFilledOrder_usesWeightedAverageFillPrice() {
        insertAccount("partial", "9790000.00", "10000000.00");
        insertPrice("005930", "69000.00");
        insertOrder(
                "partial-buy",
                "partial",
                "005930",
                "BUY",
                "LIMIT",
                "PARTIALLY_FILLED",
                "70000.00",
                3,
                1,
                "70000.00",
                "140000.00"
        );

        int executedCount = orderExecutionService.executeEligibleOrders();

        assertThat(executedCount).isEqualTo(1);
        assertThat(queryString("select status from stock_order where client_order_id = 'partial-buy'"))
                .isEqualTo("FILLED");
        assertThat(queryDecimal("select average_fill_price from stock_order where client_order_id = 'partial-buy'"))
                .isEqualByComparingTo(new BigDecimal("69333.33"));
        assertThat(queryDecimal("select reserved_cash from stock_order where client_order_id = 'partial-buy'"))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void executeEligibleOrders_marketBuyPriceRisesWithCashAvailable_chargesShortfallAndFillsOrder() {
        insertAccount("market-buyer", "9930000.00", "10000000.00");
        insertPrice("005930", "80000.00");
        insertOrder("market-buy", "market-buyer", "005930", "BUY", "MARKET", "PENDING", null, 1, 0, null, "70000.00");

        int executedCount = orderExecutionService.executeEligibleOrders();

        assertThat(executedCount).isEqualTo(1);
        assertThat(queryString("select status from stock_order where client_order_id = 'market-buy'"))
                .isEqualTo("FILLED");
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'market-buyer'"))
                .isEqualByComparingTo(new BigDecimal("9920000.00"));
        assertThat(queryLong("select quantity from stock_holding where user_key = 'market-buyer' and symbol = '005930'"))
                .isEqualTo(1L);
        assertThat(queryDecimal("select average_fill_price from stock_order where client_order_id = 'market-buy'"))
                .isEqualByComparingTo(new BigDecimal("80000.00"));
    }

    @Test
    void executeEligibleOrders_marketBuyPriceRisesWithoutCash_rejectsAndRefundsReservedCash() {
        insertAccount("market-reject", "0.00", "70000.00");
        insertPrice("005930", "80000.00");
        insertOrder("market-reject-buy", "market-reject", "005930", "BUY", "MARKET", "PENDING", null, 1, 0, null, "70000.00");

        int executedCount = orderExecutionService.executeEligibleOrders();

        assertThat(executedCount).isEqualTo(1);
        assertThat(queryString("select status from stock_order where client_order_id = 'market-reject-buy'"))
                .isEqualTo("REJECTED");
        assertThat(queryDecimal("select reserved_cash from stock_order where client_order_id = 'market-reject-buy'"))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'market-reject'"))
                .isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(queryLong("select count(*) from stock_holding where user_key = 'market-reject'"))
                .isZero();
        assertThat(queryLong("select count(*) from stock_execution where user_key = 'market-reject'"))
                .isZero();
    }

    private void insertAccount(String userKey, String cashBalance, String initialCash) {
        jdbcTemplate.update(
                "insert into stock_account(user_key, cash_balance, initial_cash, created_at, updated_at) values (?, ?, ?, ?, ?)",
                userKey,
                new BigDecimal(cashBalance),
                new BigDecimal(initialCash),
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private void insertPrice(String symbol, String currentPrice) {
        jdbcTemplate.update(
                "insert into stock_price(symbol, current_price, previous_close, price_time, provider) values (?, ?, ?, ?, 'test')",
                symbol,
                new BigDecimal(currentPrice),
                new BigDecimal(currentPrice),
                LocalDateTime.now()
        );
    }

    private void insertHolding(String userKey, String symbol, long quantity, long reservedQuantity, String averagePrice) {
        jdbcTemplate.update(
                "insert into stock_holding(user_key, symbol, quantity, reserved_quantity, average_price, updated_at) values (?, ?, ?, ?, ?, ?)",
                userKey,
                symbol,
                quantity,
                reservedQuantity,
                new BigDecimal(averagePrice),
                LocalDateTime.now()
        );
    }

    private void insertOrder(
            String clientOrderId,
            String userKey,
            String symbol,
            String side,
            String orderType,
            String status,
            String limitPrice,
            long quantity,
            long filledQuantity,
            String averageFillPrice,
            String reservedCash
    ) {
        jdbcTemplate.update(
                """
                insert into stock_order(
                  client_order_id, user_key, symbol, side, order_type, status, limit_price,
                  quantity, filled_quantity, average_fill_price, reserved_cash, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                clientOrderId,
                userKey,
                symbol,
                side,
                orderType,
                status,
                limitPrice == null ? null : new BigDecimal(limitPrice),
                quantity,
                filledQuantity,
                averageFillPrice == null ? null : new BigDecimal(averageFillPrice),
                new BigDecimal(reservedCash),
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private BigDecimal queryDecimal(String sql) {
        return jdbcTemplate.queryForObject(sql, BigDecimal.class);
    }

    private String queryString(String sql) {
        return jdbcTemplate.queryForObject(sql, String.class);
    }

    private Long queryLong(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    private int executeAfterStart(CountDownLatch start) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        return orderExecutionService.executeEligibleOrders();
    }
}
