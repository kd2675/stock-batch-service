package stock.batch.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import stock.batch.service.execution.biz.OrderExecutionService;
import stock.batch.service.settlement.biz.PortfolioSettlementService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class StockBatchTradingFlowTest {

    @Autowired
    private OrderExecutionService orderExecutionService;

    @Autowired
    private PortfolioSettlementService portfolioSettlementService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from portfolio_snapshot");
        jdbcTemplate.update("delete from stock_execution");
        jdbcTemplate.update("delete from stock_order");
        jdbcTemplate.update("delete from stock_holding");
        jdbcTemplate.update("delete from stock_price");
        jdbcTemplate.update("delete from stock_account");
    }

    @Test
    void executeAndSettle_buyLimitOrder_updatesOrderLedgerHoldingsAndDailySnapshot() {
        insertAccount("flow-buyer", "9860000.00", "10000000.00");
        insertPrice("005930", "69000.00");
        insertBuyLimitOrder("flow-buy", "flow-buyer", "005930", "70000.00", 2, "140000.00");

        int executedCount = orderExecutionService.executeEligibleOrders();
        int settledCount = portfolioSettlementService.settleToday();

        assertThat(executedCount).isEqualTo(1);
        assertThat(settledCount).isEqualTo(1);
        assertThat(queryString("select status from stock_order where client_order_id = 'flow-buy'"))
                .isEqualTo("FILLED");
        assertThat(queryDecimal("select reserved_cash from stock_order where client_order_id = 'flow-buy'"))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'flow-buyer'"))
                .isEqualByComparingTo(new BigDecimal("9862000.00"));
        assertThat(queryLong("select quantity from stock_holding h join stock_account a on a.id = h.account_id where a.user_key = 'flow-buyer' and symbol = '005930'"))
                .isEqualTo(2L);
        assertThat(queryDecimal("select average_price from stock_holding h join stock_account a on a.id = h.account_id where a.user_key = 'flow-buyer' and symbol = '005930'"))
                .isEqualByComparingTo(new BigDecimal("69000.00"));
        assertThat(queryLong("select count(*) from stock_execution e join stock_account a on a.id = e.account_id where a.user_key = 'flow-buyer' and source = 'VIRTUAL_MARKET_PRICE'"))
                .isEqualTo(1L);
        assertThat(queryDate("select snapshot_date from portfolio_snapshot ps join stock_account a on a.id = ps.account_id where a.user_key = 'flow-buyer'"))
                .isEqualTo(LocalDate.now());
        assertThat(queryDecimal("select total_asset from portfolio_snapshot ps join stock_account a on a.id = ps.account_id where a.user_key = 'flow-buyer'"))
                .isEqualByComparingTo(new BigDecimal("10000000.00"));
        assertThat(queryDecimal("select market_value from portfolio_snapshot ps join stock_account a on a.id = ps.account_id where a.user_key = 'flow-buyer'"))
                .isEqualByComparingTo(new BigDecimal("138000.00"));
        assertThat(queryDecimal("select return_rate from portfolio_snapshot ps join stock_account a on a.id = ps.account_id where a.user_key = 'flow-buyer'"))
                .isEqualByComparingTo(BigDecimal.ZERO);
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

    private void insertPrice(String symbol, String currentPrice) {
        jdbcTemplate.update(
                """
                merge into stock_virtual_market_config(symbol, enabled, market_status, updated_at)
                key(symbol)
                values (?, true, 'OPEN', ?)
                """,
                symbol,
                LocalDateTime.now()
        );
        jdbcTemplate.update(
                "insert into stock_price(symbol, current_price, previous_close, price_time, provider) values (?, ?, ?, ?, 'test')",
                symbol,
                new BigDecimal(currentPrice),
                new BigDecimal(currentPrice),
                LocalDateTime.now()
        );
    }

    private void insertBuyLimitOrder(
            String clientOrderId,
            String userKey,
            String symbol,
            String limitPrice,
            long quantity,
            String reservedCash
    ) {
        Long accountId = jdbcTemplate.queryForObject(
                "select id from stock_account where user_key = ?",
                Long.class,
                userKey
        );
        jdbcTemplate.update(
                """
                insert into stock_order(
                  client_order_id, account_id, symbol, side, order_type, status, limit_price,
                  quantity, filled_quantity, reserved_cash, created_at, updated_at
                ) values (?, ?, ?, 'BUY', 'LIMIT', 'PENDING', ?, ?, 0, ?, ?, ?)
                """,
                clientOrderId,
                accountId,
                symbol,
                new BigDecimal(limitPrice),
                quantity,
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

    private LocalDate queryDate(String sql) {
        return jdbcTemplate.queryForObject(sql, LocalDate.class);
    }

    private Long queryLong(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }
}
