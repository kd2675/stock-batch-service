package stock.batch.service.batch.automarket.writer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import javax.sql.DataSource;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.batch.automarket.model.AutoOrder;
import stock.batch.service.execution.queue.NoopOrderBookReadySymbolQueue;
import stock.batch.service.testsupport.BatchTestDatabaseFactory;

class AutoMarketWriterBulkCancellationTest {

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private AutoMarketWriter writer;

    @BeforeEach
    void setUp() {
        DataSource dataSource = BatchTestDatabaseFactory.createDataSource("auto_market_bulk_cancel");
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbcTemplate.execute("""
                create table stock_account(
                    id bigint primary key,
                    cash_balance decimal(19, 2) not null,
                    updated_at timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table stock_holding(
                    id bigint auto_increment primary key,
                    account_id bigint not null,
                    symbol varchar(20) not null,
                    reserved_quantity bigint not null,
                    updated_at timestamp,
                    unique(account_id, symbol)
                )
                """);
        jdbcTemplate.execute("""
                create table stock_order(
                    id bigint primary key,
                    status varchar(30) not null,
                    reserved_cash decimal(19, 2) not null,
                    updated_at timestamp
                )
                """);
        jdbcTemplate.update("insert into stock_account(id, cash_balance) values (1, 1000), (2, 1000), (3, 1000)");
        jdbcTemplate.update("""
                insert into stock_holding(account_id, symbol, reserved_quantity)
                values (2, 'STOCK001', 5), (2, 'STOCK002', 6)
                """);
        jdbcTemplate.update("""
                insert into stock_order(id, status, reserved_cash)
                values (1, 'PENDING', 3000),
                       (2, 'PENDING', 0),
                       (3, 'PARTIALLY_FILLED', 1000),
                       (4, 'PARTIALLY_FILLED', 0)
                """);
        writer = new AutoMarketWriter(
                jdbcTemplate,
                new NoopOrderBookReadySymbolQueue(),
                new SimpleMeterRegistry()
        );
    }

    @Test
    void bulkCancellation_exactPkCohort_cancelsAndReturnsReservationsInFixedWrites() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 9, 0);
        List<AutoOrder> orders = List.of(
                new AutoOrder(1L, 3L, "STOCK001", "BUY", 10L, 0L, new BigDecimal("3000.00")),
                new AutoOrder(2L, 2L, "STOCK002", "SELL", 8L, 2L, BigDecimal.ZERO),
                new AutoOrder(3L, 1L, "STOCK001", "BUY", 10L, 0L, new BigDecimal("1000.00")),
                new AutoOrder(4L, 2L, "STOCK001", "SELL", 5L, 0L, BigDecimal.ZERO)
        );

        Integer processed = transactionTemplate.execute(status -> {
            writer.lockAccountsForUpdate(orders.stream().map(AutoOrder::accountId).toList());
            writer.lockSellHoldingsForUpdate(orders);
            int cancelled = writer.cancelOpenOrders(orders, now);
            writer.creditCancelledBuyReservations(orders, now);
            writer.releaseCancelledSellReservations(orders, now);
            return cancelled;
        });

        String outcome = processed
                + ":" + jdbcTemplate.queryForObject(
                        "select count(*) from stock_order where status = 'CANCELLED'",
                        Long.class
                )
                + ":" + jdbcTemplate.queryForObject(
                        "select cash_balance from stock_account where id = 1",
                        BigDecimal.class
                )
                + ":" + jdbcTemplate.queryForObject(
                        "select cash_balance from stock_account where id = 3",
                        BigDecimal.class
                )
                + ":" + jdbcTemplate.queryForObject(
                        "select sum(reserved_quantity) from stock_holding where account_id = 2",
                        Long.class
                );

        assertThat(outcome).isEqualTo("4:4:2000.00:4000.00:0");
    }
}
