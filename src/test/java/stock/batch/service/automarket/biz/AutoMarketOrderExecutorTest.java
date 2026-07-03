package stock.batch.service.automarket.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import stock.batch.service.batch.automarket.model.AutoOrder;
import stock.batch.service.batch.automarket.writer.AutoMarketWriter;
import stock.batch.service.batch.automarket.reader.AutoMarketOrderReader;
import stock.batch.service.simulation.SimulationClockService;

class AutoMarketOrderExecutorTest {

    @Test
    void placeOrders_sameAccountBuyOrders_reservesCashOnceAndBatchInsertsAcceptedOrders() {
        AutoMarketOrderReader orderReader = mock(AutoMarketOrderReader.class);
        AutoMarketWriter writer = mock(AutoMarketWriter.class);
        SimulationClockService clockService = mock(SimulationClockService.class);
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        AutoMarketOrderExecutor executor = new AutoMarketOrderExecutor(orderReader, writer, clockService);
        List<AutoMarketPlannedOrder> orders = List.of(
                new AutoMarketPlannedOrder(1L, "STOCK001", "BUY", new BigDecimal("1000.00"), 2),
                new AutoMarketPlannedOrder(1L, "STOCK001", "BUY", new BigDecimal("1200.00"), 3)
        );
        when(clockService.currentMarketDateTime()).thenReturn(now);
        when(writer.reserveBuyCash(eq(1L), eq(new BigDecimal("5600.00")), eq(now))).thenReturn(true);
        when(writer.insertLimitOrders(any(), eq(now))).thenReturn(2);

        AutoParticipantOrderGenerationResult result = executor.placeOrders(orders);

        assertThat(result.generatedOrderCount()).isEqualTo(2);
        assertThat(result.reservedBuyCount()).isEqualTo(2);
        assertThat(result.failedReserveCount()).isZero();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AutoMarketWriter.LimitOrderInsert>> insertsCaptor = ArgumentCaptor.forClass(List.class);
        verify(writer).insertLimitOrders(insertsCaptor.capture(), eq(now));
        assertThat(insertsCaptor.getValue())
                .extracting(AutoMarketWriter.LimitOrderInsert::reservedCash)
                .containsExactly(new BigDecimal("2000.00"), new BigDecimal("3600.00"));
    }

    @Test
    void placeOrders_buyReservations_lockAccountsInAscendingAccountIdOrder() {
        AutoMarketOrderReader orderReader = mock(AutoMarketOrderReader.class);
        AutoMarketWriter writer = mock(AutoMarketWriter.class);
        SimulationClockService clockService = mock(SimulationClockService.class);
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        AutoMarketOrderExecutor executor = new AutoMarketOrderExecutor(orderReader, writer, clockService);
        List<AutoMarketPlannedOrder> orders = List.of(
                new AutoMarketPlannedOrder(2L, "STOCK001", "BUY", new BigDecimal("1000.00"), 1),
                new AutoMarketPlannedOrder(1L, "STOCK001", "BUY", new BigDecimal("1000.00"), 1)
        );
        when(clockService.currentMarketDateTime()).thenReturn(now);
        when(writer.reserveBuyCash(eq(1L), eq(new BigDecimal("1000.00")), eq(now))).thenReturn(true);
        when(writer.reserveBuyCash(eq(2L), eq(new BigDecimal("1000.00")), eq(now))).thenReturn(true);
        when(writer.insertLimitOrders(any(), eq(now))).thenReturn(2);

        executor.placeOrders(orders);

        InOrder inOrder = org.mockito.Mockito.inOrder(writer);
        inOrder.verify(writer).reserveBuyCash(1L, new BigDecimal("1000.00"), now);
        inOrder.verify(writer).reserveBuyCash(2L, new BigDecimal("1000.00"), now);
    }

