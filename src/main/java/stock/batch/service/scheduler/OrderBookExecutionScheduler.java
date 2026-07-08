package stock.batch.service.scheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.batch.execution.job.OrderBookExecutionJob;
import stock.batch.service.execution.config.OrderBookExecutionRunExecutorConfig;
import stock.batch.service.simulation.SimulationMarketSessionService;

@Component
@Slf4j
@ConditionalOnProperty(prefix = "stock.batch.order-book-execution", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrderBookExecutionScheduler {

    private final StockBatchJobLauncher stockBatchJobLauncher;
    private final StockBatchScheduledJobGuard scheduledJobGuard;
    private final SimulationMarketSessionService simulationMarketSessionService;
    private final Executor orderBookExecutionRunTaskExecutor;

    public OrderBookExecutionScheduler(
            StockBatchJobLauncher stockBatchJobLauncher,
            StockBatchScheduledJobGuard scheduledJobGuard,
            SimulationMarketSessionService simulationMarketSessionService,
            @Qualifier(OrderBookExecutionRunExecutorConfig.ORDER_BOOK_EXECUTION_RUN_TASK_EXECUTOR) Executor orderBookExecutionRunTaskExecutor
    ) {
        this.stockBatchJobLauncher = stockBatchJobLauncher;
        this.scheduledJobGuard = scheduledJobGuard;
        this.simulationMarketSessionService = simulationMarketSessionService;
        this.orderBookExecutionRunTaskExecutor = orderBookExecutionRunTaskExecutor;
    }

    @Scheduled(
            scheduler = StockBatchSchedulerNames.EXECUTION,
            initialDelayString = "${stock.batch.order-book-execution.initial-delay-ms:36000}",
            fixedRateString = "${stock.batch.order-book-execution.fixed-rate-ms:5000}"
    )
    public void executeOrderBookOrders() {
        if (!simulationMarketSessionService.isRegularSession()) {
            return;
        }
        try {
            orderBookExecutionRunTaskExecutor.execute(this::executeOrderBookOrdersIfEnabled);
        } catch (RejectedExecutionException ex) {
            log.warn("Order-book execution run dispatch skipped because dispatcher is saturated: reason={}", ex.getMessage());
        }
    }

    private void executeOrderBookOrdersIfEnabled() {
        scheduledJobGuard.runIfEnabled(
                OrderBookExecutionJob.JOB_NAME,
                true,
                stockBatchJobLauncher::executeOrderBookOrders
        );
    }
}
