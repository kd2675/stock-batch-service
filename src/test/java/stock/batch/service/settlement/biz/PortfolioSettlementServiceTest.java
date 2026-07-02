package stock.batch.service.settlement.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class PortfolioSettlementServiceTest {

    @Autowired
    private PortfolioSettlementService portfolioSettlementService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from portfolio_snapshot");
        jdbcTemplate.update("delete from stock_order");
        jdbcTemplate.update("delete from stock_holding");
        jdbcTemplate.update("delete from stock_price");
        jdbcTemplate.update("delete from stock_account_cash_flow");
        jdbcTemplate.update("delete from stock_account");
        jdbcTemplate.update("delete from stock_simulation_clock");
    }

    @Test
    void settleToday_createsSnapshotFromCashAndHoldings() {
        insertAccount("ranker", "100000.00", "200000.00");
        insertPrice("005930", "70000.00");
        insertHolding("ranker", "005930", 2, "50000.00");

        int settledCount = portfolioSettlementService.settleToday();

        assertThat(settledCount).isEqualTo(1);
        assertThat(queryDecimal("select total_asset from portfolio_snapshot ps join stock_account a on a.id = ps.account_id where a.user_key = 'ranker'"))
                .isEqualByComparingTo(new BigDecimal("240000.00"));
        assertThat(queryDecimal("select market_value from portfolio_snapshot ps join stock_account a on a.id = ps.account_id where a.user_key = 'ranker'"))
                .isEqualByComparingTo(new BigDecimal("140000.00"));
        assertThat(queryDecimal("select return_rate from portfolio_snapshot ps join stock_account a on a.id = ps.account_id where a.user_key = 'ranker'"))
                .isEqualByComparingTo(new BigDecimal("20.0000"));
        assertThat(queryDate("select snapshot_date from portfolio_snapshot ps join stock_account a on a.id = ps.account_id where a.user_key = 'ranker'"))
                .isEqualTo(LocalDate.now());
    }

    @Test
    void settleToday_existingSnapshot_updatesSameDateRow() {
        insertAccount("ranker", "100000.00", "200000.00");
        insertPrice("005930", "70000.00");
        insertHolding("ranker", "005930", 2, "50000.00");
        portfolioSettlementService.settleToday();

        jdbcTemplate.update("update stock_price set current_price = 80000.00 where symbol = '005930'");
        portfolioSettlementService.settleToday();

        assertThat(queryLong("select count(*) from portfolio_snapshot ps join stock_account a on a.id = ps.account_id where a.user_key = 'ranker'"))
                .isEqualTo(1L);
        assertThat(queryDecimal("select total_asset from portfolio_snapshot ps join stock_account a on a.id = ps.account_id where a.user_key = 'ranker'"))
                .isEqualByComparingTo(new BigDecimal("260000.00"));
    }

    @Test
    void settleToday_pendingBuyOrder_includesReservedCashInTotalAsset() {
        insertAccount("buyer", "9860000.00", "10000000.00");
        insertPendingBuyOrder("buyer-order", "buyer", "005930", "140000.00");

        portfolioSettlementService.settleToday();

        assertThat(queryDecimal("select ps.cash_balance from portfolio_snapshot ps join stock_account a on a.id = ps.account_id where a.user_key = 'buyer'"))
                .isEqualByComparingTo(new BigDecimal("9860000.00"));
        assertThat(queryDecimal("select total_asset from portfolio_snapshot ps join stock_account a on a.id = ps.account_id where a.user_key = 'buyer'"))
                .isEqualByComparingTo(new BigDecimal("10000000.00"));
        assertThat(queryDecimal("select return_rate from portfolio_snapshot ps join stock_account a on a.id = ps.account_id where a.user_key = 'buyer'"))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void settleToday_holdingWithoutCurrentPrice_usesAveragePriceAsFallback() {
        insertAccount("order-book-user", "100000.00", "200000.00");
        insertHolding("order-book-user", "123456", 2, "50000.00");

        portfolioSettlementService.settleToday();

        assertThat(queryDecimal("select market_value from portfolio_snapshot ps join stock_account a on a.id = ps.account_id where a.user_key = 'order-book-user'"))
                .isEqualByComparingTo(new BigDecimal("100000.00"));
        assertThat(queryDecimal("select total_asset from portfolio_snapshot ps join stock_account a on a.id = ps.account_id where a.user_key = 'order-book-user'"))
                .isEqualByComparingTo(new BigDecimal("200000.00"));
        assertThat(queryDecimal("select return_rate from portfolio_snapshot ps join stock_account a on a.id = ps.account_id where a.user_key = 'order-book-user'"))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void settleToday_dividendPaymentImprovesReturnWithoutIncreasingPrincipal() {
        insertAccount("dividend-user", "110000.00", "200000.00");
        insertCashFlow("dividend-user", "10000.00", "DIVIDEND_PAYMENT");
        insertPrice("005930", "70000.00");
        insertHolding("dividend-user", "005930", 2, "50000.00");

        portfolioSettlementService.settleToday();

        assertThat(queryDecimal("select total_asset from portfolio_snapshot ps join stock_account a on a.id = ps.account_id where a.user_key = 'dividend-user'"))
                .isEqualByComparingTo(new BigDecimal("250000.00"));
        assertThat(queryDecimal("select return_rate from portfolio_snapshot ps join stock_account a on a.id = ps.account_id where a.user_key = 'dividend-user'"))
                .isEqualByComparingTo(new BigDecimal("25.0000"));
    }

    @Test
    void settleToday_listingSupplyAccount_isExcludedFromSnapshots() {
        insertAccount("stock-listing-zq001", "1.00", "1.00");

        int settledCount = portfolioSettlementService.settleToday();

        assertThat(settledCount).isZero();
        assertThat(queryLong("select count(*) from portfolio_snapshot")).isZero();
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

    private void insertCashFlow(String userKey, String amount) {
        insertCashFlow(userKey, amount, "OPENING_GRANT");
    }

    private void insertCashFlow(String userKey, String amount, String reason) {
        jdbcTemplate.update(
                """
                insert into stock_account_cash_flow(account_id, flow_type, amount, reason, created_by, created_at)
                select id, 'DEPOSIT', ?, ?, 'SYSTEM', ?
                from stock_account
                where user_key = ?
                """,
                new BigDecimal(amount),
                reason,
                LocalDateTime.now(),
                userKey
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

    private void insertHolding(String userKey, String symbol, long quantity, String averagePrice) {
        Long accountId = accountIdFor(userKey);
        jdbcTemplate.update(
                "insert into stock_holding(account_id, symbol, quantity, average_price, updated_at) values (?, ?, ?, ?, ?)",
                accountId,
                symbol,
                quantity,
                new BigDecimal(averagePrice),
                LocalDateTime.now()
        );
    }

    private void insertPendingBuyOrder(String clientOrderId, String userKey, String symbol, String reservedCash) {
        Long accountId = accountIdFor(userKey);
        jdbcTemplate.update(
                """
                insert into stock_order(
                  client_order_id, account_id, symbol, side, order_type, status, limit_price,
                  quantity, filled_quantity, reserved_cash, created_at, updated_at
                ) values (?, ?, ?, 'BUY', 'LIMIT', 'PENDING', 70000.00, 2, 0, ?, ?, ?)
                """,
                clientOrderId,
                accountId,
                symbol,
                new BigDecimal(reservedCash),
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private BigDecimal queryDecimal(String sql) {
        return jdbcTemplate.queryForObject(sql, BigDecimal.class);
    }

    private LocalDate queryDate(String sql) {
        return jdbcTemplate.queryForObject(sql, LocalDate.class);
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
}