    @Test
    void placeOrders_sameHoldingSellOrders_reservesQuantityOnceAndBatchInsertsAcceptedOrders() {
        AutoMarketOrderReader orderReader = mock(AutoMarketOrderReader.class);
        AutoMarketWriter writer = mock(AutoMarketWriter.class);
        SimulationClockService clockService = mock(SimulationClockService.class);
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        AutoMarketOrderExecutor executor = new AutoMarketOrderExecutor(orderReader, writer, clockService);
        List<AutoMarketPlannedOrder> orders = List.of(
                new AutoMarketPlannedOrder(1L, "STOCK001", "SELL", new BigDecimal("1000.00"), 2),
                new AutoMarketPlannedOrder(1L, "STOCK001", "SELL", new BigDecimal("1200.00"), 3)
        );
        when(clockService.currentMarketDateTime()).thenReturn(now);
        when(writer.reserveSellQuantity(1L, "STOCK001", 5L, now)).thenReturn(true);
        when(writer.insertLimitOrders(any(), eq(now))).thenReturn(2);

        AutoParticipantOrderGenerationResult result = executor.placeOrders(orders);

        assertThat(result.generatedOrderCount()).isEqualTo(2);
        assertThat(result.reservedSellCount()).isEqualTo(2);
        assertThat(result.failedReserveCount()).isZero();
    }

    @Test
    void placeOrders_sellReservations_lockHoldingsInAscendingAccountAndSymbolOrder() {
        AutoMarketOrderReader orderReader = mock(AutoMarketOrderReader.class);
        AutoMarketWriter writer = mock(AutoMarketWriter.class);
        SimulationClockService clockService = mock(SimulationClockService.class);
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        AutoMarketOrderExecutor executor = new AutoMarketOrderExecutor(orderReader, writer, clockService);
        List<AutoMarketPlannedOrder> orders = List.of(
                new AutoMarketPlannedOrder(2L, "STOCK002", "SELL", new BigDecimal("1000.00"), 1),
                new AutoMarketPlannedOrder(1L, "STOCK002", "SELL", new BigDecimal("1000.00"), 1),
                new AutoMarketPlannedOrder(1L, "STOCK001", "SELL", new BigDecimal("1000.00"), 1)
        );
        when(clockService.currentMarketDateTime()).thenReturn(now);
        when(writer.reserveSellQuantity(1L, "STOCK001", 1L, now)).thenReturn(true);
        when(writer.reserveSellQuantity(1L, "STOCK002", 1L, now)).thenReturn(true);
        when(writer.reserveSellQuantity(2L, "STOCK002", 1L, now)).thenReturn(true);
        when(writer.insertLimitOrders(any(), eq(now))).thenReturn(3);

        executor.placeOrders(orders);

        InOrder inOrder = org.mockito.Mockito.inOrder(writer);
        inOrder.verify(writer).reserveSellQuantity(1L, "STOCK001", 1L, now);
        inOrder.verify(writer).reserveSellQuantity(1L, "STOCK002", 1L, now);
        inOrder.verify(writer).reserveSellQuantity(2L, "STOCK002", 1L, now);
    }

