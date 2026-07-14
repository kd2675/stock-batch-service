package stock.batch.service.batch.settlement.reader;

import java.math.BigDecimal;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;

import stock.batch.service.batch.settlement.model.AccountSettlementTarget;
import stock.batch.service.testsupport.BatchTestDatabaseFactory;

import static org.assertj.core.api.Assertions.assertThat;

class AccountSettlementTargetReaderTest {

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private AccountSettlementTargetReader readerFactory;

    @BeforeEach
    void setUp() {
        dataSource = BatchTestDatabaseFactory.createDataSource("portfolio_paging_reader");
        jdbcTemplate = new JdbcTemplate(dataSource);
        createSchema();
        readerFactory = new AccountSettlementTargetReader(dataSource);
    }

    @Test
    void create_readsTargetsInStableAccountIdOrder() throws Exception {
        insertAccount(2L, "user-b", "200000.00");
        insertAccount(1L, "user-a", "100000.00");
        jdbcTemplate.update(
                "insert into stock_account_cash_flow(account_id, flow_type, amount, reason) values (1, 'DEPOSIT', 90000, 'OPENING_GRANT')"
        );
        jdbcTemplate.update(
                "insert into stock_holding(account_id, symbol, quantity, average_price) values (1, 'AAA', 2, 50000)"
        );
        jdbcTemplate.update(
                "insert into stock_price(symbol, current_price) values ('AAA', 60000)"
        );
        jdbcTemplate.update(
                "insert into stock_order(account_id, side, status, reserved_cash) values (1, 'BUY', 'PENDING', 30000)"
        );

        var reader = readerFactory.create(1, true);
        reader.open(new ExecutionContext());
        AccountSettlementTarget first = reader.read();
        AccountSettlementTarget second = reader.read();
        AccountSettlementTarget end = reader.read();
        reader.close();

        assertThat(first.accountId()).isEqualTo(1L);
        assertThat(first.cashBalance()).isEqualByComparingTo(new BigDecimal("100000.00"));
        assertThat(first.netCashFlow()).isEqualByComparingTo(new BigDecimal("90000.00"));
        assertThat(first.marketValue()).isEqualByComparingTo(new BigDecimal("120000.00"));
        assertThat(first.reservedBuyCash()).isEqualByComparingTo(new BigDecimal("30000.00"));
        assertThat(second.accountId()).isEqualTo(2L);
        assertThat(end).isNull();
    }

    @Test
    void create_whenSettlementIsIneligible_returnsNoRows() throws Exception {
        insertAccount(1L, "user-a", "100000.00");
        var reader = readerFactory.create(10, false);
        reader.open(new ExecutionContext());

        AccountSettlementTarget target = reader.read();
        reader.close();

        assertThat(target).isNull();
    }

    private void insertAccount(long id, String userKey, String cashBalance) {
        jdbcTemplate.update(
                "insert into stock_account(id, user_key, cash_balance, status) values (?, ?, ?, 'ACTIVE')",
                id,
                userKey,
                new BigDecimal(cashBalance)
        );
    }

    private void createSchema() {
        jdbcTemplate.execute("create table stock_account(id bigint primary key, user_key varchar(100), cash_balance decimal(19,2), status varchar(30))");
        jdbcTemplate.execute("create table stock_account_cash_flow(account_id bigint, flow_type varchar(20), amount decimal(19,2), reason varchar(100))");
        jdbcTemplate.execute("create table stock_holding(account_id bigint, symbol varchar(20), quantity bigint, average_price decimal(19,2))");
        jdbcTemplate.execute("create table stock_price(symbol varchar(20) primary key, current_price decimal(19,2))");
        jdbcTemplate.execute("create table stock_order(account_id bigint, side varchar(10), status varchar(30), reserved_cash decimal(19,2))");
        jdbcTemplate.execute("create table stock_corporate_action_entitlement(account_id bigint, status varchar(30), subscribed_cash_amount decimal(19,2))");
    }
}
