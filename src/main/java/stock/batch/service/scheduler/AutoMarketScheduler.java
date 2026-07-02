package stock.batch.service.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import stock.batch.service.batch.automarket.job.AutoMarketJob;
import stock.batch.service.batch.common.support.StockBatchJobLauncher;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "stock.batch.auto-market", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AutoMarketScheduler {

    private final StockBatchJobLauncher stockBatchJobLauncher;
    private final StockBatchScheduledJobGuard scheduledJobGuard;

    @Scheduled(
            initialDelayString = "${stock.batch.auto-market.initial-delay-ms:5000}",
            fixedDelayString = "${stock.batch.auto-market.fixed-delay-ms:1000}"
    )
    public void runAutoMarket() {
        scheduledJobGuard.runIfEnabled(AutoMarketJob.JOB_NAME, true, stockBatchJobLauncher::runAutoMarket);
    }
}