    @Test
    void placeOrders_reserveFailureSkipsRejectedOrdersAndInsertsOnlyAcceptedOrders() {
        AutoMarketOrderReader orderReader = mock(AutoMarketOrderReader.class);
        AutoMarketWriter writer = mock(AutoMarketWriter.class);
        SimulationClockService clockService = mock(SimulationClockService.class);
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        AutoMarketOrderExecutor executor = new AutoMarketOrderExecutor(orderReader, writer, clockService);
        List<AutoMarketPlannedOrder> orders = List.of(
                new AutoMarketPlannedOrder(1L, "STOCK001", "BUY", new BigDecimal("1000.00"), 2),
                new AutoMarketPlannedOrder(2L, "STOCK001", "BUY", new BigDecimal("1200.00"), 3)
        );
        when(clockService.currentMarketDateTime()).thenReturn(now);
        when(writer.reserveBuyCash(eq(1L), eq(new BigDecimal("2000.00")), eq(now))).thenReturn(false);
        when(writer.reserveBuyCash(eq(2L), eq(new BigDecimal("3600.00")), eq(now))).thenReturn(true);
        when(writer.insertLimitOrders(any(), eq(now))).thenReturn(1);

        AutoParticipantOrderGenerationResult result = executor.placeOrders(orders);

        assertThat(result.generatedOrderCount()).isEqualTo(1);
        assertThat(result.reservedBuyCount()).isEqualTo(1);
        assertThat(result.failedReserveCount()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AutoMarketWriter.LimitOrderInsert>> insertsCaptor = ArgumentCaptor.forClass(List.class);
        verify(writer).insertLimitOrders(insertsCaptor.capture(), eq(now));
        assertThat(insertsCaptor.getValue())
                .extracting(AutoMarketWriter.LimitOrderInsert::accountId)
                .containsExactly(2L);
    }

    @Test
    void placeOrders_batchInsertMismatch_throwsToRollbackReservationTransaction() {
        AutoMarketOrderReader orderReader = mock(AutoMarketOrderReader.class);
        AutoMarketWriter writer = mock(AutoMarketWriter.class);
        SimulationClockService clockService = mock(SimulationClockService.class);
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        AutoMarketOrderExecutor executor = new AutoMarketOrderExecutor(orderReader, writer, clockService);
        List<AutoMarketPlannedOrder> orders = List.of(
                new AutoMarketPlannedOrder(1L, "STOCK001", "BUY", new BigDecimal("1000.00"), 2)
        );
        when(clockService.currentMarketDateTime()).thenReturn(now);
        when(writer.reserveBuyCash(eq(1L), eq(new BigDecimal("2000.00")), eq(now))).thenReturn(true);
        when(writer.insertLimitOrders(any(), eq(now))).thenReturn(0);

        assertThatThrownBy(() -> executor.placeOrders(orders))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Auto order batch insert count mismatch");
    }

    @Test
    void expireOrders_releasesCashAndHoldingsInAccountOrderAfterCancellingOrders() {
        AutoMarketOrderReader orderReader = mock(AutoMarketOrderReader.class);
        AutoMarketWriter writer = mock(AutoMarketWriter.class);
        SimulationClockService clockService = mock(SimulationClockService.class);
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        AutoMarketOrderExecutor executor = new AutoMarketOrderExecutor(orderReader, writer, clockService);
        AutoOrder buyHighAccount = new AutoOrder(1L, 3L, "STOCK001", "BUY", 10, 0, new BigDecimal("3000.00"));
        AutoOrder sellLowSymbolB = new AutoOrder(2L, 2L, "STOCK002", "SELL", 8, 2, BigDecimal.ZERO);
        AutoOrder buyLowAccount = new AutoOrder(3L, 1L, "STOCK001", "BUY", 10, 0, new BigDecimal("1000.00"));
        AutoOrder sellLowSymbolA = new AutoOrder(4L, 2L, "STOCK001", "SELL", 5, 0, BigDecimal.ZERO);
        when(writer.cancelOpenOrder(buyHighAccount, now)).thenReturn(true);
        when(writer.cancelOpenOrder(sellLowSymbolB, now)).thenReturn(true);
        when(writer.cancelOpenOrder(buyLowAccount, now)).thenReturn(true);
        when(writer.cancelOpenOrder(sellLowSymbolA, now)).thenReturn(true);

        int expiredCount = executor.expireOrders(List.of(buyHighAccount, sellLowSymbolB, buyLowAccount, sellLowSymbolA), now);

        assertThat(expiredCount).isEqualTo(4);
        InOrder inOrder = org.mockito.Mockito.inOrder(writer);
        inOrder.verify(writer).cancelOpenOrder(buyHighAccount, now);
        inOrder.verify(writer).cancelOpenOrder(sellLowSymbolB, now);
        inOrder.verify(writer).cancelOpenOrder(buyLowAccount, now);
        inOrder.verify(writer).cancelOpenOrder(sellLowSymbolA, now);
        inOrder.verify(writer).creditCash(1L, new BigDecimal("1000.00"), now);
        inOrder.verify(writer).creditCash(3L, new BigDecimal("3000.00"), now);
        inOrder.verify(writer).releaseReservedSellQuantity(2L, "STOCK001", 5L, now);
        inOrder.verify(writer).releaseReservedSellQuantity(2L, "STOCK002", 6L, now);
    }
}
