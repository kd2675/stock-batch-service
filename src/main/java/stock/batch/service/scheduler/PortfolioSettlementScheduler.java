package stock.batch.service.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import stock.batch.service.batch.common.policy.BatchJobRuntimeControl;
import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.batch.marketclose.job.MarketCloseRolloverJob;
import stock.batch.service.batch.settlement.job.PortfolioSettlementJob;

@Component
@RequiredArgsConstructor
public class PortfolioSettlementScheduler {

    private final StockBatchJobLauncher stockBatchJobLauncher;
    private final BatchJobRuntimeControl batchJobRuntimeControl;

    @Value("${stock.batch.market-close.enabled:true}")
    private boolean marketCloseSchedulerConfigured;

    @Value("${stock.batch.settlement.enabled:true}")
    private boolean settlementSchedulerConfigured;

    @Scheduled(
            cron = "${stock.batch.settlement.cron:0 40 15 * * MON-FRI}",
            zone = "${stock.batch.settlement.zone:Asia/Seoul}"
    )
    public void settlePortfolios() {
        if (batchJobRuntimeControl.shouldRunScheduledJob(
                MarketCloseRolloverJob.JOB_NAME,
                marketCloseSchedulerConfigured
        )) {
            stockBatchJobLauncher.rolloverClosingPrices();
        }
        if (batchJobRuntimeControl.shouldRunScheduledJob(
                PortfolioSettlementJob.JOB_NAME,
                settlementSchedulerConfigured
        )) {
            stockBatchJobLauncher.settlePortfolios();
        }
    }
}
