package stock.batch.service.batch.settlement.job;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.batch.config.BatchRepositoryDataSourceConfig;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBatchTest
@SpringBootTest(properties = "stock.batch.settlement.chunk-size=2")
@ActiveProfiles("test")
class PortfolioSettlementJobIntegrationTest {

    private static final LocalDate TEST_SIMULATION_DATE = LocalDate.of(2026, 7, 1);
    private static final LocalDateTime TEST_SETTLEMENT_AT = LocalDateTime.of(2026, 7, 1, 18, 30);
    private static final long POST_CLOSE_ACCUMULATED_REAL_SECONDS = 5_550L;

    @Autowired
    private StockBatchJobLauncher stockBatchJobLauncher;

    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final JdbcTemplate batchMetadataJdbcTemplate;

    @Autowired
    PortfolioSettlementJobIntegrationTest(
            @Qualifier(BatchRepositoryDataSourceConfig.BATCH_METADATA_DATA_SOURCE) DataSource batchMetadataDataSource
    ) {
        this.batchMetadataJdbcTemplate = new JdbcTemplate(batchMetadataDataSource);
    }

    @BeforeEach
    void setUp() {
        jobRepositoryTestUtils.removeJobExecutions();
        jdbcTemplate.update("delete from portfolio_snapshot");
        jdbcTemplate.update("delete from stock_corporate_action_entitlement");
        jdbcTemplate.update("delete from stock_corporate_action");
        jdbcTemplate.update("delete from stock_order");
        jdbcTemplate.update("delete from stock_holding");
        jdbcTemplate.update("delete from stock_price");
        jdbcTemplate.update("delete from stock_account_cash_flow");
        jdbcTemplate.update("delete from stock_account");
        jdbcTemplate.update("delete from stock_market_close_run");
        jdbcTemplate.update("delete from stock_simulation_clock");
        insertSimulationClock();
        insertCompletedFullCloseRun(TEST_SIMULATION_DATE, TEST_SETTLEMENT_AT);
    }

    @Test
    void settleToday_createsSnapshotFromCashAndHoldings() {
        insertAccount("ranker", "100000.00", "200000.00");
        insertPrice("005930", "70000.00");
        insertHolding("ranker", "005930", 2, "50000.00");

        int settledCount = stockBatchJobLauncher.settlePortfolios().processedCount();

        assertThat(settledCount).isEqualTo(1);
        assertThat(queryDecimal("select total_asset from portfolio_snapshot ps join stock_account a on a.id = ps.account_id where a.user_key = 'ranker'"))
                .isEqualByComparingTo(new BigDecimal("240000.00"));
        assertThat(queryDecimal("select market_value from portfolio_snapshot ps join stock_account a on a.id = ps.account_id where a.user_key = 'ranker'"))
                .isEqualByComparingTo(new BigDecimal("140000.00"));
        assertThat(queryDecimal("select return_rate from portfolio_snapshot ps join stock_account a on a.id = ps.account_id where a.user_key = 'ranker'"))
                .isEqualByComparingTo(new BigDecimal("20.0000"));
        assertThat(queryDate("select snapshot_date from portfolio_snapshot ps join stock_account a on a.id = ps.account_id where a.user_key = 'ranker'"))
                .isEqualTo(TEST_SIMULATION_DATE);
    }

    @Test
    void settleToday_multiplePages_recordsRealChunkCommitCount() {
        for (int index = 1; index <= 5; index++) {
            insertAccount("paged-ranker-" + index, "100000.00", "100000.00");
        }

        int settledCount = stockBatchJobLauncher.settlePortfolios().processedCount();

        Long commitCount = batchMetadataJdbcTemplate.queryForObject(
                """
                select se.COMMIT_COUNT
                  from BATCH_STEP_EXECUTION se
                  join BATCH_JOB_EXECUTION je on je.JOB_EXECUTION_ID = se.JOB_EXECUTION_ID
                  join BATCH_JOB_INSTANCE ji on ji.JOB_INSTANCE_ID = je.JOB_INSTANCE_ID
                 where ji.JOB_NAME = ?
                   and se.STEP_NAME = ?
                 order by se.STEP_EXECUTION_ID desc
                 fetch first 1 row only
                """,
                Long.class,
                PortfolioSettlementJob.JOB_NAME,
                PortfolioSettlementJob.STEP_NAME
        );
        assertThat(settledCount + ":" + commitCount).isEqualTo("5:3");
    }

