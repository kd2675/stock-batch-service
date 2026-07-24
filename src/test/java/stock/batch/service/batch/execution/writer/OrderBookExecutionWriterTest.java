package stock.batch.service.batch.execution.writer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import stock.batch.service.batch.common.support.StockHoldingReservationJdbcSupport;
import stock.batch.service.batch.execution.model.OrderBookOrderFillUpdate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class OrderBookExecutionWriterTest {

    private static final String EXACT_ACCOUNT_LOCK_SQL = """
            select stock_account.id
              from stock_account
              join (
                  select cast(? as decimal(19, 0)) as id
                  union all
                  select cast(? as decimal(19, 0)) as id
              ) selected_account
                on selected_account.id = stock_account.id
             order by stock_account.id asc
             for update
            """;
    private static final String MYSQL_EXACT_ACCOUNT_LOCK_SQL =
            EXACT_ACCOUNT_LOCK_SQL.replace("from stock_account", "from stock_account force index (primary)");

    @Test
    void lockAccountsForUpdate_twoAccounts_locksInAscendingAccountIdOrder() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        OrderBookExecutionWriter writer = new OrderBookExecutionWriter(
                jdbcTemplate,
                mock(ExecutionHoldingJdbcSupport.class),
                mock(StockHoldingReservationJdbcSupport.class)
        );

        when(jdbcTemplate.queryForList(
                eq(EXACT_ACCOUNT_LOCK_SQL),
                eq(Long.class),
                eq(10L),
                eq(20L)
        )).thenReturn(java.util.List.of(10L, 20L));

        writer.lockAccountsForUpdate(20L, 10L);

        verify(jdbcTemplate).queryForList(
                eq(EXACT_ACCOUNT_LOCK_SQL),
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

    @Test
    void lockAccountsForUpdate_mysql_forcesPrimaryKeyPointPlan() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenReturn("MySQL");
        when(jdbcTemplate.queryForList(
                eq(MYSQL_EXACT_ACCOUNT_LOCK_SQL),
                eq(Long.class),
                eq(10L),
                eq(20L)
        )).thenReturn(java.util.List.of(10L, 20L));
        OrderBookExecutionWriter writer = new OrderBookExecutionWriter(
                jdbcTemplate,
                mock(ExecutionHoldingJdbcSupport.class),
                mock(StockHoldingReservationJdbcSupport.class)
        );

        writer.lockAccountsForUpdate(10L, 20L);

        verify(jdbcTemplate).queryForList(
                eq(MYSQL_EXACT_ACCOUNT_LOCK_SQL),
                eq(Long.class),
                eq(10L),
                eq(20L)
        );
    }

    @Test
    void batchUpdates_mysql_useExactPrimaryKeyDerivedJoins() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenReturn("MySQL");
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(2);
        OrderBookExecutionWriter writer = new OrderBookExecutionWriter(
                jdbcTemplate,
                mock(ExecutionHoldingJdbcSupport.class),
                mock(StockHoldingReservationJdbcSupport.class)
        );
        LocalDateTime executedAt = LocalDateTime.of(2027, 1, 18, 9, 0);

        writer.adjustMatchedAccounts(
                101L,
                new BigDecimal("70.00"),
                102L,
                new BigDecimal("-2.00"),
                executedAt
        );
        writer.updateOrdersAfterFill(
                new OrderBookOrderFillUpdate(
                        201L,
                        "FILLED",
                        1L,
                        new BigDecimal("70.00"),
                        BigDecimal.ZERO
                ),
                new OrderBookOrderFillUpdate(
                        202L,
                        "FILLED",
                        1L,
                        new BigDecimal("70.00"),
                        BigDecimal.ZERO
                ),
                executedAt
        );

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).update(sqlCaptor.capture(), any(Object[].class));
        List<String> statements = sqlCaptor.getAllValues();
        org.assertj.core.api.Assertions.assertThat(statements)
                .anySatisfy(sql -> org.assertj.core.api.Assertions.assertThat(sql)
                        .contains(
                                "update stock_account force index (primary)",
                                "selected_account.id = stock_account.id"
                        )
                        .doesNotContain("where id in"))
                .anySatisfy(sql -> org.assertj.core.api.Assertions.assertThat(sql)
                        .contains(
                                "update stock_order force index (primary)",
                                "selected_order.id = stock_order.id"
                        )
                        .doesNotContain("where id in"));
    }
}
