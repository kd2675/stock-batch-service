package stock.batch.service.scheduler;

import jakarta.annotation.PostConstruct;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import stock.batch.service.automarket.biz.AutoMarketDailyRegimePreCreateService;
import stock.batch.service.automarket.biz.AutoMarketProfileQueueReconcileService;
import stock.batch.service.automarket.config.AutoMarketRunExecutorConfig;
import stock.batch.service.batch.automarket.job.AutoMarketDailyRegimePreCreateJob;
import stock.batch.service.batch.automarket.job.AutoMarketJob;
import stock.batch.service.batch.automarket.job.AutoMarketOrderExpiryJob;
import stock.batch.service.batch.automarket.job.AutoMarketProfileQueueReconcileJob;
import stock.batch.service.batch.automarket.job.ListingAutoMarketJob;
import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;
import stock.batch.service.simulation.SimulationMarketSessionService;

@Component
@Slf4j
public class AutoMarketScheduler {

    private static final long MIN_AUTO_MARKET_FIXED_RATE_MILLIS = 5_000L;
    private static final long MIN_ORDER_EXPIRY_FIXED_DELAY_MILLIS = 5_000L;
    private static final long MIN_LISTING_AUTO_MARKET_FIXED_DELAY_MILLIS = 5_000L;
    private static final long MIN_PROFILE_QUEUE_RECONCILE_FIXED_DELAY_MILLIS = 60_000L;

    private final StockBatchJobLauncher stockBatchJobLauncher;
    private final StockBatchScheduledJobGuard scheduledJobGuard;
    private final SimulationMarketSessionService simulationMarketSessionService;
    private final MarketSessionFenceService marketSessionFenceService;
    private final AutoMarketDailyRegimePreCreateService autoMarketDailyRegimePreCreateService;
    private final AutoMarketProfileQueueReconcileService autoMarketProfileQueueReconcileService;
    private final Executor autoMarketRunTaskExecutor;

    public AutoMarketScheduler(
            StockBatchJobLauncher stockBatchJobLauncher,
            StockBatchScheduledJobGuard scheduledJobGuard,
            SimulationMarketSessionService simulationMarketSessionService,
            MarketSessionFenceService marketSessionFenceService,
            AutoMarketDailyRegimePreCreateService autoMarketDailyRegimePreCreateService,
            AutoMarketProfileQueueReconcileService autoMarketProfileQueueReconcileService,
            @Qualifier(AutoMarketRunExecutorConfig.AUTO_MARKET_RUN_TASK_EXECUTOR) Executor autoMarketRunTaskExecutor
    ) {
        this.stockBatchJobLauncher = stockBatchJobLauncher;
        this.scheduledJobGuard = scheduledJobGuard;
        this.simulationMarketSessionService = simulationMarketSessionService;
        this.marketSessionFenceService = marketSessionFenceService;
        this.autoMarketDailyRegimePreCreateService = autoMarketDailyRegimePreCreateService;
        this.autoMarketProfileQueueReconcileService = autoMarketProfileQueueReconcileService;
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

    @Value("${stock.batch.post-close.coordinator.enabled:true}")
    private boolean postCloseCoordinatorEnabled;

    @Value("${stock.batch.auto-market.fixed-rate-ms:5000}")
    private long autoMarketFixedRateMillis = 5_000L;

    @Value("${stock.batch.auto-market-order-expiry.fixed-delay-ms:10000}")
    private long orderExpiryFixedDelayMillis = 10_000L;

    @Value("${stock.batch.listing-auto-market.fixed-delay-ms:10000}")
    private long listingAutoMarketFixedDelayMillis = 10_000L;

    @Value("${stock.batch.auto-market.profile-queue.reconcile-fixed-delay-ms:600000}")
    private long profileQueueReconcileFixedDelayMillis = 600_000L;

    @PostConstruct
    void validateVolumeConfiguration() {
        requireMinimum(
                "stock.batch.auto-market.fixed-rate-ms",
                autoMarketFixedRateMillis,
                MIN_AUTO_MARKET_FIXED_RATE_MILLIS
        );
        requireMinimum(
                "stock.batch.auto-market-order-expiry.fixed-delay-ms",
                orderExpiryFixedDelayMillis,
                MIN_ORDER_EXPIRY_FIXED_DELAY_MILLIS
        );
        requireMinimum(
                "stock.batch.listing-auto-market.fixed-delay-ms",
                listingAutoMarketFixedDelayMillis,
                MIN_LISTING_AUTO_MARKET_FIXED_DELAY_MILLIS
        );
        requireMinimum(
                "stock.batch.auto-market.profile-queue.reconcile-fixed-delay-ms",
                profileQueueReconcileFixedDelayMillis,
                MIN_PROFILE_QUEUE_RECONCILE_FIXED_DELAY_MILLIS
        );
    }

    @Scheduled(
            scheduler = StockBatchSchedulerNames.AUTO_MARKET,
            initialDelayString = "${stock.batch.auto-market.initial-delay-ms:5000}",
            fixedRateString = "${stock.batch.auto-market.fixed-rate-ms:5000}"
    )
    public void runAutoMarket() {
        if (!isOrderBookTradingOpen()) {
            return;
        }
        try {
            autoMarketRunTaskExecutor.execute(this::runAutoMarketIfEnabled);
        } catch (RejectedExecutionException ex) {
            log.debug("Auto-market run dispatch skipped because the previous run is still active");
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
        if (postCloseCoordinatorEnabled) {
            return;
        }
        if (!autoMarketDailyRegimePreCreateService.shouldPreCreateDailyRegimes()) {
            return;
        }
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
        if (postCloseCoordinatorEnabled) {
            if (!isOrderBookTradingOpen()) {
                return;
            }
            // PRE_OPEN owns the durable daily preparation. This REGULAR-session path is only a
            // bounded Redis/JVM queue recovery. It reads profile/schedule control tables directly
            // and deliberately avoids Spring Batch metadata and hot order/execution tables.
            scheduledJobGuard.runIfEnabled(
                    AutoMarketProfileQueueReconcileJob.JOB_NAME,
                    autoMarketProfileQueueReconcileSchedulerConfigured,
                    autoMarketProfileQueueReconcileService::reconcileReadyProfiles
            );
            return;
        }
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
        if (!isOrderBookTradingOpen()) {
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
        if (!isOrderBookTradingOpen()) {
            return;
        }
        scheduledJobGuard.runIfEnabled(
                ListingAutoMarketJob.JOB_NAME,
                listingAutoMarketSchedulerConfigured,
                stockBatchJobLauncher::runListingAutoMarket
        );
    }

    private boolean isOrderBookTradingOpen() {
        return simulationMarketSessionService.isRegularSession()
                && marketSessionFenceService.hasOpenOrderBookMarket();
    }

    private void requireMinimum(String propertyName, long value, long minimum) {
        if (value < minimum) {
            throw new IllegalStateException(
                    "%s must be at least %d: %d".formatted(propertyName, minimum, value)
            );
        }
    }
}
