package stock.batch.service.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import stock.batch.service.batch.automarket.job.AutoParticipantCashFlowJob;
import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.simulation.SimulationMarketSessionService;
import web.common.core.simulation.SimulationMarketSession;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "stock.batch.auto-participant-cash-flow", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AutoParticipantCashFlowScheduler {

    private final StockBatchJobLauncher stockBatchJobLauncher;
    private final StockBatchScheduledJobGuard scheduledJobGuard;
    private final SimulationMarketSessionService simulationMarketSessionService;

    @Value("${stock.batch.post-close.coordinator.enabled:true}")
    private boolean postCloseCoordinatorEnabled;

    @Scheduled(
            scheduler = StockBatchSchedulerNames.MAINTENANCE,
            initialDelayString = "${stock.batch.auto-participant-cash-flow.initial-delay-ms:5000}",
            fixedDelayString = "${stock.batch.auto-participant-cash-flow.fixed-delay-ms:300000}"
    )
    public void fundAutoParticipants() {
        if (postCloseCoordinatorEnabled) {
            return;
        }
        if (simulationMarketSessionService.currentSession() != SimulationMarketSession.PRE_OPEN) {
            return;
        }
        scheduledJobGuard.runIfEnabled(
                AutoParticipantCashFlowJob.JOB_NAME,
                true,
                stockBatchJobLauncher::fundAutoParticipants
        );
    }
}
