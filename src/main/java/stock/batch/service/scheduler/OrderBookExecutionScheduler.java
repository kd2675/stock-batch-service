package stock.batch.service.scheduler;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.batch.execution.job.OrderBookExecutionJob;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;
import stock.batch.service.simulation.SimulationMarketSessionService;

@Component
@ConditionalOnProperty(prefix = "stock.batch.order-book-execution", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrderBookExecutionScheduler {

    private static final long MIN_FALLBACK_FIXED_DELAY_MILLIS = 10_000L;
    private static final long MAX_FALLBACK_FIXED_DELAY_MILLIS = 300_000L;

    private final StockBatchJobLauncher stockBatchJobLauncher;
    private final StockBatchScheduledJobGuard scheduledJobGuard;
    private final SimulationMarketSessionService simulationMarketSessionService;
    private final MarketSessionFenceService marketSessionFenceService;

    @Value("${stock.batch.order-book-execution.fixed-delay-ms:30000}")
    private long fallbackFixedDelayMillis = 30_000L;

    public OrderBookExecutionScheduler(
            StockBatchJobLauncher stockBatchJobLauncher,
            StockBatchScheduledJobGuard scheduledJobGuard,
            SimulationMarketSessionService simulationMarketSessionService,
            MarketSessionFenceService marketSessionFenceService
    ) {
        this.stockBatchJobLauncher = stockBatchJobLauncher;
        this.scheduledJobGuard = scheduledJobGuard;
        this.simulationMarketSessionService = simulationMarketSessionService;
        this.marketSessionFenceService = marketSessionFenceService;
    }

    @PostConstruct
    void validateFallbackConfiguration() {
        if (fallbackFixedDelayMillis < MIN_FALLBACK_FIXED_DELAY_MILLIS
                || fallbackFixedDelayMillis > MAX_FALLBACK_FIXED_DELAY_MILLIS) {
            throw new IllegalStateException(
                    "stock.batch.order-book-execution.fixed-delay-ms must be between %d and %d: %d"
                            .formatted(
                                    MIN_FALLBACK_FIXED_DELAY_MILLIS,
                                    MAX_FALLBACK_FIXED_DELAY_MILLIS,
                                    fallbackFixedDelayMillis
                            )
            );
        }
    }

    @Scheduled(
            scheduler = StockBatchSchedulerNames.EXECUTION,
            initialDelayString = "${stock.batch.order-book-execution.initial-delay-ms:36000}",
            fixedDelayString = "${stock.batch.order-book-execution.fixed-delay-ms:30000}"
    )
    public void executeOrderBookOrders() {
        if (!simulationMarketSessionService.isRegularSession()
                || !marketSessionFenceService.hasOpenOrderBookMarket()) {
            return;
        }
        scheduledJobGuard.runIfEnabled(
                OrderBookExecutionJob.JOB_NAME,
                true,
                stockBatchJobLauncher::executeOrderBookOrders
        );
    }
}
