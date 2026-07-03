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
class InternalOrderBookExecutionServiceTest {

    @Autowired
    private InternalOrderBookExecutionService internalOrderBookExecutionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from stock_execution");
        jdbcTemplate.update("delete from stock_order");
        jdbcTemplate.update("delete from stock_holding");
        jdbcTemplate.update("delete from stock_account");
        jdbcTemplate.update(
                """
                merge into stock_order_book_instrument(symbol, name, market, initial_price, issued_shares, tradable_shares, enabled, created_at, updated_at)
                key(symbol)
                values ('005930', '삼성전자 주문장', 'ORDERBOOK', 70000.00, 100000, 100000, true, current_timestamp, current_timestamp)
                """
        );
        jdbcTemplate.update(
                """
                merge into stock_order_book_market_config(symbol, enabled, market_status, updated_at)
                key(symbol)
                values ('005930', true, 'OPEN', current_timestamp)
                """
        );
    }

    @Test
    void executeEligibleOrders_crossedLimitOrders_partiallyFillsBuyAndFillsSell() {
        insertAccount("buyer", "9790000.00", "10000000.00");
        insertAccount("seller", "100000.00", "10000000.00");
        insertHolding("seller", "005930", 5, 2, "50000.00");
        insertOrder("buyer-order", "buyer", "005930", "BUY", "LIMIT", "PENDING", "70000.00", 3, 0, null, "210000.00", 1);
        insertOrder("seller-order", "seller", "005930", "SELL", "LIMIT", "PENDING", "69000.00", 2, 0, null, "0.00", 2);

        int matchCount = internalOrderBookExecutionService.executeEligibleOrders();

        assertThat(matchCount).isEqualTo(1);
        assertThat(queryString("select status from stock_order where client_order_id = 'buyer-order'"))
                .isEqualTo("PARTIALLY_FILLED");
        assertThat(queryLong("select filled_quantity from stock_order where client_order_id = 'buyer-order'"))
                .isEqualTo(2L);
        assertThat(queryDecimal("select average_fill_price from stock_order where client_order_id = 'buyer-order'"))
                .isEqualByComparingTo(new BigDecimal("69000.00"));
        assertThat(queryDecimal("select reserved_cash from stock_order where client_order_id = 'buyer-order'"))
                .isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'buyer'"))
                .isEqualByComparingTo(new BigDecimal("9792000.00"));

        assertThat(queryString("select status from stock_order where client_order_id = 'seller-order'"))
                .isEqualTo("FILLED");
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'seller'"))
                .isEqualByComparingTo(new BigDecimal("238000.00"));
        assertThat(queryLong("select quantity from stock_holding h join stock_account a on a.id = h.account_id where a.user_key = 'seller' and symbol = '005930'"))
                .isEqualTo(3L);
        assertThat(queryLong("select reserved_quantity from stock_holding h join stock_account a on a.id = h.account_id where a.user_key = 'seller' and symbol = '005930'"))
                .isZero();

        assertThat(queryLong("select quantity from stock_holding h join stock_account a on a.id = h.account_id where a.user_key = 'buyer' and symbol = '005930'"))
                .isEqualTo(2L);
        assertThat(queryDecimal("select average_price from stock_holding h join stock_account a on a.id = h.account_id where a.user_key = 'buyer' and symbol = '005930'"))
                .isEqualByComparingTo(new BigDecimal("69000.00"));
        assertThat(queryLong("select count(*) from stock_execution where source = 'INTERNAL_ORDER_BOOK'"))
                .isEqualTo(2L);
    }

    @Test
    void executeEligibleOrders_buyOrderMatchedByMultipleSells_releasesCashAndCalculatesWeightedAverage() {
        insertAccount("multi-buyer", "9790000.00", "10000000.00");
        insertAccount("first-seller", "100000.00", "10000000.00");
        insertAccount("second-seller", "200000.00", "10000000.00");
        insertHolding("first-seller", "005930", 1, 1, "50000.00");
        insertHolding("second-seller", "005930", 2, 2, "51000.00");
        insertOrder("multi-buy", "multi-buyer", "005930", "BUY", "LIMIT", "PENDING", "70000.00", 3, 0, null, "210000.00", 1);
        insertOrder("first-sell", "first-seller", "005930", "SELL", "LIMIT", "PENDING", "69000.00", 1, 0, null, "0.00", 2);
        insertOrder("second-sell", "second-seller", "005930", "SELL", "LIMIT", "PENDING", "68000.00", 2, 0, null, "0.00", 3);

        int matchCount = internalOrderBookExecutionService.executeEligibleOrders();

        assertThat(matchCount).isEqualTo(2);
        assertThat(queryString("select status from stock_order where client_order_id = 'multi-buy'"))
                .isEqualTo("FILLED");
        assertThat(queryLong("select filled_quantity from stock_order where client_order_id = 'multi-buy'"))
                .isEqualTo(3L);
        assertThat(queryDecimal("select average_fill_price from stock_order where client_order_id = 'multi-buy'"))
                .isEqualByComparingTo(new BigDecimal("68333.33"));
        assertThat(queryDecimal("select reserved_cash from stock_order where client_order_id = 'multi-buy'"))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'multi-buyer'"))
                .isEqualByComparingTo(new BigDecimal("9795000.00"));
        assertThat(queryLong("select quantity from stock_holding h join stock_account a on a.id = h.account_id where a.user_key = 'multi-buyer' and symbol = '005930'"))
                .isEqualTo(3L);
        assertThat(queryDecimal("select average_price from stock_holding h join stock_account a on a.id = h.account_id where a.user_key = 'multi-buyer' and symbol = '005930'"))
                .isEqualByComparingTo(new BigDecimal("68333.33"));

        assertThat(queryString("select status from stock_order where client_order_id = 'first-sell'"))
                .isEqualTo("FILLED");
        assertThat(queryString("select status from stock_order where client_order_id = 'second-sell'"))
                .isEqualTo("FILLED");
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'first-seller'"))
                .isEqualByComparingTo(new BigDecimal("169000.00"));
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'second-seller'"))
                .isEqualByComparingTo(new BigDecimal("336000.00"));
        assertThat(queryLong("select count(*) from stock_execution where source = 'INTERNAL_ORDER_BOOK'"))
                .isEqualTo(4L);
    }

    @Test
    void executeEligibleOrders_concurrentRuns_locksMatchedOrdersAndMatchesOnce() throws Exception {
        insertAccount("concurrent-buyer", "9930000.00", "10000000.00");
        insertAccount("concurrent-seller", "100000.00", "10000000.00");
        insertHolding("concurrent-seller", "005930", 1, 1, "50000.00");
        insertOrder("concurrent-buy", "concurrent-buyer", "005930", "BUY", "LIMIT", "PENDING", "70000.00", 1, 0, null, "70000.00", 1);
        insertOrder("concurrent-sell", "concurrent-seller", "005930", "SELL", "LIMIT", "PENDING", "69000.00", 1, 0, null, "0.00", 2);
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Future<Integer> first = executor.submit(() -> executeAfterStart(start));
            Future<Integer> second = executor.submit(() -> executeAfterStart(start));

            start.countDown();

            int matchCount = first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS);

            assertThat(matchCount).isEqualTo(1);
            assertThat(queryLong("select count(*) from stock_execution where source = 'INTERNAL_ORDER_BOOK'"))
                    .isEqualTo(2L);
            assertThat(queryString("select status from stock_order where client_order_id = 'concurrent-buy'"))
                    .isEqualTo("FILLED");
            assertThat(queryString("select status from stock_order where client_order_id = 'concurrent-sell'"))
                    .isEqualTo("FILLED");
            assertThat(queryLong("select quantity from stock_holding h join stock_account a on a.id = h.account_id where a.user_key = 'concurrent-buyer' and symbol = '005930'"))
                    .isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void executeEligibleOrders_multipleSymbols_matchesEachSymbolThroughExecutionPool() {
        upsertOrderBookSymbol("000660");
        insertAccount("first-symbol-buyer", "9930000.00", "10000000.00");
        insertAccount("first-symbol-seller", "100000.00", "10000000.00");
        insertAccount("second-symbol-buyer", "9930000.00", "10000000.00");
        insertAccount("second-symbol-seller", "100000.00", "10000000.00");
        insertHolding("first-symbol-seller", "005930", 1, 1, "50000.00");
        insertHolding("second-symbol-seller", "000660", 1, 1, "50000.00");
        insertOrder("first-symbol-buy", "first-symbol-buyer", "005930", "BUY", "LIMIT", "PENDING", "70000.00", 1, 0, null, "70000.00", 1);
        insertOrder("first-symbol-sell", "first-symbol-seller", "005930", "SELL", "LIMIT", "PENDING", "69000.00", 1, 0, null, "0.00", 2);
        insertOrder("second-symbol-buy", "second-symbol-buyer", "000660", "BUY", "LIMIT", "PENDING", "80000.00", 1, 0, null, "80000.00", 1);
        insertOrder("second-symbol-sell", "second-symbol-seller", "000660", "SELL", "LIMIT", "PENDING", "79000.00", 1, 0, null, "0.00", 2);

        int matchCount = internalOrderBookExecutionService.executeEligibleOrders();

        assertThat(matchCount).isEqualTo(2);
        assertThat(queryString("select status from stock_order where client_order_id = 'first-symbol-buy'"))
                .isEqualTo("FILLED");
        assertThat(queryString("select status from stock_order where client_order_id = 'second-symbol-buy'"))
                .isEqualTo("FILLED");
        assertThat(queryLong("select count(*) from stock_execution where source = 'INTERNAL_ORDER_BOOK'"))
                .isEqualTo(4L);
    }

    @Test
    void executeEligibleOrders_sameUserCrossedOrders_doesNotSelfTrade() {
        insertAccount("same-user", "9790000.00", "10000000.00");
        insertHolding("same-user", "005930", 5, 2, "50000.00");
        insertOrder("same-buy", "same-user", "005930", "BUY", "LIMIT", "PENDING", "70000.00", 3, 0, null, "210000.00", 1);
        insertOrder("same-sell", "same-user", "005930", "SELL", "LIMIT", "PENDING", "69000.00", 2, 0, null, "0.00", 2);

        int matchCount = internalOrderBookExecutionService.executeEligibleOrders();

        assertThat(matchCount).isZero();
        assertThat(queryString("select status from stock_order where client_order_id = 'same-buy'"))
                .isEqualTo("PENDING");
        assertThat(queryString("select status from stock_order where client_order_id = 'same-sell'"))
                .isEqualTo("PENDING");
        assertThat(queryLong("select count(*) from stock_execution"))
                .isZero();
    }

    @Test
    void executeEligibleOrders_marketBuy_matchesBestLimitSellAndReleasesUnusedReserve() {
        insertAccount("market-buyer", "9928000.00", "10000000.00");
        insertAccount("market-seller", "100000.00", "10000000.00");
        insertHolding("market-seller", "005930", 1, 1, "50000.00");
        insertOrder("market-buy", "market-buyer", "005930", "BUY", "MARKET", "PENDING", null, 1, 0, null, "72000.00", 1);
        insertOrder("limit-sell-for-market-buy", "market-seller", "005930", "SELL", "LIMIT", "PENDING", "70000.00", 1, 0, null, "0.00", 2);

        int matchCount = internalOrderBookExecutionService.executeEligibleOrders();

        assertThat(matchCount).isEqualTo(1);
        assertThat(queryString("select status from stock_order where client_order_id = 'market-buy'"))
                .isEqualTo("FILLED");
        assertThat(queryDecimal("select reserved_cash from stock_order where client_order_id = 'market-buy'"))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'market-buyer'"))
                .isEqualByComparingTo(new BigDecimal("9930000.00"));
        assertThat(queryDecimal("select average_fill_price from stock_order where client_order_id = 'market-buy'"))
                .isEqualByComparingTo(new BigDecimal("70000.00"));
        assertThat(queryString("select status from stock_order where client_order_id = 'limit-sell-for-market-buy'"))
                .isEqualTo("FILLED");
        assertThat(queryLong("select quantity from stock_holding h join stock_account a on a.id = h.account_id where a.user_key = 'market-buyer' and symbol = '005930'"))
                .isEqualTo(1L);
        assertThat(queryLong("select count(*) from stock_execution where source = 'INTERNAL_ORDER_BOOK'"))
                .isEqualTo(2L);
    }

    @Test
    void executeEligibleOrders_marketBuyPartiallyFilled_releasesUnusedReserveForFilledQuantityOnly() {
        insertAccount("market-partial-buyer", "9856000.00", "10000000.00");
        insertAccount("market-partial-seller", "100000.00", "10000000.00");
        insertHolding("market-partial-seller", "005930", 1, 1, "50000.00");
        insertOrder("market-partial-buy", "market-partial-buyer", "005930", "BUY", "MARKET", "PENDING", null, 2, 0, null, "144000.00", 1);
        insertOrder("limit-sell-for-partial-market-buy", "market-partial-seller", "005930", "SELL", "LIMIT", "PENDING", "70000.00", 1, 0, null, "0.00", 2);

        int matchCount = internalOrderBookExecutionService.executeEligibleOrders();

        assertThat(matchCount).isEqualTo(1);
        assertThat(queryString("select status from stock_order where client_order_id = 'market-partial-buy'"))
                .isEqualTo("PARTIALLY_FILLED");
        assertThat(queryLong("select filled_quantity from stock_order where client_order_id = 'market-partial-buy'"))
                .isEqualTo(1L);
        assertThat(queryDecimal("select reserved_cash from stock_order where client_order_id = 'market-partial-buy'"))
                .isEqualByComparingTo(new BigDecimal("72000.00"));
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'market-partial-buyer'"))
                .isEqualByComparingTo(new BigDecimal("9858000.00"));
        assertThat(queryLong("select quantity from stock_holding h join stock_account a on a.id = h.account_id where a.user_key = 'market-partial-buyer' and symbol = '005930'"))
                .isEqualTo(1L);
        assertThat(queryString("select status from stock_order where client_order_id = 'limit-sell-for-partial-market-buy'"))
                .isEqualTo("FILLED");
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'market-partial-seller'"))
                .isEqualByComparingTo(new BigDecimal("170000.00"));
        assertThat(queryLong("select count(*) from stock_execution where source = 'INTERNAL_ORDER_BOOK'"))
                .isEqualTo(2L);
    }

    @Test
    void executeEligibleOrders_marketSell_matchesBestLimitBuy() {
        insertAccount("limit-buyer-for-market-sell", "9929000.00", "10000000.00");
        insertAccount("market-seller-for-limit-buy", "100000.00", "10000000.00");
        insertHolding("market-seller-for-limit-buy", "005930", 1, 1, "50000.00");
        insertOrder("limit-buy-for-market-sell", "limit-buyer-for-market-sell", "005930", "BUY", "LIMIT", "PENDING", "71000.00", 1, 0, null, "71000.00", 1);
        insertOrder("market-sell", "market-seller-for-limit-buy", "005930", "SELL", "MARKET", "PENDING", null, 1, 0, null, "0.00", 2);

        int matchCount = internalOrderBookExecutionService.executeEligibleOrders();

        assertThat(matchCount).isEqualTo(1);
        assertThat(queryString("select status from stock_order where client_order_id = 'limit-buy-for-market-sell'"))
                .isEqualTo("FILLED");
        assertThat(queryString("select status from stock_order where client_order_id = 'market-sell'"))
                .isEqualTo("FILLED");
        assertThat(queryDecimal("select average_fill_price from stock_order where client_order_id = 'market-sell'"))
                .isEqualByComparingTo(new BigDecimal("71000.00"));
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'market-seller-for-limit-buy'"))
                .isEqualByComparingTo(new BigDecimal("171000.00"));
        assertThat(queryLong("select quantity from stock_holding h join stock_account a on a.id = h.account_id where a.user_key = 'limit-buyer-for-market-sell' and symbol = '005930'"))
                .isEqualTo(1L);
        assertThat(queryLong("select count(*) from stock_execution where source = 'INTERNAL_ORDER_BOOK'"))
                .isEqualTo(2L);
    }

    @Test
    void executeEligibleOrders_marketBuyAndMarketSellWithoutLimitPrice_doNotMatch() {
        insertAccount("market-only-buyer", "9928000.00", "10000000.00");
        insertAccount("market-only-seller", "100000.00", "10000000.00");
        insertHolding("market-only-seller", "005930", 1, 1, "50000.00");
        insertOrder("market-only-buy", "market-only-buyer", "005930", "BUY", "MARKET", "PENDING", null, 1, 0, null, "72000.00", 1);
        insertOrder("market-only-sell", "market-only-seller", "005930", "SELL", "MARKET", "PENDING", null, 1, 0, null, "0.00", 2);

        int matchCount = internalOrderBookExecutionService.executeEligibleOrders();

        assertThat(matchCount).isZero();
        assertThat(queryString("select status from stock_order where client_order_id = 'market-only-buy'"))
                .isEqualTo("PENDING");
        assertThat(queryString("select status from stock_order where client_order_id = 'market-only-sell'"))
                .isEqualTo("PENDING");
        assertThat(queryLong("select count(*) from stock_execution"))
                .isZero();
    }

    @Test
    void executeEligibleOrders_buyWithInsufficientReservedCash_rejectsWithoutMatching() {
        insertAccount("broken-buyer", "0.00", "10000000.00");
        insertAccount("seller-for-broken-buy", "100000.00", "10000000.00");
        insertHolding("seller-for-broken-buy", "005930", 1, 1, "50000.00");
        insertOrder("broken-buy", "broken-buyer", "005930", "BUY", "LIMIT", "PENDING", "70000.00", 1, 0, null, "100.00", 1);
        insertOrder("seller-for-broken-buy-order", "seller-for-broken-buy", "005930", "SELL", "LIMIT", "PENDING", "69000.00", 1, 0, null, "0.00", 2);

        int matchCount = internalOrderBookExecutionService.executeEligibleOrders();

        assertThat(matchCount).isZero();
        assertThat(queryString("select status from stock_order where client_order_id = 'broken-buy'"))
                .isEqualTo("REJECTED");
        assertThat(queryDecimal("select reserved_cash from stock_order where client_order_id = 'broken-buy'"))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'broken-buyer'"))
                .isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(queryString("select status from stock_order where client_order_id = 'seller-for-broken-buy-order'"))
                .isEqualTo("PENDING");
        assertThat(queryLong("select count(*) from stock_execution"))
                .isZero();
    }

    @Test
    void executeEligibleOrders_sellWithInsufficientReservedHolding_rejectsWithoutMatching() {
        insertAccount("buyer-for-broken-sell", "9930000.00", "10000000.00");
        insertAccount("broken-seller", "100000.00", "10000000.00");
        insertHolding("broken-seller", "005930", 2, 1, "50000.00");
        insertOrder("buyer-for-broken-sell-order", "buyer-for-broken-sell", "005930", "BUY", "LIMIT", "PENDING", "70000.00", 2, 0, null, "140000.00", 1);
        insertOrder("broken-sell", "broken-seller", "005930", "SELL", "LIMIT", "PENDING", "69000.00", 2, 0, null, "0.00", 2);

        int matchCount = internalOrderBookExecutionService.executeEligibleOrders();

        assertThat(matchCount).isZero();
        assertThat(queryString("select status from stock_order where client_order_id = 'broken-sell'"))
                .isEqualTo("REJECTED");
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'broken-seller'"))
                .isEqualByComparingTo(new BigDecimal("100000.00"));
        assertThat(queryLong("select reserved_quantity from stock_holding h join stock_account a on a.id = h.account_id where a.user_key = 'broken-seller' and symbol = '005930'"))
                .isZero();
        assertThat(queryString("select status from stock_order where client_order_id = 'buyer-for-broken-sell-order'"))
                .isEqualTo("PENDING");
        assertThat(queryLong("select count(*) from stock_execution"))
                .isZero();
    }

    @Test
    void executeEligibleOrders_bestBuyCanOnlySelfTrade_matchesNextEligibleBuy() {
        insertAccount("same-user", "9790000.00", "10000000.00");
        insertAccount("other-buyer", "9861000.00", "10000000.00");
        insertHolding("same-user", "005930", 5, 2, "50000.00");
        insertOrder("same-buy", "same-user", "005930", "BUY", "LIMIT", "PENDING", "70000.00", 3, 0, null, "210000.00", 1);
        insertOrder("same-sell", "same-user", "005930", "SELL", "LIMIT", "PENDING", "69000.00", 2, 0, null, "0.00", 2);
        insertOrder("other-buy", "other-buyer", "005930", "BUY", "LIMIT", "PENDING", "69500.00", 2, 0, null, "139000.00", 3);

        int matchCount = internalOrderBookExecutionService.executeEligibleOrders();

        assertThat(matchCount).isEqualTo(1);
        assertThat(queryString("select status from stock_order where client_order_id = 'same-buy'"))
                .isEqualTo("PENDING");
        assertThat(queryString("select status from stock_order where client_order_id = 'same-sell'"))
                .isEqualTo("FILLED");
        assertThat(queryString("select status from stock_order where client_order_id = 'other-buy'"))
                .isEqualTo("FILLED");
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'other-buyer'"))
                .isEqualByComparingTo(new BigDecimal("9862000.00"));
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'same-user'"))
                .isEqualByComparingTo(new BigDecimal("9928000.00"));
        assertThat(queryLong("select count(*) from stock_execution where source = 'INTERNAL_ORDER_BOOK'"))
                .isEqualTo(2L);
    }

    private void insertAccount(String userKey, String cashBalance, String openingGrantAmount) {
        jdbcTemplate.update(
                "insert into stock_account(user_key, cash_balance, created_at, updated_at) values (?, ?, ?, ?)",
                userKey,
                new BigDecimal(cashBalance),
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        insertCashFlow(userKey, openingGrantAmount);
    }

    private void upsertOrderBookSymbol(String symbol) {
        jdbcTemplate.update(
                """
                merge into stock_order_book_instrument(symbol, name, market, initial_price, issued_shares, tradable_shares, enabled, created_at, updated_at)
                key(symbol)
                values (?, ?, 'ORDERBOOK', 70000.00, 100000, 100000, true, current_timestamp, current_timestamp)
                """,
                symbol,
                symbol + " 주문장"
        );
        jdbcTemplate.update(
                """
                merge into stock_order_book_market_config(symbol, enabled, market_status, updated_at)
                key(symbol)
                values (?, true, 'OPEN', current_timestamp)
                """,
                symbol
        );
    }

    private void insertCashFlow(String userKey, String amount) {
        jdbcTemplate.update(
                """
                insert into stock_account_cash_flow(account_id, flow_type, amount, reason, created_by, created_at)
                select id, 'DEPOSIT', ?, 'OPENING_GRANT', 'SYSTEM', ?
                from stock_account
                where user_key = ?
                """,
                new BigDecimal(amount),
                LocalDateTime.now(),
                userKey
        );
    }

    private void insertHolding(String userKey, String symbol, long quantity, long reservedQuantity, String averagePrice) {
        Long accountId = accountIdFor(userKey);
        jdbcTemplate.update(
                "insert into stock_holding(account_id, symbol, quantity, reserved_quantity, average_price, updated_at) values (?, ?, ?, ?, ?, ?)",
                accountId,
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
            String reservedCash,
            int secondsOffset
    ) {
        LocalDateTime createdAt = LocalDateTime.now().plusSeconds(secondsOffset);
        Long accountId = accountIdFor(userKey);
        jdbcTemplate.update(
                """
                insert into stock_order(
                  client_order_id, account_id, symbol, market_type, side, order_type, status, limit_price,
                  quantity, filled_quantity, average_fill_price, reserved_cash, created_at, updated_at
                ) values (?, ?, ?, 'ORDER_BOOK', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                clientOrderId,
                accountId,
                symbol,
                side,
                orderType,
                status,
                limitPrice == null ? null : new BigDecimal(limitPrice),
                quantity,
                filledQuantity,
                averageFillPrice == null ? null : new BigDecimal(averageFillPrice),
                new BigDecimal(reservedCash),
                createdAt,
                createdAt
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

    private Long accountIdFor(String userKey) {
        return jdbcTemplate.queryForObject(
                "select id from stock_account where user_key = ?",
                Long.class,
                userKey
        );
    }

    private int executeAfterStart(CountDownLatch start) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        return internalOrderBookExecutionService.executeEligibleOrders();
    }
}
