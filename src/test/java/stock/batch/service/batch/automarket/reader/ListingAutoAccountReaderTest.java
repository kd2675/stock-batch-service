package stock.batch.service.batch.automarket.reader;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import stock.batch.service.testsupport.BatchTestDatabaseFactory;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ListingAutoAccountReaderTest {

    @Test
    void scalarAccountQueries_readAvailableQuantityAndCashBalanceWithJdbcClient() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(
                BatchTestDatabaseFactory.createDataSource("listing_auto_account_reader_test")
        );
        jdbcTemplate.execute("drop table if exists stock_holding");
        jdbcTemplate.execute("drop table if exists stock_account");
        jdbcTemplate.execute(
                """
                create table stock_account(
                    id bigint primary key,
                    cash_balance decimal(19, 2)
                )
                """
        );
        jdbcTemplate.execute(
                """
                create table stock_holding(
                    id bigint auto_increment primary key,
                    account_id bigint,
                    symbol varchar(20),
                    quantity bigint,
                    reserved_quantity bigint
                )
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_account(id, cash_balance)
                values (10, 125000.50)
                """
        );
        jdbcTemplate.update(
                """
                insert into stock_holding(account_id, symbol, quantity, reserved_quantity)
                values (10, 'STOCK001', 30, 7)
                """
        );
        ListingAutoAccountReader reader = new ListingAutoAccountReader(jdbcTemplate);

        assertThat(reader.getAvailableQuantity(10L, "STOCK001")).isEqualTo(23L);
        assertThat(reader.getAvailableQuantity(10L, "UNKNOWN")).isZero();
        assertThat(reader.getCashBalance(10L)).isEqualByComparingTo(new BigDecimal("125000.50"));
        assertThat(reader.getCashBalance(999L)).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
