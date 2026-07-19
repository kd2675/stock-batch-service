package stock.batch.service.marketclose.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.batch.marketclose.model.MarketCloseOrderRow;
import stock.batch.service.batch.marketclose.writer.MarketCloseRolloverWriter;
import stock.batch.service.execution.lock.OrderBookSymbolLock;
import stock.batch.service.marketclose.model.PostCloseCycle;
import stock.batch.service.marketclose.model.PostCloseCycleStatus;
import stock.batch.service.marketclose.model.PostClosePhase;
import stock.batch.service.marketclose.model.PostClosePhaseClaim;
import stock.batch.service.marketclose.model.PostCloseScopeType;
import stock.batch.service.simulation.SimulationClockService;

class MarketCloseRolloverServiceUnitTest {

    @Test
    void validateVolumeConfiguration_orderCancelChunkAboveSafeMaximum_rejectsStartup() {
        MarketCloseRolloverService service = newService(
                mock(MarketCloseRolloverWriter.class),
                mock(SimulationClockService.class),
                symbol -> Optional.empty()
        );
        ReflectionTestUtils.setField(service, "orderCancelChunkSize", 5_001);

        assertThatThrownBy(service::validateVolumeConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("order-cancel-chunk-size must be between 1 and 5000");
    }

    @Test
    void validateVolumeConfiguration_accountChunkZero_rejectsStartup() {
        MarketCloseRolloverService service = newService(
                mock(MarketCloseRolloverWriter.class),
                mock(SimulationClockService.class),
                symbol -> Optional.empty()
        );
        ReflectionTestUtils.setField(service, "accountSnapshotChunkSize", 0);

        assertThatThrownBy(service::validateVolumeConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("account-snapshot-chunk-size must be between 1 and 2000");
    }

    @Test
    void cancelOpenOrderBookOrders_openMarket_rejectsBeforeTakingSymbolLock() {
        MarketCloseRolloverWriter writer = mock(MarketCloseRolloverWriter.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        OrderBookSymbolLock orderBookSymbolLock = symbol -> Optional.of(() -> {
        });
        MarketCloseRolloverService service = newService(writer, simulationClockService, orderBookSymbolLock);
        when(writer.isOrderBookMarketOpen("MC001")).thenReturn(true);

        assertThatThrownBy(() -> service.cancelOpenOrderBookOrders("MC001"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("halted or closed");
        verify(writer, never()).findCloseLockSymbols("MC001");
    }

    @Test
    void rolloverClosingPrices_openSymbol_rejectsBeforeCreatingCycleOrTakingSymbolLock() {
        MarketCloseRolloverWriter writer = mock(MarketCloseRolloverWriter.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        OrderBookSymbolLock orderBookSymbolLock = symbol -> Optional.of(() -> {
        });
        MarketCloseRolloverService service = newService(writer, simulationClockService, orderBookSymbolLock);
        LocalDate tradeDate = LocalDate.of(2026, 7, 3);
        LocalDateTime closedAt = LocalDateTime.of(2026, 7, 3, 18, 0);
        when(writer.isOrderBookMarketOpen("MC001")).thenReturn(true);

        assertThatThrownBy(() -> service.rolloverClosingPrices("MC001", tradeDate, closedAt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("halted or closed");
        verify(writer, never()).findCloseLockSymbols("MC001");
    }

    @Test
    void cancelOpenOrderBookOrders_symbolLockBusy_defersWithoutTouchingOrders() {
        MarketCloseRolloverWriter writer = mock(MarketCloseRolloverWriter.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        OrderBookSymbolLock orderBookSymbolLock = symbol -> Optional.empty();
        MarketCloseRolloverService service = newService(writer, simulationClockService, orderBookSymbolLock);
        when(writer.findCloseLockSymbols("MC001")).thenReturn(List.of("MC001"));

        assertThatThrownBy(() -> service.cancelOpenOrderBookOrders("MC001"))
                .isInstanceOf(CannotAcquireLockException.class)
                .hasMessageContaining("symbol lock is busy");
        verify(writer, never()).findOpenOrderBookOrderCandidates("MC001", 500);
    }

    @Test
    void cancelOpenOrderBookOrders_locksAccountsAndHoldingsBeforeExactOrderPrimaryKeys() {
        MarketCloseRolloverWriter writer = mock(MarketCloseRolloverWriter.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        OrderBookSymbolLock orderBookSymbolLock = symbol -> Optional.of(() -> {
        });
        MarketCloseRolloverService service = newService(writer, simulationClockService, orderBookSymbolLock);
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 18, 0);
        MarketCloseOrderRow buyHighAccount = new MarketCloseOrderRow(1L, 3L, "MC001", "BUY", 10, new BigDecimal("3000.00"));
        MarketCloseOrderRow sellHighQuantity = new MarketCloseOrderRow(2L, 2L, "MC001", "SELL", 6, BigDecimal.ZERO);
        MarketCloseOrderRow buyLowAccount = new MarketCloseOrderRow(3L, 1L, "MC001", "BUY", 10, new BigDecimal("1000.00"));
        MarketCloseOrderRow sellLowQuantity = new MarketCloseOrderRow(4L, 2L, "MC001", "SELL", 5, BigDecimal.ZERO);
        when(writer.findCloseLockSymbols("MC001")).thenReturn(List.of("MC001"));
        when(simulationClockService.currentMarketDateTime()).thenReturn(now);
        List<MarketCloseOrderRow> candidates = List.of(
                buyHighAccount,
                sellHighQuantity,
                buyLowAccount,
                sellLowQuantity
        );
        when(writer.findOpenOrderBookOrderCandidates("MC001", 500))
                .thenReturn(candidates)
                .thenReturn(List.of());
        when(writer.lockOpenOrderBookOrdersForUpdate(candidates)).thenReturn(candidates);
        when(writer.cancelOrders(List.of(1L, 2L, 3L, 4L), now)).thenReturn(4);
        when(writer.creditCashChunk(Map.of(
                1L, new BigDecimal("1000.00"),
                3L, new BigDecimal("3000.00")
        ), now)).thenReturn(2);
        when(writer.releaseReservedSellQuantityChunk("MC001", Map.of(2L, 11L), now)).thenReturn(1);

        int processedCount = service.cancelOpenOrderBookOrders("MC001");

        assertThat(processedCount).isEqualTo(4);
        InOrder inOrder = org.mockito.Mockito.inOrder(writer);
        inOrder.verify(writer).lockAccountsForUpdate(candidates);
        inOrder.verify(writer).lockSellHoldingsForUpdate("MC001", candidates);
        inOrder.verify(writer).lockOpenOrderBookOrdersForUpdate(candidates);
        inOrder.verify(writer).cancelOrders(List.of(1L, 2L, 3L, 4L), now);
        inOrder.verify(writer).creditCashChunk(Map.of(
                1L, new BigDecimal("1000.00"),
                3L, new BigDecimal("3000.00")
        ), now);
        inOrder.verify(writer).releaseReservedSellQuantityChunk("MC001", Map.of(2L, 11L), now);
    }

    @Test
    void cancelOpenOrderBookOrders_deadlockRetriesInNewTransaction() {
        MarketCloseRolloverWriter writer = mock(MarketCloseRolloverWriter.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        OrderBookSymbolLock orderBookSymbolLock = symbol -> Optional.of(() -> {
        });
        MarketCloseRolloverService service = newService(writer, simulationClockService, orderBookSymbolLock);
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 18, 0);
        MarketCloseOrderRow order = new MarketCloseOrderRow(1L, 3L, "MC001", "BUY", 10, new BigDecimal("3000.00"));
        when(writer.findCloseLockSymbols("MC001")).thenReturn(List.of("MC001"));
        when(simulationClockService.currentMarketDateTime()).thenReturn(now);
        when(writer.findOpenOrderBookOrderCandidates("MC001", 500))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenReturn(List.of(order))
                .thenReturn(List.of());
        when(writer.lockOpenOrderBookOrdersForUpdate(List.of(order))).thenReturn(List.of(order));
        when(writer.cancelOrders(List.of(1L), now)).thenReturn(1);
        when(writer.creditCashChunk(Map.of(3L, new BigDecimal("3000.00")), now)).thenReturn(1);

        int processedCount = service.cancelOpenOrderBookOrders("MC001");

        assertThat(processedCount).isEqualTo(1);
        verify(writer, org.mockito.Mockito.times(3)).findOpenOrderBookOrderCandidates("MC001", 500);
        verify(writer).creditCashChunk(Map.of(3L, new BigDecimal("3000.00")), now);
    }

    @Test
    void rolloverClosingPrices_symbolLockBusy_failsInsteadOfReturningZero() {
        MarketCloseRolloverWriter writer = mock(MarketCloseRolloverWriter.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        OrderBookSymbolLock orderBookSymbolLock = symbol -> Optional.empty();
        MarketCloseRolloverService service = newService(writer, simulationClockService, orderBookSymbolLock);
        LocalDate tradeDate = LocalDate.of(2026, 7, 3);
        LocalDateTime closedAt = LocalDateTime.of(2026, 7, 3, 18, 30);
        when(writer.findCloseLockSymbols(null)).thenReturn(List.of("MC001"));
        when(simulationClockService.currentDate()).thenReturn(tradeDate);
        when(simulationClockService.currentMarketDateTime()).thenReturn(closedAt);

        assertThatThrownBy(service::rolloverClosingPrices)
                .isInstanceOf(CannotAcquireLockException.class)
                .hasMessageContaining("symbol lock is busy");
        verify(writer, never()).createCloseRun(null, tradeDate, closedAt);
    }

    @Test
    void rolloverClosingPrices_missingCapturedBuyRelease_rollsBackChunk() {
        MarketCloseRolloverWriter writer = mock(MarketCloseRolloverWriter.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        OrderBookSymbolLock orderBookSymbolLock = symbol -> Optional.of(() -> {
        });
        MarketCloseRolloverService service = newService(writer, simulationClockService, orderBookSymbolLock);
        LocalDate tradeDate = LocalDate.of(2026, 7, 3);
        LocalDateTime closedAt = LocalDateTime.of(2026, 7, 3, 18, 0);
        MarketCloseOrderRow buyOrder = new MarketCloseOrderRow(
                101L,
                7L,
                "MC001",
                "BUY",
                2L,
                new BigDecimal("2000.00")
        );
        when(writer.findCloseLockSymbols("MC001")).thenReturn(List.of("MC001"));
        when(writer.createCloseRun("MC001", tradeDate, closedAt)).thenReturn(10L);
        when(writer.findUnreleasedCapturedOrderCandidates(1L, 500))
                .thenReturn(List.of(buyOrder));
        when(writer.lockOpenOrderBookOrdersForUpdate(List.of(buyOrder))).thenReturn(List.of(buyOrder));
        when(writer.cancelCapturedOrders(1L, List.of(101L), closedAt)).thenReturn(1);
        when(writer.creditCashChunk(Map.of(7L, new BigDecimal("2000.00")), closedAt)).thenReturn(0);

        assertThatThrownBy(() -> service.rolloverClosingPrices("MC001", tradeDate, closedAt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("buy-reservation release count mismatch");
    }

    @Test
    void rolloverClosingPrices_defersDailyReportAggregationOutOfFreezeTransaction() {
        MarketCloseRolloverWriter writer = mock(MarketCloseRolloverWriter.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        OrderBookSymbolLock orderBookSymbolLock = symbol -> Optional.of(() -> {
        });
        MarketCloseRolloverService service = newService(writer, simulationClockService, orderBookSymbolLock);
        LocalDate tradeDate = LocalDate.of(2026, 7, 3);
        LocalDateTime closedAt = LocalDateTime.of(2026, 7, 3, 18, 0);
        LocalDateTime completedAt = LocalDateTime.of(2026, 7, 3, 18, 0, 5);
        when(writer.findCloseLockSymbols("MC001")).thenReturn(List.of("MC001"));
        when(simulationClockService.currentDate()).thenReturn(tradeDate);
        when(simulationClockService.currentMarketDateTime()).thenReturn(closedAt, completedAt);
        when(writer.createCloseRun("MC001", tradeDate, closedAt)).thenReturn(10L);
        when(writer.countHoldingSnapshots(1L)).thenReturn(2L);
        when(writer.rolloverClosingPrices("MC001")).thenReturn(1);

        int processedCount = service.rolloverClosingPrices("MC001");

        assertThat(processedCount).isEqualTo(3);
        InOrder inOrder = org.mockito.Mockito.inOrder(writer);
        inOrder.verify(writer).createCloseRun("MC001", tradeDate, closedAt);
        inOrder.verify(writer).captureOpenOrdersChunk(
                1L, 10L, "MC001", "PENDING", closedAt, 0L, 1000
        );
        inOrder.verify(writer).captureOpenOrdersChunk(
                1L, 10L, "MC001", "PARTIALLY_FILLED", closedAt, 0L, 1000
        );
        inOrder.verify(writer).snapshotOpenOrderSummary(1L, 10L, "MC001", closedAt);
        inOrder.verify(writer).completeOpenOrderSummaryReconciliation(1L);
        inOrder.verify(writer).rolloverClosingPrices("MC001");
        inOrder.verify(writer).completeCloseRun(10L, 0, 2, 1, completedAt);
        verify(writer, never()).snapshotOrderBookDailySymbols(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    private TransactionTemplate transactionTemplate() {
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        return transactionTemplate;
    }

    private MarketCloseRolloverService newService(
            MarketCloseRolloverWriter writer,
            SimulationClockService simulationClockService,
            OrderBookSymbolLock orderBookSymbolLock
    ) {
        when(writer.sumReleasedReservations(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new MarketCloseRolloverWriter.ReleasedReservationTotals(BigDecimal.ZERO, 0L));
        when(simulationClockService.currentDate()).thenReturn(LocalDate.of(2026, 7, 3));
        when(simulationClockService.currentMarketDateTime()).thenReturn(LocalDateTime.of(2026, 7, 3, 18, 0));
        PostCloseCycleService postCloseCycleService = mock(PostCloseCycleService.class);
        PostCloseCycle cycle = new PostCloseCycle(
                1L,
                LocalDate.of(2026, 7, 3),
                PostCloseScopeType.FULL_MARKET,
                "ALL",
                stock.batch.service.marketclose.model.PostCloseCycleKind.TRADING,
                null,
                PostClosePhase.CLOSE_REQUESTED,
                PostCloseCycleStatus.PENDING,
                1,
                0,
                null,
                null,
                0,
                null,
                null,
                null
        );
        when(postCloseCycleService.ensureFullMarketCycle(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(cycle);
        when(postCloseCycleService.ensureSymbolCycle(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(cycle);
        when(postCloseCycleService.tryClaim(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(Optional.of(new PostClosePhaseClaim(
                1L,
                PostClosePhase.CLOSE_REQUESTED,
                1,
                "test-owner",
                LocalDateTime.of(2026, 7, 3, 18, 3)
        )));
        when(postCloseCycleService.findById(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(Optional.of(cycle));
        return new MarketCloseRolloverService(
                writer,
                simulationClockService,
                orderBookSymbolLock,
                transactionTemplate(),
                mock(MarketSessionFenceService.class),
                postCloseCycleService
        );
    }
}
