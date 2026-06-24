package stock.batch.service.execution.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "stock.batch.execution.fee-rate=0.001",
        "stock.batch.execution.sell-tax-rate=0.002"
})
@ActiveProfiles("test")
class ExecutionCostAccountingTest {

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
    void executeEligibleOrders_buyIncludesFeeInNetAmountAndAveragePrice() {
        insertAccount("fee-buyer", "9860000.00", "10000000.00");
        insertPrice("005930", "69000.00");
        insertOrder("fee-buy", "fee-buyer", "005930", "BUY", "LIMIT", "PENDING", "70000.00", 2, "140000.00");

        int executedCount = orderExecutionService.executeEligibleOrders();

        assertThat(executedCount).isEqualTo(1);
        assertThat(queryDecimal("select gross_amount from stock_execution e join stock_account a on a.id = e.account_id where a.user_key = 'fee-buyer'"))
                .isEqualByComparingTo(new BigDecimal("138000.00"));
        assertThat(queryDecimal("select fee_amount from stock_execution e join stock_account a on a.id = e.account_id where a.user_key = 'fee-buyer'"))
                .isEqualByComparingTo(new BigDecimal("138.00"));
        assertThat(queryDecimal("select tax_amount from stock_execution e join stock_account a on a.id = e.account_id where a.user_key = 'fee-buyer'"))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(queryDecimal("select net_amount from stock_execution e join stock_account a on a.id = e.account_id where a.user_key = 'fee-buyer'"))
                .isEqualByComparingTo(new BigDecimal("138138.00"));
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'fee-buyer'"))
                .isEqualByComparingTo(new BigDecimal("9861862.00"));
        assertThat(queryDecimal("select average_price from stock_holding h join stock_account a on a.id = h.account_id where a.user_key = 'fee-buyer' and symbol = '005930'"))
                .isEqualByComparingTo(new BigDecimal("69069.00"));
    }

    @Test
    void executeEligibleOrders_sellDeductsFeeAndTaxAndRecordsRealizedProfit() {
        insertAccount("tax-seller", "100000.00", "10000000.00");
        insertPrice("005930", "80000.00");
        insertHolding("tax-seller", "005930", 5, 3, "50000.00");
        insertOrder("tax-sell", "tax-seller", "005930", "SELL", "LIMIT", "PENDING", "75000.00", 3, "0.00");

        int executedCount = orderExecutionService.executeEligibleOrders();

        assertThat(executedCount).isEqualTo(1);
        assertThat(queryDecimal("select gross_amount from stock_execution e join stock_account a on a.id = e.account_id where a.user_key = 'tax-seller'"))
                .isEqualByComparingTo(new BigDecimal("240000.00"));
        assertThat(queryDecimal("select fee_amount from stock_execution e join stock_account a on a.id = e.account_id where a.user_key = 'tax-seller'"))
                .isEqualByComparingTo(new BigDecimal("240.00"));
        assertThat(queryDecimal("select tax_amount from stock_execution e join stock_account a on a.id = e.account_id where a.user_key = 'tax-seller'"))
                .isEqualByComparingTo(new BigDecimal("480.00"));
        assertThat(queryDecimal("select net_amount from stock_execution e join stock_account a on a.id = e.account_id where a.user_key = 'tax-seller'"))
                .isEqualByComparingTo(new BigDecimal("239280.00"));
        assertThat(queryDecimal("select realized_profit from stock_execution e join stock_account a on a.id = e.account_id where a.user_key = 'tax-seller'"))
                .isEqualByComparingTo(new BigDecimal("89280.00"));
        assertThat(queryDecimal("select cash_balance from stock_account where user_key = 'tax-seller'"))
                .isEqualByComparingTo(new BigDecimal("339280.00"));
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
            String reservedCash
    ) {
        Long accountId = accountIdFor(userKey);
        jdbcTemplate.update(
                """
                insert into stock_order(
                  client_order_id, account_id, symbol, market_type, side, order_type, status, limit_price,
                  quantity, filled_quantity, average_fill_price, reserved_cash, created_at, updated_at
                ) values (?, ?, ?, 'VIRTUAL_PRICE', ?, ?, ?, ?, ?, 0, null, ?, ?, ?)
                """,
                clientOrderId,
                accountId,
                symbol,
                side,
                orderType,
                status,
                limitPrice == null ? null : new BigDecimal(limitPrice),
                quantity,
                new BigDecimal(reservedCash),
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private BigDecimal queryDecimal(String sql) {
        return jdbcTemplate.queryForObject(sql, BigDecimal.class);
    }

    private Long accountIdFor(String userKey) {
        return jdbcTemplate.queryForObject(
                "select id from stock_account where user_key = ?",
                Long.class,
                userKey
        );
    }
}