    @Test
    void settleToday_existingSnapshot_updatesSameDateRow() {
        insertAccount("ranker", "100000.00", "200000.00");
        insertPrice("005930", "70000.00");
        insertHolding("ranker", "005930", 2, "50000.00");
        stockBatchJobLauncher.settlePortfoliosForce(1L);

        jdbcTemplate.update("update stock_price set current_price = 80000.00 where symbol = '005930'");
        stockBatchJobLauncher.settlePortfoliosForce(2L);

        assertThat(queryLong("select count(*) from portfolio_snapshot ps join stock_account a on a.id = ps.account_id where a.user_key = 'ranker'"))
                .isEqualTo(1L);
        assertThat(queryDecimal("select total_asset from portfolio_snapshot ps join stock_account a on a.id = ps.account_id where a.user_key = 'ranker'"))
                .isEqualByComparingTo(new BigDecimal("260000.00"));
    }

    @Test
    void settle_withSnapshotDate_createsSnapshotForRequestedBusinessDate() {
        insertAccount("catch-up-ranker", "100000.00", "200000.00");
        insertPrice("005930", "70000.00");
        insertHolding("catch-up-ranker", "005930", 2, "50000.00");

        int settledCount = stockBatchJobLauncher.settlePortfolios(
                LocalDate.of(2026, 7, 3),
                LocalDateTime.of(2026, 7, 3, 18, 0)
        ).processedCount();

        assertThat(settledCount).isEqualTo(1);
        assertThat(queryDate("select snapshot_date from portfolio_snapshot ps join stock_account a on a.id = ps.account_id where a.user_key = 'catch-up-ranker'"))
                .isEqualTo(LocalDate.of(2026, 7, 3));
    }

    @Test
    void settleToday_pendingBuyOrder_includesReservedCashInTotalAsset() {
        insertAccount("buyer", "9860000.00", "10000000.00");
        insertPendingBuyOrder("buyer-order", "buyer", "005930", "140000.00");

        stockBatchJobLauncher.settlePortfolios();

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

        stockBatchJobLauncher.settlePortfolios();

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

        stockBatchJobLauncher.settlePortfolios();

        assertThat(queryDecimal("select total_asset from portfolio_snapshot ps join stock_account a on a.id = ps.account_id where a.user_key = 'dividend-user'"))
                .isEqualByComparingTo(new BigDecimal("250000.00"));
        assertThat(queryDecimal("select return_rate from portfolio_snapshot ps join stock_account a on a.id = ps.account_id where a.user_key = 'dividend-user'"))
                .isEqualByComparingTo(new BigDecimal("25.0000"));
    }

    @Test
    void settleToday_capitalIncreaseSubscriptionPreservesPrincipalBeforeAndAfterListing() {
        insertAccount("capital-settlement-user", "8000000.00", "10000000.00");
        insertCapitalIncreaseSubscriptionCashFlow("capital-settlement-user", "2000000.00");
        long entitlementId = insertSubscribedCapitalIncreaseEntitlement(
                "capital-settlement-user",
                "ZQ036",
                40L,
                "2000000.00"
        );

        stockBatchJobLauncher.settlePortfoliosForce(1L);
        String beforeListing = snapshotTotalAndReturn("capital-settlement-user");

        jdbcTemplate.update(
                "update stock_corporate_action_entitlement set status = 'PAID', paid_at = ? where id = ?",
                LocalDateTime.now(),
                entitlementId
        );
        insertPrice("ZQ036", "50000.00");
        insertHolding("capital-settlement-user", "ZQ036", 40L, "50000.00");
        stockBatchJobLauncher.settlePortfoliosForce(2L);

        assertThat(beforeListing + "|" + snapshotTotalAndReturn("capital-settlement-user"))
                .isEqualTo("10000000.00:0.0000|10000000.00:0.0000");
    }

