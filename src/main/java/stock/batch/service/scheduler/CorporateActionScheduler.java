package stock.batch.service.scheduler;

import lombok.RequiredArgsConstructor;
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

    @Scheduled(
            scheduler = StockBatchSchedulerNames.MAINTENANCE,
            initialDelayString = "${stock.batch.corporate-actions.initial-delay-ms:20000}",
            fixedDelayString = "${stock.batch.corporate-actions.fixed-delay-ms:60000}"
    )
    public void applyCorporateActions() {
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
