package stock.batch.service.batch.automarket.writer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.execution.queue.NoopOrderBookReadySymbolQueue;
import stock.batch.service.testsupport.BatchTestDatabaseFactory;

class AutoMarketWriterBulkReservationTest {

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private AutoMarketWriter writer;

    @BeforeEach
    void setUp() {
        DataSource dataSource = BatchTestDatabaseFactory.createDataSource("auto_market_bulk_reservation");
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbcTemplate.execute("""
                create table stock_account(
                    id bigint primary key,
                    status varchar(30) not null,
                    cash_balance decimal(19, 2) not null,
                    updated_at timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table stock_holding(
                    id bigint auto_increment primary key,
                    account_id bigint not null,
                    symbol varchar(20) not null,
                    quantity bigint not null,
                    reserved_quantity bigint not null,
                    updated_at timestamp,
                    unique(account_id, symbol)
                )
                """);
        jdbcTemplate.update("""
                insert into stock_account(id, status, cash_balance)
                values (1, 'ACTIVE', 10000), (2, 'ACTIVE', 8000)
                """);
        jdbcTemplate.update("""
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity)
                values (1, 'STOCK001', 10, 2), (2, 'STOCK002', 20, 3)
                """);
        writer = new AutoMarketWriter(
                jdbcTemplate,
                new NoopOrderBookReadySymbolQueue(),
                new SimpleMeterRegistry()
        );
    }

    @Test
    void bulkReservation_exactCohorts_reservesCashAndHoldingsInFixedWrites() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 9, 0);
        AutoMarketWriter.HoldingReservationKey firstKey =
                new AutoMarketWriter.HoldingReservationKey(1L, "STOCK001");
        AutoMarketWriter.HoldingReservationKey secondKey =
                new AutoMarketWriter.HoldingReservationKey(2L, "STOCK002");

        String transactionOutcome = transactionTemplate.execute(status -> {
            Map<Long, AutoMarketWriter.AccountReservationState> accountStates =
                    writer.lockAccountReservationStatesForUpdate(List.of(2L, 1L));
            Map<AutoMarketWriter.HoldingReservationKey, AutoMarketWriter.HoldingReservationState> holdingStates =
                    writer.lockHoldingReservationStatesForUpdate(List.of(secondKey, firstKey));
            int cashUpdates = writer.reserveBuyCashChunk(
                    Map.of(1L, new BigDecimal("2500.00"), 2L, new BigDecimal("1500.00")),
                    now
            );
            int holdingUpdates = writer.reserveSellQuantityChunk(
                    Map.of(firstKey, 4L, secondKey, 5L),
                    now
            );
            return accountStates.size()
                    + ":" + holdingStates.size()
                    + ":" + holdingStates.get(firstKey).availableQuantity()
                    + ":" + (cashUpdates + holdingUpdates);
        });

        String outcome = transactionOutcome
                + ":" + jdbcTemplate.queryForObject(
                        "select sum(cash_balance) from stock_account",
                        BigDecimal.class
                )
                + ":" + jdbcTemplate.queryForObject(
                        "select sum(reserved_quantity) from stock_holding",
                        Long.class
                );
        assertThat(outcome).isEqualTo("2:2:8:4:14000.00:14");
    }
}
