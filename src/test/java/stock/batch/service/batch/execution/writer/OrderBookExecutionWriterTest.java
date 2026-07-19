package stock.batch.service.batch.execution.writer;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import stock.batch.service.batch.common.support.StockHoldingReservationJdbcSupport;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderBookExecutionWriterTest {

    @Test
    void lockAccountsForUpdate_twoAccounts_locksInAscendingAccountIdOrder() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        OrderBookExecutionWriter writer = new OrderBookExecutionWriter(
                jdbcTemplate,
                mock(ExecutionHoldingJdbcSupport.class),
                mock(StockHoldingReservationJdbcSupport.class)
        );

        when(jdbcTemplate.queryForList(
                eq("select id from stock_account where id in (?, ?) order by id asc for update"),
                eq(Long.class),
                eq(10L),
                eq(20L)
        )).thenReturn(java.util.List.of(10L, 20L));

        writer.lockAccountsForUpdate(20L, 10L);

        verify(jdbcTemplate).queryForList(
                eq("select id from stock_account where id in (?, ?) order by id asc for update"),
                eq(Long.class),
                eq(10L),
                eq(20L)
        );
    }

    @Test
    void lockAccountsForUpdate_sameAccount_locksSingleAccount() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        OrderBookExecutionWriter writer = new OrderBookExecutionWriter(
                jdbcTemplate,
                mock(ExecutionHoldingJdbcSupport.class),
                mock(StockHoldingReservationJdbcSupport.class)
        );

        writer.lockAccountsForUpdate(10L, 10L);

        verify(jdbcTemplate).queryForList(
                eq("select id from stock_account where id = ? for update"),
                eq(Long.class),
                eq(10L)
        );
    }
}
