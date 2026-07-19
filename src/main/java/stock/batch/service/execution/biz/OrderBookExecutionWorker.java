package stock.batch.service.execution.biz;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.common.policy.BatchJobRuntimeControl;
import stock.batch.service.batch.execution.job.OrderBookExecutionJob;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;
import stock.batch.service.simulation.SimulationMarketSessionService;

@Component
@Slf4j
@ConditionalOnProperty(
        prefix = "stock.batch.order-book-execution",
        name = {"enabled", "worker.enabled"},
        havingValue = "true",
        matchIfMissing = true
)
public class OrderBookExecutionWorker implements SmartLifecycle {

    private static final int LIFECYCLE_PHASE = Integer.MAX_VALUE - 50;
    private static final int MAX_WORKER_COUNT = 8;
    private static final long MAX_IDLE_DELAY_MILLIS = 10_000L;
    private static final long MAX_MATCH_YIELD_MILLIS = 1_000L;
    private static final long MIN_GATE_REFRESH_MILLIS = 10L;
    private static final long MAX_GATE_REFRESH_MILLIS = 10_000L;

    private final InternalOrderBookExecutionService executionService;
    private final BatchJobRuntimeControl batchJobRuntimeControl;
    private final SimulationMarketSessionService simulationMarketSessionService;
    private final MarketSessionFenceService marketSessionFenceService;
    private final int workerCount;
    private final long idleDelayMillis;
    private final long matchYieldMillis;
    private final long gateRefreshMillis;
    private final Counter runCounter;
    private final Counter matchCounter;
    private final Counter failureCounter;
    private final Timer runTimer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object gateMonitor = new Object();

    private volatile boolean executionEnabled;
    private volatile long nextGateRefreshNanos;
    private volatile ExecutorService executorService;

    public OrderBookExecutionWorker(
            InternalOrderBookExecutionService executionService,
            BatchJobRuntimeControl batchJobRuntimeControl,
            SimulationMarketSessionService simulationMarketSessionService,
            MarketSessionFenceService marketSessionFenceService,
            MeterRegistry meterRegistry,
            @Value("${stock.batch.order-book-execution.worker.count:2}") int workerCount,
            @Value("${stock.batch.order-book-execution.worker.idle-delay-ms:100}") long idleDelayMillis,
            @Value("${stock.batch.order-book-execution.worker.match-yield-ms:5}") long matchYieldMillis,
            @Value("${stock.batch.order-book-execution.worker.gate-refresh-ms:1000}") long gateRefreshMillis
    ) {
        if (workerCount <= 0 || workerCount > MAX_WORKER_COUNT) {
            throw new IllegalArgumentException(
                    "stock.batch.order-book-execution.worker.count must be between 1 and %d"
                            .formatted(MAX_WORKER_COUNT)
            );
        }
        if (idleDelayMillis <= 0 || idleDelayMillis > MAX_IDLE_DELAY_MILLIS) {
            throw new IllegalArgumentException(
                    "stock.batch.order-book-execution.worker.idle-delay-ms must be between 1 and %d"
                            .formatted(MAX_IDLE_DELAY_MILLIS)
            );
        }
        if (matchYieldMillis < 0 || matchYieldMillis > MAX_MATCH_YIELD_MILLIS) {
            throw new IllegalArgumentException(
                    "stock.batch.order-book-execution.worker.match-yield-ms must be between 0 and %d"
                            .formatted(MAX_MATCH_YIELD_MILLIS)
            );
        }
        if (gateRefreshMillis < MIN_GATE_REFRESH_MILLIS || gateRefreshMillis > MAX_GATE_REFRESH_MILLIS) {
            throw new IllegalArgumentException(
                    "stock.batch.order-book-execution.worker.gate-refresh-ms must be between %d and %d"
                            .formatted(MIN_GATE_REFRESH_MILLIS, MAX_GATE_REFRESH_MILLIS)
            );
        }
        this.executionService = executionService;
        this.batchJobRuntimeControl = batchJobRuntimeControl;
        this.simulationMarketSessionService = simulationMarketSessionService;
        this.marketSessionFenceService = marketSessionFenceService;
        this.workerCount = workerCount;
        this.idleDelayMillis = idleDelayMillis;
        this.matchYieldMillis = matchYieldMillis;
        this.gateRefreshMillis = gateRefreshMillis;
        this.runCounter = meterRegistry.counter("stock.orderbook.execution.worker.runs");
        this.matchCounter = meterRegistry.counter("stock.orderbook.execution.worker.matches");
        this.failureCounter = meterRegistry.counter("stock.orderbook.execution.worker.failures");
        this.runTimer = meterRegistry.timer("stock.orderbook.execution.worker.duration");
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        executorService = Executors.newFixedThreadPool(
                workerCount,
                Thread.ofPlatform().name("stock-orderbook-worker-", 0).factory()
        );
        for (int index = 0; index < workerCount; index++) {
            executorService.submit(this::runLoop);
        }
        log.info("Order-book execution workers started: workerCount={}", workerCount);
    }

    private void runLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            if (!canExecute()) {
                pause(gateRefreshMillis);
                continue;
            }
            try {
                int matchCount = runTimer.record(executionService::executeReadyOrders);
                runCounter.increment();
                if (matchCount > 0) {
                    matchCounter.increment(matchCount);
                    if (matchYieldMillis > 0) {
                        pause(matchYieldMillis);
                    }
                    continue;
                }
            } catch (RuntimeException ex) {
                failureCounter.increment();
                log.warn("Order-book execution worker run failed: reason={}", ex.getMessage(), ex);
            }
            pause(idleDelayMillis);
        }
    }

    private boolean canExecute() {
        long now = System.nanoTime();
        if (now < nextGateRefreshNanos) {
            return executionEnabled;
        }
        synchronized (gateMonitor) {
            now = System.nanoTime();
            if (now < nextGateRefreshNanos) {
                return executionEnabled;
            }
            try {
                executionEnabled = simulationMarketSessionService.isRegularSession()
                        && marketSessionFenceService.hasOpenOrderBookMarket()
                        && batchJobRuntimeControl.shouldRunScheduledJob(OrderBookExecutionJob.JOB_NAME, true);
            } catch (RuntimeException ex) {
                executionEnabled = false;
                log.warn("Order-book execution worker gate refresh failed: reason={}", ex.getMessage(), ex);
            } finally {
                nextGateRefreshNanos = now + TimeUnit.MILLISECONDS.toNanos(gateRefreshMillis);
            }
            return executionEnabled;
        }
    }

    private void pause(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void shutdown() {
        stop();
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        ExecutorService executor = executorService;
        executorService = null;
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                log.warn("Order-book execution workers did not stop within timeout; interrupting remaining work");
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return LIFECYCLE_PHASE;
    }
}