    @Test
    void settleToday_listingSupplyAccount_isExcludedFromSnapshots() {
        insertAccount("stock-listing-zq001", "1.00", "1.00");

        int settledCount = stockBatchJobLauncher.settlePortfolios().processedCount();

        assertThat(settledCount).isZero();
        assertThat(queryLong("select count(*) from portfolio_snapshot")).isZero();
    }

    @Test
    void settleToday_withoutCompletedMarketCloseRun_skipsCurrentDateSnapshot() {
        // given
        jdbcTemplate.update("delete from stock_market_close_run");
        insertAccount("not-closed-ranker", "100000.00", "200000.00");

        // when
        int settledCount = stockBatchJobLauncher.settlePortfolios().processedCount();

        // then
        assertThat(settledCount).isZero();
        assertThat(queryLong("select count(*) from portfolio_snapshot")).isZero();
    }

    private void insertSimulationClock() {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                """
                insert into stock_simulation_clock(
                    clock_id, base_simulation_date, real_seconds_per_simulation_day,
                    accumulated_real_seconds, running, last_started_at, last_heartbeat_at,
                    timezone, created_at, updated_at
                )
                values ('DEFAULT', ?, 7200, ?, false, null, null, 'Asia/Seoul', ?, ?)
                """,
                TEST_SIMULATION_DATE,
                POST_CLOSE_ACCUMULATED_REAL_SECONDS,
                now,
                now
        );
    }

    private void insertCompletedFullCloseRun(LocalDate businessDate, LocalDateTime completedAt) {
        jdbcTemplate.update(
                """
                insert into stock_market_close_run(
                    symbol, business_date, closed_at, status,
                    cancelled_order_count, holding_snapshot_count, price_rollover_count,
                    created_at, completed_at
                )
                values (null, ?, ?, 'COMPLETED', 0, 0, 0, ?, ?)
                """,
                businessDate,
                completedAt,
                completedAt,
                completedAt
        );
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

    private void insertCapitalIncreaseSubscriptionCashFlow(String userKey, String amount) {
        jdbcTemplate.update(
                """
                insert into stock_account_cash_flow(account_id, flow_type, amount, reason, created_by, created_at)
                select id, 'WITHDRAW', ?, 'CAPITAL_INCREASE_SUBSCRIPTION', 'CORPORATE_ACTION_AUTO', ?
                  from stock_account
                 where user_key = ?
                """,
                new BigDecimal(amount),
                LocalDateTime.now(),
                userKey
        );
    }

    private long insertSubscribedCapitalIncreaseEntitlement(
            String userKey,
            String symbol,
            long shareQuantity,
            String cashAmount
    ) {
        long accountId = accountIdFor(userKey);
        jdbcTemplate.update(
                """
                insert into stock_corporate_action_entitlement(
                  action_id, account_id, symbol, quantity, share_quantity, cash_amount,
                  subscribed_share_quantity, subscribed_cash_amount, status,
                  holding_snapshot_run_id, created_at, subscribed_at, paid_at
                ) values (900001, ?, ?, ?, ?, ?, ?, ?, 'SUBSCRIBED', null, ?, ?, null)
                """,
                accountId,
                symbol,
                shareQuantity,
                shareQuantity,
                new BigDecimal(cashAmount),
                shareQuantity,
                new BigDecimal(cashAmount),
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        return jdbcTemplate.queryForObject(
                "select id from stock_corporate_action_entitlement where action_id = 900001 and account_id = ?",
                Long.class,
                accountId
        );
    }

    private String snapshotTotalAndReturn(String userKey) {
        return queryDecimal("select total_asset from portfolio_snapshot ps join stock_account a on a.id = ps.account_id where a.user_key = '" + userKey + "'")
                + ":" + queryDecimal("select return_rate from portfolio_snapshot ps join stock_account a on a.id = ps.account_id where a.user_key = '" + userKey + "'");
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
