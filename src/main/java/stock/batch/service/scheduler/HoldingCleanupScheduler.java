package stock.batch.service.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.batch.holdingcleanup.job.HoldingCleanupJob;
import stock.batch.service.simulation.SimulationMarketSessionService;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "stock.batch.holding-cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HoldingCleanupScheduler {

    private final StockBatchJobLauncher stockBatchJobLauncher;
    private final StockBatchScheduledJobGuard scheduledJobGuard;
    private final SimulationMarketSessionService simulationMarketSessionService;

    @Scheduled(
            scheduler = StockBatchSchedulerNames.MAINTENANCE,
            initialDelayString = "${stock.batch.holding-cleanup.initial-delay-ms:45000}",
            fixedDelayString = "${stock.batch.holding-cleanup.fixed-delay-ms:300000}"
    )
    public void cleanupEmptyHoldings() {
        if (!simulationMarketSessionService.isAfterCloseSession()) {
            return;
        }
        scheduledJobGuard.runIfEnabled(
                HoldingCleanupJob.JOB_NAME,
                true,
                stockBatchJobLauncher::cleanupEmptyHoldings
        );
    }
}
