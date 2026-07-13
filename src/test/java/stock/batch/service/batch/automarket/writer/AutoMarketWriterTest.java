package stock.batch.service.batch.automarket.writer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.CannotAcquireLockException;

import stock.batch.service.batch.common.support.StockHoldingReservationJdbcSupport;
import stock.batch.service.execution.queue.NoopOrderBookReadySymbolQueue;

class AutoMarketWriterTest {

    @Test
    void insertLimitOrders_successNoInfoCountsAsInserted() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        StockHoldingReservationJdbcSupport holdingReservationJdbcSupport = mock(StockHoldingReservationJdbcSupport.class);
        AutoMarketWriter writer = new AutoMarketWriter(
                jdbcTemplate,
                holdingReservationJdbcSupport,
                new NoopOrderBookReadySymbolQueue(),
                new SimpleMeterRegistry()
        );
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        List<AutoMarketWriter.LimitOrderInsert> orders = List.of(
                new AutoMarketWriter.LimitOrderInsert(
                        "order-1",
                        1L,
                        "STOCK001",
                        "BUY",
                        new BigDecimal("1000.00"),
                        2,
                        new BigDecimal("2000.00")
                )
        );
        when(jdbcTemplate.batchUpdate(any(String.class), any(BatchPreparedStatementSetter.class)))
                .thenReturn(new int[] {Statement.SUCCESS_NO_INFO});

        int insertedCount = writer.insertLimitOrders(orders, now);

        assertThat(insertedCount).isEqualTo(1);
    }

    @Test
    void insertLimitOrders_emptyOrdersDoesNotCallBatchUpdate() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        StockHoldingReservationJdbcSupport holdingReservationJdbcSupport = mock(StockHoldingReservationJdbcSupport.class);
        AutoMarketWriter writer = new AutoMarketWriter(
                jdbcTemplate,
                holdingReservationJdbcSupport,
                new NoopOrderBookReadySymbolQueue(),
                new SimpleMeterRegistry()
        );

        int insertedCount = writer.insertLimitOrders(List.of(), LocalDateTime.of(2026, 7, 3, 9, 0));

        assertThat(insertedCount).isZero();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void insertLimitOrders_databaseFailureIncrementsFailureMetric() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AutoMarketWriter writer = new AutoMarketWriter(
                jdbcTemplate,
                mock(StockHoldingReservationJdbcSupport.class),
                new NoopOrderBookReadySymbolQueue(),
                meterRegistry
        );
        List<AutoMarketWriter.LimitOrderInsert> orders = List.of(new AutoMarketWriter.LimitOrderInsert(
                "order-1",
                1L,
                "STOCK001",
                "BUY",
                new BigDecimal("1000.00"),
                1L,
                new BigDecimal("1000.00")
        ));
        when(jdbcTemplate.batchUpdate(any(String.class), any(BatchPreparedStatementSetter.class)))
                .thenThrow(new CannotAcquireLockException("deadlock"));

        assertThatThrownBy(() -> writer.insertLimitOrders(orders, LocalDateTime.of(2026, 7, 3, 9, 0)))
                .isInstanceOf(CannotAcquireLockException.class);
        assertThat(meterRegistry.counter("stock.auto.market.order.insert.failures").count()).isEqualTo(1.0);
    }

    @Test
    void reserveSellQuantity_doesNotLockStockAccountAgain() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        StockHoldingReservationJdbcSupport holdingReservationJdbcSupport = mock(StockHoldingReservationJdbcSupport.class);
        AutoMarketWriter writer = new AutoMarketWriter(
                jdbcTemplate,
                holdingReservationJdbcSupport,
                new NoopOrderBookReadySymbolQueue(),
                new SimpleMeterRegistry()
        );
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        when(jdbcTemplate.update(any(String.class), eq(10L), eq(now), eq(1L), eq("DEMO001"), eq(10L)))
                .thenReturn(1);

        boolean reserved = writer.reserveSellQuantity(1L, "DEMO001", 10L, now);

        assertThat(reserved).isTrue();
        verify(jdbcTemplate).update(sqlCaptor.capture(), eq(10L), eq(now), eq(1L), eq("DEMO001"), eq(10L));
        assertThat(sqlCaptor.getValue())
                .doesNotContain("stock_account")
                .doesNotContain("exists");
    }
}
