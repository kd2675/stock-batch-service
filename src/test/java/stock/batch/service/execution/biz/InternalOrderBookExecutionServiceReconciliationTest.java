package stock.batch.service.execution.biz;

import java.util.List;
import java.util.Optional;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.automarket.biz.AutoParticipantFundingBudgetService;
import stock.batch.service.batch.common.support.StockPriceRedisPublisher;
import stock.batch.service.batch.execution.reader.OrderBookExecutionReader;
import stock.batch.service.batch.execution.writer.OrderBookExecutionWriter;
import stock.batch.service.batch.execution.writer.OrderBookPriceWriter;
import stock.batch.service.execution.lock.OrderBookSymbolLock;
import stock.batch.service.execution.queue.OrderBookReadySymbolQueue;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalOrderBookExecutionServiceReconciliationTest {

    private OrderBookExecutionReader reader;
    private OrderBookReadySymbolQueue queue;
    private OrderBookSymbolLock symbolLock;
    private InternalOrderBookExecutionService service;

    @BeforeEach
    void setUp() {
        reader = mock(OrderBookExecutionReader.class);
        queue = mock(OrderBookReadySymbolQueue.class);
        symbolLock = mock(OrderBookSymbolLock.class);
        service = new InternalOrderBookExecutionService(
                mock(ExecutionCostCalculator.class),
                reader,
                mock(OrderBookExecutionWriter.class),
                mock(OrderBookPriceWriter.class),
                mock(StockPriceRedisPublisher.class),
                mock(MarketSessionFenceService.class),
                mock(TransactionTemplate.class),
                symbolLock,
                queue,
                mock(ExecutionAccountDaySummaryAccumulator.class),
                mock(AutoParticipantFundingBudgetService.class),
                new SimpleMeterRegistry()
        );
        ReflectionTestUtils.setField(service, "readySymbolReconciliationEnabled", true);
        ReflectionTestUtils.setField(service, "readySymbolReconciliationIntervalMillis", 1_000L);
        ReflectionTestUtils.setField(service, "readySymbolFallbackScanLimit", 8);
        ReflectionTestUtils.setField(service, "scanLimit", 1);
    }

    @Test
    void reconcileReadySymbolsIfDue_acquiredLease_enqueuesBoundedDatabaseCandidatesOncePerWindow() {
        OrderBookReadySymbolQueue.ReconciliationLease lease =
                mock(OrderBookReadySymbolQueue.ReconciliationLease.class);
        when(queue.tryAcquireReconciliationLease(1_000L)).thenReturn(Optional.of(lease));
        when(lease.cursor()).thenReturn("");
        when(lease.updateCursor("")).thenReturn(true);
        when(reader.findOpenOrderBookSymbolsAfter("", 8)).thenReturn(List.of("DEMO001", "DEMO002"));
        when(queue.reconcileAll(List.of("DEMO001", "DEMO002"))).thenReturn(2);

        int firstEnqueuedCount = service.reconcileReadySymbolsIfDue();
        int secondEnqueuedCount = service.reconcileReadySymbolsIfDue();

        assertThat(List.of(firstEnqueuedCount, secondEnqueuedCount)).containsExactly(2, 0);
        verify(reader, times(1)).findOpenOrderBookSymbolsAfter("", 8);
        verify(queue).reconcileAll(List.of("DEMO001", "DEMO002"));
        verify(lease).updateCursor("");
        verify(lease).close();
    }

    @Test
    void reconcileReadySymbolsIfDue_leaseHeldByAnotherWorker_skipsDatabaseScan() {
        when(queue.tryAcquireReconciliationLease(1_000L)).thenReturn(Optional.empty());

        int enqueuedCount = service.reconcileReadySymbolsIfDue();

        assertThat(enqueuedCount).isZero();
        verify(reader, never()).findOpenOrderBookSymbolsAfter("", 8);
    }

    @Test
    void reconcileReadySymbolsIfDue_disabled_skipsLeaseAndDatabaseScan() {
        ReflectionTestUtils.setField(service, "readySymbolReconciliationEnabled", false);

        int enqueuedCount = service.reconcileReadySymbolsIfDue();

        assertThat(enqueuedCount).isZero();
        verify(queue, never()).tryAcquireReconciliationLease(1_000L);
        verify(reader, never()).findOpenOrderBookSymbolsAfter("", 8);
    }

    @Test
    void reconcileReadySymbolsIfDue_redisRepairFails_doesNotAdvanceSharedCursor() {
        OrderBookReadySymbolQueue.ReconciliationLease lease =
                mock(OrderBookReadySymbolQueue.ReconciliationLease.class);
        when(queue.tryAcquireReconciliationLease(1_000L)).thenReturn(Optional.of(lease));
        when(lease.cursor()).thenReturn("DEMO008");
        when(reader.findOpenOrderBookSymbolsAfter("DEMO008", 8)).thenReturn(List.of("DEMO009"));
        when(queue.reconcileAll(List.of("DEMO009"))).thenThrow(new IllegalStateException("redis unavailable"));

        int enqueuedCount = service.reconcileReadySymbolsIfDue();

        assertThat(enqueuedCount).isZero();
        verify(lease, never()).updateCursor("DEMO009");
        verify(lease).close();
    }

    @Test
    void reconcileReadySymbolsIfDue_leaseExpiresBeforeCursorAdvance_keepsRepairedQueueEntry() {
        OrderBookReadySymbolQueue.ReconciliationLease lease =
                mock(OrderBookReadySymbolQueue.ReconciliationLease.class);
        when(queue.tryAcquireReconciliationLease(1_000L)).thenReturn(Optional.of(lease));
        when(lease.cursor()).thenReturn("DEMO008");
        when(reader.findOpenOrderBookSymbolsAfter("DEMO008", 8)).thenReturn(List.of("DEMO009"));
        when(queue.reconcileAll(List.of("DEMO009"))).thenReturn(1);
        when(lease.updateCursor("")).thenReturn(false);

        int enqueuedCount = service.reconcileReadySymbolsIfDue();

        assertThat(enqueuedCount).isEqualTo(1);
        verify(lease).updateCursor("");
        verify(lease).close();
    }

    @Test
    void executeReadyOrders_requeuedAttemptedSymbol_preservesQueueEntryForNextRun() {
        when(queue.poll())
                .thenReturn(Optional.of("DEMO001"))
                .thenReturn(Optional.of("DEMO001"));
        when(symbolLock.tryLock("DEMO001")).thenReturn(Optional.empty());
        when(queue.enqueue("DEMO001")).thenReturn(true);

        int matchCount = service.executeReadyOrders();

        assertThat(matchCount).isZero();
        verify(queue, times(2)).enqueue("DEMO001");
    }

    @Test
    void executeReadyOrders_manyEmptySymbols_stopsAtBoundedAttemptLimit() {
        ReflectionTestUtils.setField(service, "readySymbolFallbackScanLimit", 2);
        when(queue.poll())
                .thenReturn(Optional.of("DEMO001"))
                .thenReturn(Optional.of("DEMO002"))
                .thenReturn(Optional.of("DEMO003"));
        when(symbolLock.tryLock("DEMO001")).thenReturn(Optional.empty());
        when(symbolLock.tryLock("DEMO002")).thenReturn(Optional.empty());

        int matchCount = service.executeReadyOrders();

        assertThat(matchCount).isZero();
        verify(queue, times(2)).poll();
        verify(symbolLock, never()).tryLock("DEMO003");
    }
}
