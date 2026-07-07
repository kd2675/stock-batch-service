package stock.batch.service.execution.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.batch.common.support.StockPriceRedisPublisher;
import stock.batch.service.batch.execution.reader.OrderBookExecutionReader;
import stock.batch.service.batch.execution.writer.OrderBookExecutionWriter;
import stock.batch.service.batch.execution.writer.OrderBookPriceWriter;
import stock.batch.service.execution.queue.OrderBookReadySymbolQueue;
import stock.batch.service.simulation.SimulationClockService;

class InternalOrderBookExecutionServiceParallelWorkerTest {

    @Test
    void executeEligibleOrders_withMultipleWorkers_pollsReadySymbolQueueConcurrently() {
        int workerCount = 3;
        BlockingEmptyReadySymbolQueue readySymbolQueue = new BlockingEmptyReadySymbolQueue(workerCount);
        ExecutorService executorService = Executors.newFixedThreadPool(workerCount);
        InternalOrderBookExecutionService service = new InternalOrderBookExecutionService(
                mock(ExecutionCostCalculator.class),
                mock(OrderBookExecutionReader.class),
                mock(OrderBookExecutionWriter.class),
                mock(OrderBookPriceWriter.class),
                mock(StockPriceRedisPublisher.class),
                mock(SimulationClockService.class),
                mock(TransactionTemplate.class),
                unused -> Optional.empty(),
                readySymbolQueue,
                executorService
        );
        ReflectionTestUtils.setField(service, "scanLimit", 30);
        ReflectionTestUtils.setField(service, "executionWorkerCount", workerCount);

        try {
            int matchCount = service.executeEligibleOrders();

            assertThat(matchCount).isZero();
            assertThat(readySymbolQueue.pollCount()).isEqualTo(workerCount);
            assertThat(readySymbolQueue.maxConcurrentPollCount()).isEqualTo(workerCount);
        } finally {
            executorService.shutdownNow();
        }
    }

    private static final class BlockingEmptyReadySymbolQueue implements OrderBookReadySymbolQueue {

        private final CountDownLatch allWorkersEntered;
        private final CountDownLatch releaseWorkers = new CountDownLatch(1);
        private final AtomicInteger pollCount = new AtomicInteger();
        private final AtomicInteger concurrentPollCount = new AtomicInteger();
        private final AtomicInteger maxConcurrentPollCount = new AtomicInteger();

        private BlockingEmptyReadySymbolQueue(int expectedWorkerCount) {
            this.allWorkersEntered = new CountDownLatch(expectedWorkerCount);
        }

        @Override
        public boolean enqueue(String symbol) {
            return true;
        }

        @Override
        public int enqueueAll(Collection<String> symbols) {
            return symbols.size();
        }

        @Override
        public Optional<String> poll() {
            pollCount.incrementAndGet();
            int currentConcurrentPollCount = concurrentPollCount.incrementAndGet();
            maxConcurrentPollCount.accumulateAndGet(currentConcurrentPollCount, Math::max);
            allWorkersEntered.countDown();
            if (allWorkersEntered.getCount() == 0) {
                releaseWorkers.countDown();
            }
            try {
                if (!releaseWorkers.await(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new AssertionError("order book execution workers did not poll concurrently");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for concurrent order book worker polls", ex);
            } finally {
                concurrentPollCount.decrementAndGet();
            }
            return Optional.empty();
        }

        private int pollCount() {
            return pollCount.get();
        }

        private int maxConcurrentPollCount() {
            return maxConcurrentPollCount.get();
        }
    }
}
