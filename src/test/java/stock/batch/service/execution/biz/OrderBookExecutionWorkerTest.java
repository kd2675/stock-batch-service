package stock.batch.service.execution.biz;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import stock.batch.service.batch.common.policy.BatchJobRuntimeControl;
import stock.batch.service.batch.execution.job.OrderBookExecutionJob;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;
import stock.batch.service.simulation.SimulationMarketSessionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderBookExecutionWorkerTest {

    @Test
    void constructor_workerCountAboveSafeMaximum_rejectsStartup() {
        assertThatThrownBy(() -> new OrderBookExecutionWorker(
                mock(InternalOrderBookExecutionService.class),
                mock(BatchJobRuntimeControl.class),
                mock(SimulationMarketSessionService.class),
                mock(MarketSessionFenceService.class),
                new SimpleMeterRegistry(),
                9,
                100,
                5,
                1_000
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("worker.count must be between 1 and 8");
    }

    @Test
    void start_readyQueueConsumerRunsAndStopsWithoutBatchRepository() throws Exception {
        InternalOrderBookExecutionService executionService = mock(InternalOrderBookExecutionService.class);
        BatchJobRuntimeControl runtimeControl = mock(BatchJobRuntimeControl.class);
        SimulationMarketSessionService marketSessionService = mock(SimulationMarketSessionService.class);
        MarketSessionFenceService marketSessionFenceService = mock(MarketSessionFenceService.class);
        CountDownLatch runCalled = new CountDownLatch(1);
        when(marketSessionService.isRegularSession()).thenReturn(true);
        when(marketSessionFenceService.hasOpenOrderBookMarket()).thenReturn(true);
        when(runtimeControl.shouldRunScheduledJob(OrderBookExecutionJob.JOB_NAME, true)).thenReturn(true);
        when(executionService.executeReadyOrders()).thenAnswer(invocation -> {
            runCalled.countDown();
            return 0;
        });
        OrderBookExecutionWorker worker = new OrderBookExecutionWorker(
                executionService,
                runtimeControl,
                marketSessionService,
                marketSessionFenceService,
                new SimpleMeterRegistry(),
                1,
                10,
                0,
                10
        );

        worker.start();
        try {
            assertThat(runCalled.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            worker.stop();
        }

        assertThat(worker.isRunning()).isFalse();
        verify(executionService).executeReadyOrders();
    }

    @Test
    void start_successfulMatchYieldsBeforeNextRun() throws Exception {
        InternalOrderBookExecutionService executionService = mock(InternalOrderBookExecutionService.class);
        BatchJobRuntimeControl runtimeControl = mock(BatchJobRuntimeControl.class);
        SimulationMarketSessionService marketSessionService = mock(SimulationMarketSessionService.class);
        MarketSessionFenceService marketSessionFenceService = mock(MarketSessionFenceService.class);
        CountDownLatch secondRunCalled = new CountDownLatch(1);
        AtomicLong firstRunCompletedNanos = new AtomicLong();
        AtomicLong secondRunStartedNanos = new AtomicLong();
        when(marketSessionService.isRegularSession()).thenReturn(true);
        when(marketSessionFenceService.hasOpenOrderBookMarket()).thenReturn(true);
        when(runtimeControl.shouldRunScheduledJob(OrderBookExecutionJob.JOB_NAME, true)).thenReturn(true);
        when(executionService.executeReadyOrders())
                .thenAnswer(invocation -> {
                    firstRunCompletedNanos.set(System.nanoTime());
                    return 1;
                })
                .thenAnswer(invocation -> {
                    secondRunStartedNanos.set(System.nanoTime());
                    secondRunCalled.countDown();
                    return 0;
                });
        OrderBookExecutionWorker worker = new OrderBookExecutionWorker(
                executionService,
                runtimeControl,
                marketSessionService,
                marketSessionFenceService,
                new SimpleMeterRegistry(),
                1,
                10,
                50,
                10
        );

        worker.start();
        try {
            assertThat(secondRunCalled.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            worker.stop();
        }

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                secondRunStartedNanos.get() - firstRunCompletedNanos.get()
        );
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(40L);
    }

    @Test
    void start_databaseMarketClosed_doesNotPollExecutionQueue() throws Exception {
        InternalOrderBookExecutionService executionService = mock(InternalOrderBookExecutionService.class);
        BatchJobRuntimeControl runtimeControl = mock(BatchJobRuntimeControl.class);
        SimulationMarketSessionService marketSessionService = mock(SimulationMarketSessionService.class);
        MarketSessionFenceService marketSessionFenceService = mock(MarketSessionFenceService.class);
        when(marketSessionService.isRegularSession()).thenReturn(true);
        when(marketSessionFenceService.hasOpenOrderBookMarket()).thenReturn(false);
        OrderBookExecutionWorker worker = new OrderBookExecutionWorker(
                executionService,
                runtimeControl,
                marketSessionService,
                marketSessionFenceService,
                new SimpleMeterRegistry(),
                1,
                10,
                0,
                10
        );

        worker.start();
        try {
            Thread.sleep(75L);
        } finally {
            worker.stop();
        }

        verify(executionService, never()).executeReadyOrders();
        verify(runtimeControl, never()).shouldRunScheduledJob(OrderBookExecutionJob.JOB_NAME, true);
    }
}
