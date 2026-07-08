package stock.batch.service.scheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import stock.batch.service.automarket.config.AutoMarketRunExecutorConfig;
import stock.batch.service.batch.automarket.job.AutoMarketDailyRegimePreCreateJob;
import stock.batch.service.batch.automarket.job.AutoMarketJob;
import stock.batch.service.batch.automarket.job.AutoMarketOrderExpiryJob;
import stock.batch.service.batch.automarket.job.AutoMarketProfileQueueReconcileJob;
import stock.batch.service.batch.automarket.job.ListingAutoMarketJob;
import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.simulation.SimulationMarketSessionService;

@Component
@Slf4j
public class AutoMarketScheduler {

    private final StockBatchJobLauncher stockBatchJobLauncher;
    private final StockBatchScheduledJobGuard scheduledJobGuard;
    private final SimulationMarketSessionService simulationMarketSessionService;
    private final Executor autoMarketRunTaskExecutor;

    public AutoMarketScheduler(
            StockBatchJobLauncher stockBatchJobLauncher,
            StockBatchScheduledJobGuard scheduledJobGuard,
            SimulationMarketSessionService simulationMarketSessionService,
            @Qualifier(AutoMarketRunExecutorConfig.AUTO_MARKET_RUN_TASK_EXECUTOR) Executor autoMarketRunTaskExecutor
    ) {
        this.stockBatchJobLauncher = stockBatchJobLauncher;
        this.scheduledJobGuard = scheduledJobGuard;
        this.simulationMarketSessionService = simulationMarketSessionService;
        this.autoMarketRunTaskExecutor = autoMarketRunTaskExecutor;
    }

    @Value("${stock.batch.auto-market.enabled:true}")
    private boolean autoMarketSchedulerConfigured = true;

    @Value("${stock.batch.auto-market.daily-regime.enabled:true}")
    private boolean autoMarketDailyRegimeSchedulerConfigured = true;

    @Value("${stock.batch.auto-market.profile-queue.reconcile-enabled:true}")
    private boolean autoMarketProfileQueueReconcileSchedulerConfigured = true;

    @Value("${stock.batch.auto-market-order-expiry.enabled:true}")
    private boolean autoMarketOrderExpirySchedulerConfigured = true;

    @Value("${stock.batch.listing-auto-market.enabled:true}")
    private boolean listingAutoMarketSchedulerConfigured = true;

    @Scheduled(
            scheduler = StockBatchSchedulerNames.AUTO_MARKET,
            initialDelayString = "${stock.batch.auto-market.initial-delay-ms:5000}",
            fixedRateString = "${stock.batch.auto-market.fixed-rate-ms:5000}"
    )
    public void runAutoMarket() {
        if (!simulationMarketSessionService.isRegularSession()) {
            return;
        }
        try {
            autoMarketRunTaskExecutor.execute(this::runAutoMarketIfEnabled);
        } catch (RejectedExecutionException ex) {
            log.debug("Auto-market run dispatch skipped because dispatcher is saturated: {}", ex.getMessage());
        }
    }

    private void runAutoMarketIfEnabled() {
        scheduledJobGuard.runIfEnabled(AutoMarketJob.JOB_NAME, autoMarketSchedulerConfigured, stockBatchJobLauncher::runAutoMarket);
    }

    @Scheduled(
            scheduler = StockBatchSchedulerNames.AUTO_MARKET,
            initialDelayString = "${stock.batch.auto-market.daily-regime.initial-delay-ms:3000}",
            fixedDelayString = "${stock.batch.auto-market.daily-regime.fixed-delay-ms:10000}"
    )
    public void preCreateDailyRegimes() {
        scheduledJobGuard.runIfEnabled(
                AutoMarketDailyRegimePreCreateJob.JOB_NAME,
                autoMarketDailyRegimeSchedulerConfigured,
                stockBatchJobLauncher::preCreateAutoMarketDailyRegimes
        );
    }

    @Scheduled(
            scheduler = StockBatchSchedulerNames.AUTO_MARKET,
            initialDelayString = "${stock.batch.auto-market.profile-queue.reconcile-initial-delay-ms:4000}",
            fixedDelayString = "${stock.batch.auto-market.profile-queue.reconcile-fixed-delay-ms:600000}"
    )
    public void reconcileProfileQueue() {
        runProfileQueueReconcile();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileProfileQueueOnStartup() {
        runProfileQueueReconcile();
    }

    private void runProfileQueueReconcile() {
        scheduledJobGuard.runIfEnabled(
                AutoMarketProfileQueueReconcileJob.JOB_NAME,
                autoMarketProfileQueueReconcileSchedulerConfigured,
                stockBatchJobLauncher::reconcileAutoMarketProfileQueue
        );
    }

    @Scheduled(
            scheduler = StockBatchSchedulerNames.AUTO_MARKET,
            initialDelayString = "${stock.batch.auto-market-order-expiry.initial-delay-ms:7000}",
            fixedDelayString = "${stock.batch.auto-market-order-expiry.fixed-delay-ms:10000}"
    )
    public void expireAutoMarketOrders() {
        if (!simulationMarketSessionService.isRegularSession()) {
            return;
        }
        scheduledJobGuard.runIfEnabled(
                AutoMarketOrderExpiryJob.JOB_NAME,
                autoMarketOrderExpirySchedulerConfigured,
                stockBatchJobLauncher::expireAutoMarketOrders
        );
    }

    @Scheduled(
            scheduler = StockBatchSchedulerNames.AUTO_MARKET,
            initialDelayString = "${stock.batch.listing-auto-market.initial-delay-ms:8000}",
            fixedDelayString = "${stock.batch.listing-auto-market.fixed-delay-ms:10000}"
    )
    public void runListingAutoMarket() {
        if (!simulationMarketSessionService.isRegularSession()) {
            return;
        }
        scheduledJobGuard.runIfEnabled(
                ListingAutoMarketJob.JOB_NAME,
                listingAutoMarketSchedulerConfigured,
                stockBatchJobLauncher::runListingAutoMarket
        );
    }
}
