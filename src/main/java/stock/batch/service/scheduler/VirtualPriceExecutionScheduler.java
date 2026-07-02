package stock.batch.service.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.batch.execution.job.VirtualPriceExecutionJob;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "stock.batch.virtual-price-execution", name = "enabled", havingValue = "true", matchIfMissing = true)
public class VirtualPriceExecutionScheduler {

    private final StockBatchJobLauncher stockBatchJobLauncher;
    private final StockBatchScheduledJobGuard scheduledJobGuard;

    @Scheduled(
            initialDelayString = "${stock.batch.virtual-price-execution.initial-delay-ms:35000}",
            fixedDelayString = "${stock.batch.virtual-price-execution.fixed-delay-ms:5000}"
    )
    public void executeVirtualPriceOrders() {
        scheduledJobGuard.runIfEnabled(
                VirtualPriceExecutionJob.JOB_NAME,
                true,
                stockBatchJobLauncher::executeVirtualPriceOrders
        );
    }
}
