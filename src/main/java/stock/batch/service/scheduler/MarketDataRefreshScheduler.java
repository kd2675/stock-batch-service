package stock.batch.service.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.batch.marketdata.job.MarketDataRefreshJob;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "stock.batch.market-data", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MarketDataRefreshScheduler {

    private final StockBatchJobLauncher stockBatchJobLauncher;
    private final StockBatchScheduledJobGuard scheduledJobGuard;

    @Value("${stock.batch.post-close.coordinator.enabled:true}")
    private boolean postCloseCoordinatorEnabled;

    @Scheduled(
            scheduler = StockBatchSchedulerNames.MAINTENANCE,
            initialDelayString = "${stock.batch.market-data.initial-delay-ms:30000}",
            fixedDelayString = "${stock.batch.market-data.fixed-delay-ms:60000}"
    )
    public void refreshMarketData() {
        if (postCloseCoordinatorEnabled) {
            return;
        }
        scheduledJobGuard.runIfEnabled(MarketDataRefreshJob.JOB_NAME, true, stockBatchJobLauncher::refreshMarketData);
    }

}
