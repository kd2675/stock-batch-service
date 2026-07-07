package stock.batch.service.batch.common.support;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import stock.batch.service.automarket.biz.AutoParticipantCashFlowService;
import stock.batch.service.automarket.biz.AutoMarketDailyRegimePreCreateService;
import stock.batch.service.automarket.biz.AutoMarketService;
import stock.batch.service.automarket.biz.AutoMarketOrderExpiryJobService;
import stock.batch.service.automarket.biz.AutoMarketProfileQueueReconcileService;
import stock.batch.service.automarket.biz.ListingAutoMarketJobService;
import stock.batch.service.batch.automarket.job.AutoParticipantCashFlowJob;
import stock.batch.service.batch.automarket.job.AutoMarketDailyRegimePreCreateJob;
import stock.batch.service.batch.automarket.job.AutoMarketJob;
import stock.batch.service.batch.automarket.job.AutoMarketOrderExpiryJob;
import stock.batch.service.batch.automarket.job.AutoMarketProfileQueueReconcileJob;
import stock.batch.service.batch.automarket.job.ListingAutoMarketJob;
import stock.batch.service.batch.common.policy.BatchJobLockRegistry;
import stock.batch.service.batch.corporateaction.job.CorporateActionJob;
import stock.batch.service.batch.execution.job.OrderBookExecutionJob;
import stock.batch.service.batch.execution.job.VirtualPriceExecutionJob;
import stock.batch.service.batch.holdingcleanup.job.HoldingCleanupJob;
import stock.batch.service.batch.marketclose.job.MarketCloseRolloverJob;
import stock.batch.service.batch.marketdata.job.MarketDataRefreshJob;
import stock.batch.service.batch.settlement.job.PortfolioSettlementJob;
import stock.batch.service.corporateaction.biz.CorporateActionService;
import stock.batch.service.execution.biz.InternalOrderBookExecutionService;
import stock.batch.service.execution.biz.OrderExecutionService;
import stock.batch.service.holdingcleanup.biz.HoldingCleanupService;
import stock.batch.service.marketclose.biz.MarketCloseRolloverService;
import stock.batch.service.marketdata.biz.MarketDataRefreshService;
import stock.batch.service.settlement.biz.PortfolioSettlementService;
import stock.batch.service.testsupport.BatchTestDatabaseFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockBatchJobLauncherTest {

    private final MarketDataRefreshService marketDataRefreshService = mock(MarketDataRefreshService.class);
    private final OrderExecutionService orderExecutionService = mock(OrderExecutionService.class);
    private final InternalOrderBookExecutionService internalOrderBookExecutionService = mock(InternalOrderBookExecutionService.class);
    private final PortfolioSettlementService portfolioSettlementService = mock(PortfolioSettlementService.class);
    private final AutoParticipantCashFlowService autoParticipantCashFlowService = mock(AutoParticipantCashFlowService.class);
    private final AutoMarketDailyRegimePreCreateService autoMarketDailyRegimePreCreateService =
            mock(AutoMarketDailyRegimePreCreateService.class);
    private final AutoMarketProfileQueueReconcileService autoMarketProfileQueueReconcileService =
            mock(AutoMarketProfileQueueReconcileService.class);
    private final AutoMarketService autoMarketService = mock(AutoMarketService.class);
    private final AutoMarketOrderExpiryJobService autoMarketOrderExpiryJobService = mock(AutoMarketOrderExpiryJobService.class);
    private final ListingAutoMarketJobService listingAutoMarketJobService = mock(ListingAutoMarketJobService.class);
    private final CorporateActionService corporateActionService = mock(CorporateActionService.class);
    private final MarketCloseRolloverService marketCloseRolloverService = mock(MarketCloseRolloverService.class);
    private final HoldingCleanupService holdingCleanupService = mock(HoldingCleanupService.class);
    private final StockBatchJobExecutionRecord executionRecord = mock(StockBatchJobExecutionRecord.class);
    private final StockBatchJobRepositoryRecorder stockBatchJobRepositoryRecorder = mock(StockBatchJobRepositoryRecorder.class);

    private final StockBatchJobLauncher stockBatchJobLauncher = new StockBatchJobLauncher(
            new StockBatchJobRunner(createBatchJobLockRegistry("launcher-default"), stockBatchJobRepositoryRecorder),
            new MarketDataRefreshJob(marketDataRefreshService),
            new VirtualPriceExecutionJob(orderExecutionService),
            new OrderBookExecutionJob(internalOrderBookExecutionService),
            new AutoParticipantCashFlowJob(autoParticipantCashFlowService),
            new AutoMarketDailyRegimePreCreateJob(autoMarketDailyRegimePreCreateService),
            new AutoMarketProfileQueueReconcileJob(autoMarketProfileQueueReconcileService),
            new AutoMarketJob(autoMarketService),
            new AutoMarketOrderExpiryJob(autoMarketOrderExpiryJobService),
            new ListingAutoMarketJob(listingAutoMarketJobService),
            new PortfolioSettlementJob(portfolioSettlementService),
            new MarketCloseRolloverJob(marketCloseRolloverService),
            new CorporateActionJob(corporateActionService),
            new HoldingCleanupJob(holdingCleanupService)
    );

    StockBatchJobLauncherTest() {
        when(stockBatchJobRepositoryRecorder.start(any(), any())).thenReturn(executionRecord);
    }

    @Test
    void executeVirtualPriceOrders_usesMarketPriceExecution() {
        when(orderExecutionService.executeEligibleOrders()).thenReturn(3);

        var response = stockBatchJobLauncher.executeVirtualPriceOrders();

        assertThat(response.job()).isEqualTo("virtual-price-execution");
        assertThat(response.executionMode()).isEqualTo("virtual-price");
        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.processedCount()).isEqualTo(3);
        assertThat(response.message()).isEqualTo("Job completed");
        verify(orderExecutionService).executeEligibleOrders();
        verify(internalOrderBookExecutionService, never()).executeEligibleOrders();
    }

    @Test
    void executeOrderBookOrders_usesOrderBookExecution() {
        when(internalOrderBookExecutionService.executeEligibleOrders()).thenReturn(2);

        var response = stockBatchJobLauncher.executeOrderBookOrders();

        assertThat(response.job()).isEqualTo("order-book-execution");
        assertThat(response.executionMode()).isEqualTo("order-book");
        assertThat(response.processedCount()).isEqualTo(2);
        verify(internalOrderBookExecutionService).executeEligibleOrders();
        verify(orderExecutionService, never()).executeEligibleOrders();
    }

    @Test
    void refreshMarketData_manualRun_invokesMarketDataRefresh() {
        when(marketDataRefreshService.refreshWatchedPrices()).thenReturn(5);

        var response = stockBatchJobLauncher.refreshMarketData();

        assertThat(response.job()).isEqualTo("market-data-refresh");
        assertThat(response.processedCount()).isEqualTo(5);
        verify(marketDataRefreshService).refreshWatchedPrices();
    }

    @Test
    void settlePortfolios_manualRun_invokesPortfolioSettlement() {
        when(portfolioSettlementService.settleToday()).thenReturn(4);

        var response = stockBatchJobLauncher.settlePortfolios();

        assertThat(response.job()).isEqualTo("portfolio-settlement");
        assertThat(response.processedCount()).isEqualTo(4);
        verify(portfolioSettlementService).settleToday();
    }

    @Test
    void rolloverClosingPrices_manualRun_invokesMarketCloseRollover() {
        when(marketCloseRolloverService.rolloverClosingPrices()).thenReturn(2);

        var response = stockBatchJobLauncher.rolloverClosingPrices();

        assertThat(response.job()).isEqualTo("market-close-rollover");
        assertThat(response.executionMode()).isEqualTo("price-limit-base");
        assertThat(response.processedCount()).isEqualTo(2);
        verify(marketCloseRolloverService).rolloverClosingPrices();
    }

    @Test
    void rolloverClosingPrices_symbolManualRun_invokesMarketCloseRolloverForSymbol() {
        when(marketCloseRolloverService.rolloverClosingPrices("MC001")).thenReturn(4);

        var response = stockBatchJobLauncher.rolloverClosingPrices("MC001");

        assertThat(response.job()).isEqualTo("market-close-rollover");
        assertThat(response.executionMode()).isEqualTo("price-limit-base:MC001");
        assertThat(response.processedCount()).isEqualTo(4);
        verify(marketCloseRolloverService).rolloverClosingPrices("MC001");
    }

    @Test
    void cancelOpenOrderBookOrders_symbolManualRun_invokesOpenOrderCancellationForSymbol() {
        when(marketCloseRolloverService.cancelOpenOrderBookOrders("MC001")).thenReturn(2);

        var response = stockBatchJobLauncher.cancelOpenOrderBookOrders("MC001");

        assertThat(response.job()).isEqualTo("market-close-rollover");
        assertThat(response.executionMode()).isEqualTo("halt-open-order-cancel:MC001");
        assertThat(response.processedCount()).isEqualTo(2);
        verify(marketCloseRolloverService).cancelOpenOrderBookOrders("MC001");
    }

    @Test
    void applyCorporateActions_manualRun_invokesCorporateActionService() {
        when(corporateActionService.applyDueCorporateActions()).thenReturn(3);

        var response = stockBatchJobLauncher.applyCorporateActions();

        assertThat(response.job()).isEqualTo("corporate-actions");
        assertThat(response.executionMode()).isEqualTo("order-book");
        assertThat(response.processedCount()).isEqualTo(3);
        verify(corporateActionService).applyDueCorporateActions();
    }

    @Test
    void cleanupEmptyHoldings_manualRun_invokesHoldingCleanup() {
        when(holdingCleanupService.cleanupEmptyHoldings()).thenReturn(9);

        var response = stockBatchJobLauncher.cleanupEmptyHoldings();

        assertThat(response.job()).isEqualTo("holding-cleanup");
        assertThat(response.executionMode()).isEqualTo("maintenance");
        assertThat(response.processedCount()).isEqualTo(9);
        verify(holdingCleanupService).cleanupEmptyHoldings();
    }

    @Test
    void fundAutoParticipants_manualRun_invokesAutoParticipantCashFlow() {
        when(autoParticipantCashFlowService.fundRecurringCash()).thenReturn(6);

        var response = stockBatchJobLauncher.fundAutoParticipants();

        assertThat(response.job()).isEqualTo("auto-participant-cash-flow");
        assertThat(response.executionMode()).isEqualTo("recurring-cash");
        assertThat(response.processedCount()).isEqualTo(6);
        verify(autoParticipantCashFlowService).fundRecurringCash();
        verify(autoMarketService, never()).runAutoMarketStep();
        verify(internalOrderBookExecutionService, never()).executeEligibleOrders();
    }

    @Test
    void fundAutoParticipantsManually_manualRun_ignoresRecurringIntervalGuard() {
        when(autoParticipantCashFlowService.fundRecurringCashManually()).thenReturn(6);

        var response = stockBatchJobLauncher.fundAutoParticipantsManually();

        assertThat(response.job()).isEqualTo("auto-participant-cash-flow");
        assertThat(response.executionMode()).isEqualTo("manual-recurring-cash");
        assertThat(response.processedCount()).isEqualTo(6);
        verify(autoParticipantCashFlowService).fundRecurringCashManually();
        verify(autoParticipantCashFlowService, never()).fundRecurringCash();
        verify(autoMarketService, never()).runAutoMarketStep();
        verify(internalOrderBookExecutionService, never()).executeEligibleOrders();
    }

    @Test
    void runAutoMarket_generatesOrdersWithoutInlineExecution() {
        when(autoMarketService.runAutoMarketStep()).thenReturn(7);

        var response = stockBatchJobLauncher.runAutoMarket();

        assertThat(response.job()).isEqualTo("auto-market");
        assertThat(response.executionMode()).isEqualTo("order-book");
        assertThat(response.processedCount()).isEqualTo(7);
        verify(autoMarketService).runAutoMarketStep();
        verify(autoMarketOrderExpiryJobService, never()).expireAutoMarketOrders();
        verify(listingAutoMarketJobService, never()).runListingAutoMarket();
        verify(internalOrderBookExecutionService, never()).executeEligibleOrders();
    }

    @Test
    void preCreateAutoMarketDailyRegimes_manualRun_invokesDailyRegimePreCreateJob() {
        when(autoMarketDailyRegimePreCreateService.preCreateDailyRegimes()).thenReturn(3);

        var response = stockBatchJobLauncher.preCreateAutoMarketDailyRegimes();

        assertThat(response.job()).isEqualTo("auto-market-daily-regime-pre-create");
        assertThat(response.executionMode()).isEqualTo("daily-regime");
        assertThat(response.processedCount()).isEqualTo(3);
        verify(autoMarketDailyRegimePreCreateService).preCreateDailyRegimes();
        verify(autoMarketService, never()).runAutoMarketStep();
    }

    @Test
    void expireAutoMarketOrders_manualRun_invokesExpiryJob() {
        when(autoMarketOrderExpiryJobService.expireAutoMarketOrders()).thenReturn(4);

        var response = stockBatchJobLauncher.expireAutoMarketOrders();

        assertThat(response.job()).isEqualTo("auto-market-order-expiry");
        assertThat(response.executionMode()).isEqualTo("order-book");
        assertThat(response.processedCount()).isEqualTo(4);
        verify(autoMarketOrderExpiryJobService).expireAutoMarketOrders();
        verify(autoMarketService, never()).runAutoMarketStep();
    }

    @Test
    void runListingAutoMarket_manualRun_invokesListingAutoMarketJob() {
        when(listingAutoMarketJobService.runListingAutoMarket()).thenReturn(5);

        var response = stockBatchJobLauncher.runListingAutoMarket();

        assertThat(response.job()).isEqualTo("listing-auto-market");
        assertThat(response.executionMode()).isEqualTo("order-book");
        assertThat(response.processedCount()).isEqualTo(5);
        verify(listingAutoMarketJobService).runListingAutoMarket();
        verify(autoMarketService, never()).runAutoMarketStep();
    }

    @Test
    void executeVirtualPriceOrders_sameJobAlreadyRunning_skipsSecondRun() throws Exception {
        CountDownLatch actionStarted = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        doAnswer(invocation -> {
            actionStarted.countDown();
            assertThat(releaseAction.await(3, TimeUnit.SECONDS)).isTrue();
            return 3;
        }).when(orderExecutionService).executeEligibleOrders();

        var executor = Executors.newSingleThreadExecutor();
        try {
            var firstRun = executor.submit(stockBatchJobLauncher::executeVirtualPriceOrders);
            assertThat(actionStarted.await(3, TimeUnit.SECONDS)).isTrue();

            var secondResponse = stockBatchJobLauncher.executeVirtualPriceOrders();

            assertThat(secondResponse.status()).isEqualTo("SKIPPED");
            assertThat(secondResponse.processedCount()).isZero();
            assertThat(secondResponse.message()).isEqualTo("Job is already running");
            releaseAction.countDown();
            assertThat(firstRun.get(3, TimeUnit.SECONDS).status()).isEqualTo("COMPLETED");
            verify(orderExecutionService, times(1)).executeEligibleOrders();
        } finally {
            releaseAction.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void executeVirtualPriceOrders_separateRunnersShareDatabaseLock_skipsSecondRun() throws Exception {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        StockBatchJobLauncher firstLauncher = createLauncher(createBatchJobLockRegistry(jdbcTemplate, "launcher-1"));
        StockBatchJobLauncher secondLauncher = createLauncher(createBatchJobLockRegistry(jdbcTemplate, "launcher-2"));
        CountDownLatch actionStarted = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        doAnswer(invocation -> {
            actionStarted.countDown();
            assertThat(releaseAction.await(3, TimeUnit.SECONDS)).isTrue();
            return 3;
        }).when(orderExecutionService).executeEligibleOrders();

        var executor = Executors.newSingleThreadExecutor();
        try {
            var firstRun = executor.submit(firstLauncher::executeVirtualPriceOrders);
            assertThat(actionStarted.await(3, TimeUnit.SECONDS)).isTrue();

            var secondResponse = secondLauncher.executeVirtualPriceOrders();

            assertThat(secondResponse.status()).isEqualTo("SKIPPED");
            assertThat(secondResponse.message()).isEqualTo("Job is already running");
            releaseAction.countDown();
            assertThat(firstRun.get(3, TimeUnit.SECONDS).status()).isEqualTo("COMPLETED");
            verify(orderExecutionService, times(1)).executeEligibleOrders();
        } finally {
            releaseAction.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void refreshMarketData_actionThrows_returnsFailedResponse() {
        doThrow(new IllegalStateException("provider unavailable"))
                .when(marketDataRefreshService)
                .refreshWatchedPrices();

        var response = stockBatchJobLauncher.refreshMarketData();

        assertThat(response.job()).isEqualTo("market-data-refresh");
        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.processedCount()).isZero();
        assertThat(response.message()).contains("provider unavailable");
    }

    private StockBatchJobLauncher createLauncher(BatchJobLockRegistry batchJobLockRegistry) {
        return new StockBatchJobLauncher(
                new StockBatchJobRunner(batchJobLockRegistry, stockBatchJobRepositoryRecorder),
                new MarketDataRefreshJob(marketDataRefreshService),
                new VirtualPriceExecutionJob(orderExecutionService),
                new OrderBookExecutionJob(internalOrderBookExecutionService),
                new AutoParticipantCashFlowJob(autoParticipantCashFlowService),
                new AutoMarketDailyRegimePreCreateJob(autoMarketDailyRegimePreCreateService),
                new AutoMarketProfileQueueReconcileJob(autoMarketProfileQueueReconcileService),
                new AutoMarketJob(autoMarketService),
                new AutoMarketOrderExpiryJob(autoMarketOrderExpiryJobService),
                new ListingAutoMarketJob(listingAutoMarketJobService),
                new PortfolioSettlementJob(portfolioSettlementService),
                new MarketCloseRolloverJob(marketCloseRolloverService),
                new CorporateActionJob(corporateActionService),
                new HoldingCleanupJob(holdingCleanupService)
        );
    }

    private BatchJobLockRegistry createBatchJobLockRegistry(String lockOwner) {
        return createBatchJobLockRegistry(createJdbcTemplate(), lockOwner);
    }

    private BatchJobLockRegistry createBatchJobLockRegistry(JdbcTemplate jdbcTemplate, String lockOwner) {
        return new BatchJobLockRegistry(jdbcTemplate, 1800, lockOwner);
    }

    private JdbcTemplate createJdbcTemplate() {
        return BatchTestDatabaseFactory.createJobLockJdbcTemplate("stock_batch_job_launcher_test");
    }
}
