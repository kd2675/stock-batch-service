package stock.batch.service.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.batch.execution.job.OrderBookExecutionJob;
import stock.batch.service.simulation.SimulationMarketSessionService;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "stock.batch.order-book-execution", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrderBookExecutionScheduler {

    private final StockBatchJobLauncher stockBatchJobLauncher;
    private final StockBatchScheduledJobGuard scheduledJobGuard;
    private final SimulationMarketSessionService simulationMarketSessionService;

    @Scheduled(
            scheduler = StockBatchSchedulerNames.EXECUTION,
            initialDelayString = "${stock.batch.order-book-execution.initial-delay-ms:36000}",
            fixedDelayString = "${stock.batch.order-book-execution.fixed-delay-ms:1000}"
    )
    public void executeOrderBookOrders() {
        if (!simulationMarketSessionService.isRegularSession()) {
            return;
        }
        scheduledJobGuard.runIfEnabled(
                OrderBookExecutionJob.JOB_NAME,
                true,
                stockBatchJobLauncher::executeOrderBookOrders
        );
    }
}
