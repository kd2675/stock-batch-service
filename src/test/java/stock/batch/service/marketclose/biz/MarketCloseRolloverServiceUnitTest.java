package stock.batch.service.marketclose.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.batch.marketclose.model.MarketCloseOrderRow;
import stock.batch.service.batch.marketclose.writer.MarketCloseRolloverWriter;
import stock.batch.service.execution.lock.OrderBookSymbolLock;
import stock.batch.service.simulation.SimulationClockService;

class MarketCloseRolloverServiceUnitTest {

    @Test
    void cancelOpenOrderBookOrders_symbolLockBusy_skipsWithoutTouchingOrders() {
        MarketCloseRolloverWriter writer = mock(MarketCloseRolloverWriter.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        OrderBookSymbolLock orderBookSymbolLock = symbol -> Optional.empty();
        MarketCloseRolloverService service = new MarketCloseRolloverService(
                writer,
                simulationClockService,
                orderBookSymbolLock,
                transactionTemplate()
        );
        when(writer.findCloseLockSymbols("MC001")).thenReturn(List.of("MC001"));

        int processedCount = service.cancelOpenOrderBookOrders("MC001");

        assertThat(processedCount).isZero();
        verify(writer, never()).findOpenOrderBookOrdersForUpdate("MC001");
    }

    @Test
    void cancelOpenOrderBookOrders_releasesCashAndHoldingsInAccountOrderAfterCancellingOrders() {
        MarketCloseRolloverWriter writer = mock(MarketCloseRolloverWriter.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        OrderBookSymbolLock orderBookSymbolLock = symbol -> Optional.of(() -> {
        });
        MarketCloseRolloverService service = new MarketCloseRolloverService(
                writer,
                simulationClockService,
                orderBookSymbolLock,
                transactionTemplate()
        );
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 18, 0);
        MarketCloseOrderRow buyHighAccount = new MarketCloseOrderRow(1L, 3L, "MC001", "BUY", 10, new BigDecimal("3000.00"));
        MarketCloseOrderRow sellLowSymbolB = new MarketCloseOrderRow(2L, 2L, "MC002", "SELL", 6, BigDecimal.ZERO);
        MarketCloseOrderRow buyLowAccount = new MarketCloseOrderRow(3L, 1L, "MC001", "BUY", 10, new BigDecimal("1000.00"));
        MarketCloseOrderRow sellLowSymbolA = new MarketCloseOrderRow(4L, 2L, "MC001", "SELL", 5, BigDecimal.ZERO);
        when(writer.findCloseLockSymbols("MC001")).thenReturn(List.of("MC001"));
        when(simulationClockService.currentMarketDateTime()).thenReturn(now);
        when(writer.findOpenOrderBookOrdersForUpdate("MC001"))
                .thenReturn(List.of(buyHighAccount, sellLowSymbolB, buyLowAccount, sellLowSymbolA));
        when(writer.cancelOrder(1L, now)).thenReturn(true);
        when(writer.cancelOrder(2L, now)).thenReturn(true);
        when(writer.cancelOrder(3L, now)).thenReturn(true);
        when(writer.cancelOrder(4L, now)).thenReturn(true);

        int processedCount = service.cancelOpenOrderBookOrders("MC001");

        assertThat(processedCount).isEqualTo(4);
        InOrder inOrder = org.mockito.Mockito.inOrder(writer);
        inOrder.verify(writer).cancelOrder(1L, now);
        inOrder.verify(writer).cancelOrder(2L, now);
        inOrder.verify(writer).cancelOrder(3L, now);
        inOrder.verify(writer).cancelOrder(4L, now);
        inOrder.verify(writer).creditCash(1L, new BigDecimal("1000.00"), now);
        inOrder.verify(writer).creditCash(3L, new BigDecimal("3000.00"), now);
        inOrder.verify(writer).releaseReservedSellQuantity(2L, "MC001", 5L, now);
        inOrder.verify(writer).releaseReservedSellQuantity(2L, "MC002", 6L, now);
    }

    @Test
    void cancelOpenOrderBookOrders_deadlockRetriesInNewTransaction() {
        MarketCloseRolloverWriter writer = mock(MarketCloseRolloverWriter.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        OrderBookSymbolLock orderBookSymbolLock = symbol -> Optional.of(() -> {
        });
        MarketCloseRolloverService service = new MarketCloseRolloverService(
                writer,
                simulationClockService,
                orderBookSymbolLock,
                transactionTemplate()
        );
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 18, 0);
        MarketCloseOrderRow order = new MarketCloseOrderRow(1L, 3L, "MC001", "BUY", 10, new BigDecimal("3000.00"));
        when(writer.findCloseLockSymbols("MC001")).thenReturn(List.of("MC001"));
        when(simulationClockService.currentMarketDateTime()).thenReturn(now);
        when(writer.findOpenOrderBookOrdersForUpdate("MC001"))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenReturn(List.of(order));
        when(writer.cancelOrder(1L, now)).thenReturn(true);

        int processedCount = service.cancelOpenOrderBookOrders("MC001");

        assertThat(processedCount).isEqualTo(1);
        verify(writer, org.mockito.Mockito.times(2)).findOpenOrderBookOrdersForUpdate("MC001");
        verify(writer).creditCash(3L, new BigDecimal("3000.00"), now);
    }

    @Test
    void rolloverClosingPrices_snapshotsDailySymbolBeforePriceRollover() {
        MarketCloseRolloverWriter writer = mock(MarketCloseRolloverWriter.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        OrderBookSymbolLock orderBookSymbolLock = symbol -> Optional.of(() -> {
        });
        MarketCloseRolloverService service = new MarketCloseRolloverService(
                writer,
                simulationClockService,
                orderBookSymbolLock,
                transactionTemplate()
        );
        LocalDate tradeDate = LocalDate.of(2026, 7, 3);
        LocalDateTime closedAt = LocalDateTime.of(2026, 7, 3, 18, 0);
        LocalDateTime completedAt = LocalDateTime.of(2026, 7, 3, 18, 0, 5);
        when(writer.findCloseLockSymbols("MC001")).thenReturn(List.of("MC001"));
        when(simulationClockService.currentDate()).thenReturn(tradeDate);
        when(simulationClockService.currentMarketDateTime()).thenReturn(closedAt, completedAt);
        when(writer.createCloseRun("MC001", tradeDate, closedAt)).thenReturn(10L);
        when(writer.findOpenOrderBookOrdersForUpdate("MC001")).thenReturn(List.of());
        when(writer.snapshotHoldings(10L, "MC001", closedAt)).thenReturn(2);
        when(writer.snapshotOrderBookDailySymbols(
                10L,
                "MC001",
                tradeDate,
                closedAt,
                tradeDate.atStartOfDay(),
                tradeDate.plusDays(1).atStartOfDay()
        )).thenReturn(1);
        when(writer.rolloverClosingPrices("MC001")).thenReturn(1);

        int processedCount = service.rolloverClosingPrices("MC001");

        assertThat(processedCount).isEqualTo(4);
        InOrder inOrder = org.mockito.Mockito.inOrder(writer);
        inOrder.verify(writer).createCloseRun("MC001", tradeDate, closedAt);
        inOrder.verify(writer).snapshotHoldings(10L, "MC001", closedAt);
        inOrder.verify(writer).snapshotOrderBookDailySymbols(
                10L,
                "MC001",
                tradeDate,
                closedAt,
                tradeDate.atStartOfDay(),
                tradeDate.plusDays(1).atStartOfDay()
        );
        inOrder.verify(writer).rolloverClosingPrices("MC001");
        inOrder.verify(writer).completeCloseRun(10L, 0, 2, 1, completedAt);
    }

    private TransactionTemplate transactionTemplate() {
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            TransactionCallback<Integer> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        return transactionTemplate;
    }
}
