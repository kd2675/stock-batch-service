package stock.batch.service.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.batch.corporateaction.job.CorporateActionJob;
import stock.batch.service.simulation.SimulationMarketSessionService;
import web.common.core.simulation.SimulationMarketSession;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "stock.batch.corporate-actions", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CorporateActionScheduler {

    private final StockBatchJobLauncher stockBatchJobLauncher;
    private final StockBatchScheduledJobGuard scheduledJobGuard;
    private final SimulationMarketSessionService simulationMarketSessionService;

    @Value("${stock.batch.post-close.coordinator.enabled:true}")
    private boolean postCloseCoordinatorEnabled;

    @Scheduled(
            scheduler = StockBatchSchedulerNames.MAINTENANCE,
            initialDelayString = "${stock.batch.corporate-actions.initial-delay-ms:20000}",
            fixedDelayString = "${stock.batch.corporate-actions.fixed-delay-ms:60000}"
    )
    public void applyCorporateActions() {
        if (postCloseCoordinatorEnabled) {
            return;
        }
        if (simulationMarketSessionService.currentSession() == SimulationMarketSession.REGULAR) {
            return;
        }
        scheduledJobGuard.runIfEnabled(
                CorporateActionJob.JOB_NAME,
                true,
                stockBatchJobLauncher::applyCorporateActionsScheduled
        );
    }
}
