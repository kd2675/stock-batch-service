package stock.batch.service.scheduler;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import stock.batch.service.automarket.biz.AutoMarketDailyRegimePreCreateService;
import stock.batch.service.automarket.biz.AutoMarketProfileQueueReconcileService;
import stock.batch.service.batch.automarket.job.AutoMarketDailyRegimePreCreateJob;
import stock.batch.service.batch.automarket.job.AutoMarketJob;
import stock.batch.service.batch.automarket.job.AutoMarketOrderExpiryJob;
import stock.batch.service.batch.automarket.job.AutoMarketProfileQueueReconcileJob;
import stock.batch.service.batch.automarket.job.AutoParticipantCashFlowJob;
import stock.batch.service.batch.automarket.job.ListingAutoMarketJob;
import stock.batch.service.batch.common.policy.BatchJobRuntimeControl;
import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.batch.corporateaction.job.CorporateActionJob;
import stock.batch.service.batch.execution.job.OrderBookExecutionJob;
import stock.batch.service.batch.marketclose.job.MarketCloseRolloverJob;
import stock.batch.service.batch.marketdata.job.MarketDataRefreshJob;
import stock.batch.service.batch.settlement.job.PortfolioSettlementJob;
import stock.batch.service.common.vo.StockBatchJobRunResponse;
import stock.batch.service.marketclose.biz.MarketClosePostProcessingCompletionService;
import stock.batch.service.marketclose.biz.MarketCloseRolloverService;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;
import stock.batch.service.marketclose.biz.OrderBookMarketSessionStateService;
import stock.batch.service.marketclose.biz.PostCloseCycleService;
import stock.batch.service.marketclose.model.PostCloseCycle;
import stock.batch.service.marketclose.model.PostCloseCycleStatus;
import stock.batch.service.marketclose.model.PostClosePhase;
import stock.batch.service.marketclose.model.PostCloseScopeType;
import stock.batch.service.batch.settlement.biz.PortfolioSettlementLifecycleService;
import stock.batch.service.simulation.SimulationMarketSessionService;
import web.common.core.simulation.SimulationMarketSession;

import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SchedulerRuntimeControlBehaviorTest {

    private StockBatchJobLauncher stockBatchJobLauncher;
    private BatchJobRuntimeControl batchJobRuntimeControl;
    private StockBatchScheduledJobGuard scheduledJobGuard;
    private SimulationMarketSessionService simulationMarketSessionService;
    private MarketSessionFenceService marketSessionFenceService;
    private AutoMarketDailyRegimePreCreateService autoMarketDailyRegimePreCreateService;
    private AutoMarketProfileQueueReconcileService autoMarketProfileQueueReconcileService;
    private OrderBookMarketSessionStateService orderBookMarketSessionStateService;
    private MarketCloseRolloverService marketCloseRolloverService;
    private MarketClosePostProcessingCompletionService postProcessingCompletionService;
    private PostCloseCycleService postCloseCycleService;
    private PortfolioSettlementLifecycleService portfolioSettlementLifecycleService;

    @BeforeEach
    void setUp() {
        stockBatchJobLauncher = mock(StockBatchJobLauncher.class);
        batchJobRuntimeControl = mock(BatchJobRuntimeControl.class);
        scheduledJobGuard = new StockBatchScheduledJobGuard(batchJobRuntimeControl);
        simulationMarketSessionService = mock(SimulationMarketSessionService.class);
        marketSessionFenceService = mock(MarketSessionFenceService.class);
        autoMarketDailyRegimePreCreateService = mock(AutoMarketDailyRegimePreCreateService.class);
        autoMarketProfileQueueReconcileService = mock(AutoMarketProfileQueueReconcileService.class);
        orderBookMarketSessionStateService = mock(OrderBookMarketSessionStateService.class);
        marketCloseRolloverService = mock(MarketCloseRolloverService.class);
        postProcessingCompletionService = mock(MarketClosePostProcessingCompletionService.class);
        postCloseCycleService = mock(PostCloseCycleService.class);
        portfolioSettlementLifecycleService = mock(PortfolioSettlementLifecycleService.class);
        when(simulationMarketSessionService.isRegularSession()).thenReturn(true);
        when(marketSessionFenceService.hasOpenOrderBookMarket()).thenReturn(true);
        when(simulationMarketSessionService.isAfterCloseSession()).thenReturn(true);
        when(simulationMarketSessionService.currentSession()).thenReturn(SimulationMarketSession.AFTER_CLOSE);
        when(simulationMarketSessionService.currentSimulationDate())
                .thenReturn(java.time.LocalDate.of(2026, 7, 3));
        when(simulationMarketSessionService.baseSimulationDate())
                .thenReturn(java.time.LocalDate.of(2026, 7, 3));
        when(simulationMarketSessionService.currentSimulationDateTime())
                .thenReturn(LocalDateTime.of(2026, 7, 3, 18, 30));
        when(simulationMarketSessionService.closeTime())
                .thenReturn(java.time.LocalTime.of(18, 0));
        when(autoMarketDailyRegimePreCreateService.shouldPreCreateDailyRegimes()).thenReturn(true);
        when(postCloseCycleService.findOldestUnsettledFullMarketCycle()).thenReturn(Optional.empty());
        when(postCloseCycleService.findFullMarketCycle(any())).thenAnswer(invocation -> {
            java.time.LocalDate businessDate = invocation.getArgument(0);
            return Optional.of(frozenCycle(businessDate));
        });
        when(postCloseCycleService.isPhaseClaimEligible(any(), any())).thenReturn(true);
        when(portfolioSettlementLifecycleService.isSettlementEligible(anyLong(), any())).thenReturn(true);
    }

    @Test
    void autoMarketScheduler_checksRuntimeControlBeforeLaunching() {
        AutoMarketScheduler scheduler = newAutoMarketScheduler(command -> command.run());

        assertSimpleSchedulerGate(
                AutoMarketJob.JOB_NAME,
                scheduler::runAutoMarket,
                () -> verify(stockBatchJobLauncher).runAutoMarket()
        );
    }

    @Test
    void autoMarketOrderExpiryScheduler_checksRuntimeControlBeforeLaunching() {
        AutoMarketScheduler scheduler = newAutoMarketScheduler(command -> command.run());

        assertSimpleSchedulerGate(
                AutoMarketOrderExpiryJob.JOB_NAME,
                scheduler::expireAutoMarketOrders,
                () -> verify(stockBatchJobLauncher).expireAutoMarketOrders()
        );
    }

    @Test
    void listingAutoMarketScheduler_checksRuntimeControlBeforeLaunching() {
        AutoMarketScheduler scheduler = newAutoMarketScheduler(command -> command.run());

        assertSimpleSchedulerGate(
                ListingAutoMarketJob.JOB_NAME,
                scheduler::runListingAutoMarket,
                () -> verify(stockBatchJobLauncher).runListingAutoMarket()
        );
    }

    @Test
    void autoMarketDailyRegimePreCreateScheduler_checksRuntimeControlBeforeLaunching() {
        AutoMarketScheduler scheduler = newAutoMarketScheduler(command -> command.run());

        assertSimpleSchedulerGate(
                AutoMarketDailyRegimePreCreateJob.JOB_NAME,
                scheduler::preCreateDailyRegimes,
                () -> verify(stockBatchJobLauncher).preCreateAutoMarketDailyRegimes()
        );
    }

    @Test
    void autoMarketScheduler_outsideRegularSession_skipsBeforeRuntimeControl() {
        AutoMarketScheduler scheduler = newAutoMarketScheduler(command -> command.run());
        when(simulationMarketSessionService.isRegularSession()).thenReturn(false);

        scheduler.runAutoMarket();
        scheduler.expireAutoMarketOrders();
        scheduler.runListingAutoMarket();

        verifyNoInteractions(batchJobRuntimeControl, stockBatchJobLauncher);
    }

    @Test
    void autoMarketScheduler_databaseMarketClosed_skipsTradingJobsBeforeRuntimeControl() {
        AutoMarketScheduler scheduler = newAutoMarketScheduler(command -> command.run());
        when(marketSessionFenceService.hasOpenOrderBookMarket()).thenReturn(false);

        scheduler.runAutoMarket();
        scheduler.expireAutoMarketOrders();
        scheduler.runListingAutoMarket();

        verifyNoInteractions(batchJobRuntimeControl, stockBatchJobLauncher);
    }

    @Test
    void autoMarketDailyRegimePreCreateScheduler_outsideRegularSession_stillChecksRuntimeControlWhenPreCreateWindow() {
        AutoMarketScheduler scheduler = newAutoMarketScheduler(command -> command.run());
        when(simulationMarketSessionService.isRegularSession()).thenReturn(false);

        assertSimpleSchedulerGate(
                AutoMarketDailyRegimePreCreateJob.JOB_NAME,
                scheduler::preCreateDailyRegimes,
                () -> verify(stockBatchJobLauncher).preCreateAutoMarketDailyRegimes()
        );
    }

    @Test
    void autoMarketDailyRegimePreCreateScheduler_outsidePreCreateWindow_skipsBeforeRuntimeControl() {
        AutoMarketScheduler scheduler = newAutoMarketScheduler(command -> command.run());
        when(autoMarketDailyRegimePreCreateService.shouldPreCreateDailyRegimes()).thenReturn(false);

        scheduler.preCreateDailyRegimes();

        verifyNoInteractions(batchJobRuntimeControl, stockBatchJobLauncher);
    }

    @Test
    void autoMarketProfileQueueReconcileScheduler_checksRuntimeControlBeforeLaunching() {
        AutoMarketScheduler scheduler = newAutoMarketScheduler(command -> command.run());

        assertSimpleSchedulerGate(
                AutoMarketProfileQueueReconcileJob.JOB_NAME,
                scheduler::reconcileProfileQueue,
                () -> verify(stockBatchJobLauncher).reconcileAutoMarketProfileQueue()
        );
    }

    @Test
    void autoMarketProfileQueueReconcileScheduler_outsideRegularSession_stillChecksRuntimeControl() {
        AutoMarketScheduler scheduler = newAutoMarketScheduler(command -> command.run());
        when(simulationMarketSessionService.isRegularSession()).thenReturn(false);

        assertSimpleSchedulerGate(
                AutoMarketProfileQueueReconcileJob.JOB_NAME,
                scheduler::reconcileProfileQueue,
                () -> verify(stockBatchJobLauncher).reconcileAutoMarketProfileQueue()
        );
    }

    @Test
    void autoMarketProfileQueueReconcileStartupRun_checksRuntimeControlWithoutRegularSessionGate() {
        AutoMarketScheduler scheduler = newAutoMarketScheduler(command -> command.run());
        when(simulationMarketSessionService.isRegularSession()).thenReturn(false);

        assertSimpleSchedulerGate(
                AutoMarketProfileQueueReconcileJob.JOB_NAME,
                scheduler::reconcileProfileQueueOnStartup,
                () -> verify(stockBatchJobLauncher).reconcileAutoMarketProfileQueue()
        );
    }

    @Test
    void autoMarketScheduler_allowsOnlyOneOverlappingRun() {
        RecordingExecutor executor = new RecordingExecutor(1);
        AutoMarketScheduler scheduler = newAutoMarketScheduler(executor);
        when(batchJobRuntimeControl.shouldRunScheduledJob(AutoMarketJob.JOB_NAME, true)).thenReturn(true);

        scheduler.runAutoMarket();
        scheduler.runAutoMarket();
        scheduler.runAutoMarket();
        scheduler.runAutoMarket();

        verify(batchJobRuntimeControl, never()).shouldRunScheduledJob(AutoMarketJob.JOB_NAME, true);
        verify(stockBatchJobLauncher, never()).runAutoMarket();

        executor.runAll();

        verify(batchJobRuntimeControl).shouldRunScheduledJob(AutoMarketJob.JOB_NAME, true);
        verify(stockBatchJobLauncher).runAutoMarket();
    }

    @Test
    void autoParticipantCashFlowScheduler_checksRuntimeControlBeforeLaunching() {
        AutoParticipantCashFlowScheduler scheduler = new AutoParticipantCashFlowScheduler(
                stockBatchJobLauncher,
                scheduledJobGuard,
                simulationMarketSessionService
        );
        when(simulationMarketSessionService.currentSession()).thenReturn(SimulationMarketSession.PRE_OPEN);

        assertSimpleSchedulerGate(
                AutoParticipantCashFlowJob.JOB_NAME,
                scheduler::fundAutoParticipants,
                () -> verify(stockBatchJobLauncher).fundAutoParticipants()
        );
    }

    @Test
    void corporateActionScheduler_checksRuntimeControlBeforeLaunching() {
        CorporateActionScheduler scheduler = new CorporateActionScheduler(stockBatchJobLauncher, scheduledJobGuard, simulationMarketSessionService);

        assertSimpleSchedulerGate(
                CorporateActionJob.JOB_NAME,
                scheduler::applyCorporateActions,
                () -> verify(stockBatchJobLauncher).applyCorporateActionsScheduled()
        );
    }

    @Test
    void corporateActionScheduler_regularSession_skipsBeforeRuntimeControl() {
        CorporateActionScheduler scheduler = new CorporateActionScheduler(stockBatchJobLauncher, scheduledJobGuard, simulationMarketSessionService);
        when(simulationMarketSessionService.currentSession()).thenReturn(SimulationMarketSession.REGULAR);

        scheduler.applyCorporateActions();

        verifyNoInteractions(batchJobRuntimeControl, stockBatchJobLauncher);
    }

    @Test
    void corporateActionScheduler_preOpen_checksRuntimeControlBeforeLaunching() {
        CorporateActionScheduler scheduler = new CorporateActionScheduler(stockBatchJobLauncher, scheduledJobGuard, simulationMarketSessionService);
        when(simulationMarketSessionService.currentSession()).thenReturn(SimulationMarketSession.PRE_OPEN);

        assertSimpleSchedulerGate(
                CorporateActionJob.JOB_NAME,
                scheduler::applyCorporateActions,
                () -> verify(stockBatchJobLauncher).applyCorporateActionsScheduled()
        );
    }

    @Test
    void marketDataRefreshScheduler_checksRuntimeControlBeforeLaunching() {
        MarketDataRefreshScheduler scheduler = new MarketDataRefreshScheduler(stockBatchJobLauncher, scheduledJobGuard);

        assertSimpleSchedulerGate(
                MarketDataRefreshJob.JOB_NAME,
                scheduler::refreshMarketData,
                () -> verify(stockBatchJobLauncher).refreshMarketData()
        );
    }

    @Test
    void postCloseCoordinatorEnabled_disablesLegacyOverlappingMaintenanceSchedulers() {
        AutoParticipantCashFlowScheduler cashFlowScheduler = new AutoParticipantCashFlowScheduler(
                stockBatchJobLauncher,
                scheduledJobGuard,
                simulationMarketSessionService
        );
        CorporateActionScheduler corporateActionScheduler = new CorporateActionScheduler(
                stockBatchJobLauncher,
                scheduledJobGuard,
                simulationMarketSessionService
        );
        MarketDataRefreshScheduler marketDataScheduler = new MarketDataRefreshScheduler(
                stockBatchJobLauncher,
                scheduledJobGuard
        );
        HoldingCleanupScheduler holdingCleanupScheduler = new HoldingCleanupScheduler(
                stockBatchJobLauncher,
                scheduledJobGuard,
                simulationMarketSessionService
        );
        AutoMarketScheduler autoMarketScheduler = newAutoMarketScheduler(command -> command.run());
        for (Object scheduler : java.util.List.of(
                cashFlowScheduler,
                corporateActionScheduler,
                marketDataScheduler,
                holdingCleanupScheduler,
                autoMarketScheduler
        )) {
            ReflectionTestUtils.setField(scheduler, "postCloseCoordinatorEnabled", true);
        }
        when(simulationMarketSessionService.isRegularSession()).thenReturn(false);

        cashFlowScheduler.fundAutoParticipants();
        corporateActionScheduler.applyCorporateActions();
        marketDataScheduler.refreshMarketData();
        holdingCleanupScheduler.cleanupEmptyHoldings();
        autoMarketScheduler.preCreateDailyRegimes();
        autoMarketScheduler.reconcileProfileQueue();
        autoMarketScheduler.reconcileProfileQueueOnStartup();

        verifyNoInteractions(batchJobRuntimeControl, stockBatchJobLauncher);
        verifyNoInteractions(autoMarketProfileQueueReconcileService);
    }

    @Test
    void postCloseCoordinatorEnabled_regularSession_recoversProfileQueueWithoutBatchMetadata() {
        AutoMarketScheduler scheduler = newAutoMarketScheduler(command -> command.run());
        ReflectionTestUtils.setField(scheduler, "postCloseCoordinatorEnabled", true);
        when(batchJobRuntimeControl.shouldRunScheduledJob(AutoMarketProfileQueueReconcileJob.JOB_NAME, true))
                .thenReturn(true);

        scheduler.reconcileProfileQueueOnStartup();

        verify(autoMarketProfileQueueReconcileService).reconcileReadyProfiles();
        verify(stockBatchJobLauncher, never()).reconcileAutoMarketProfileQueue();
    }

    @Test
    void orderBookExecutionScheduler_checksRuntimeControlBeforeLaunching() {
        OrderBookExecutionScheduler scheduler = newOrderBookExecutionScheduler();

        assertSimpleSchedulerGate(
                OrderBookExecutionJob.JOB_NAME,
                scheduler::executeOrderBookOrders,
                () -> verify(stockBatchJobLauncher).executeOrderBookOrders()
        );
    }

    @Test
    void orderBookExecutionScheduler_outsideRegularSession_skipsBeforeRuntimeControl() {
        OrderBookExecutionScheduler scheduler = newOrderBookExecutionScheduler();
        when(simulationMarketSessionService.isRegularSession()).thenReturn(false);

        scheduler.executeOrderBookOrders();

        verifyNoInteractions(batchJobRuntimeControl, stockBatchJobLauncher);
    }

    @Test
    void orderBookExecutionScheduler_databaseMarketClosed_skipsBeforeRuntimeControl() {
        OrderBookExecutionScheduler scheduler = newOrderBookExecutionScheduler();
        when(marketSessionFenceService.hasOpenOrderBookMarket()).thenReturn(false);

        scheduler.executeOrderBookOrders();

        verifyNoInteractions(batchJobRuntimeControl, stockBatchJobLauncher);
    }

    @Test
    void orderBookExecutionScheduler_runsFallbackSynchronouslyWithoutDispatcherDrops() {
        OrderBookExecutionScheduler scheduler = newOrderBookExecutionScheduler();
        when(batchJobRuntimeControl.shouldRunScheduledJob(OrderBookExecutionJob.JOB_NAME, true)).thenReturn(true);

        scheduler.executeOrderBookOrders();
        scheduler.executeOrderBookOrders();
        scheduler.executeOrderBookOrders();
        scheduler.executeOrderBookOrders();

        verify(batchJobRuntimeControl, times(4)).shouldRunScheduledJob(OrderBookExecutionJob.JOB_NAME, true);
        verify(stockBatchJobLauncher, times(4)).executeOrderBookOrders();
    }

    @Test
    void portfolioSettlementScheduler_checksRuntimeControlForEachJobBeforeLaunching() {
        PortfolioSettlementScheduler scheduler = newPortfolioSettlementScheduler();
        ReflectionTestUtils.setField(scheduler, "marketCloseSchedulerConfigured", true);
        ReflectionTestUtils.setField(scheduler, "settlementSchedulerConfigured", true);

        when(batchJobRuntimeControl.shouldRunScheduledJob(MarketCloseRolloverJob.JOB_NAME, true))
                .thenReturn(false);
        when(batchJobRuntimeControl.shouldRunScheduledJob(PortfolioSettlementJob.JOB_NAME, true))
                .thenReturn(false);

        scheduler.settlePortfolios();

        verify(batchJobRuntimeControl).shouldRunScheduledJob(MarketCloseRolloverJob.JOB_NAME, true);
        verify(batchJobRuntimeControl, never()).shouldRunScheduledJob(PortfolioSettlementJob.JOB_NAME, true);
        verifyNoInteractions(stockBatchJobLauncher);

        reset(stockBatchJobLauncher, batchJobRuntimeControl);
        when(batchJobRuntimeControl.shouldRunScheduledJob(MarketCloseRolloverJob.JOB_NAME, true))
                .thenReturn(true);
        when(batchJobRuntimeControl.shouldRunScheduledJob(PortfolioSettlementJob.JOB_NAME, true))
                .thenReturn(true);
        when(stockBatchJobLauncher.rolloverClosingPricesScheduled())
                .thenReturn(completedResponse(MarketCloseRolloverJob.JOB_NAME));
        when(stockBatchJobLauncher.settlePortfoliosScheduled())
                .thenReturn(completedResponse(PortfolioSettlementJob.JOB_NAME));
        when(marketCloseRolloverService.hasCompletedFullCloseRun(java.time.LocalDate.of(2026, 7, 3)))
                .thenReturn(false, true);
        when(postProcessingCompletionService.isComplete(java.time.LocalDate.of(2026, 7, 3)))
                .thenReturn(false, true);

        scheduler.settlePortfolios();

        verify(batchJobRuntimeControl).shouldRunScheduledJob(MarketCloseRolloverJob.JOB_NAME, true);
        verify(batchJobRuntimeControl).shouldRunScheduledJob(PortfolioSettlementJob.JOB_NAME, true);
        verify(stockBatchJobLauncher).rolloverClosingPricesScheduled();
        verify(stockBatchJobLauncher).settlePortfoliosScheduled();
    }

    @Test
    void portfolioSettlementScheduler_marketOpen_defersBeforeCloseOrSettlementJobs() {
        PortfolioSettlementScheduler scheduler = newPortfolioSettlementScheduler();
        when(marketSessionFenceService.hasOpenMarket()).thenReturn(true);

        boolean completed = scheduler.settlePortfolios(java.time.LocalDate.of(2026, 7, 3));

        org.assertj.core.api.Assertions.assertThat(completed).isFalse();
        verifyNoInteractions(stockBatchJobLauncher);
    }

    @Test
    void portfolioSettlementScheduler_beforeClose_skipsPostCloseWork() {
        PortfolioSettlementScheduler scheduler = newPortfolioSettlementScheduler();
        ReflectionTestUtils.setField(scheduler, "marketCloseSchedulerConfigured", true);
        ReflectionTestUtils.setField(scheduler, "settlementSchedulerConfigured", true);
        when(simulationMarketSessionService.isAfterCloseSession()).thenReturn(false);
        when(simulationMarketSessionService.currentSession()).thenReturn(SimulationMarketSession.REGULAR);

        scheduler.rolloverSimulationDayIfNeeded();

        verify(orderBookMarketSessionStateService).syncCurrentSession();
        verifyNoInteractions(batchJobRuntimeControl, stockBatchJobLauncher);
    }

    @Test
    void portfolioSettlementScheduler_bothConfiguredOff_doesNotTouchSessionOrJobs() {
        PortfolioSettlementScheduler scheduler = newPortfolioSettlementScheduler();
        ReflectionTestUtils.setField(scheduler, "marketCloseSchedulerConfigured", false);
        ReflectionTestUtils.setField(scheduler, "settlementSchedulerConfigured", false);

        scheduler.rolloverSimulationDayIfNeeded();

        verifyNoInteractions(
                orderBookMarketSessionStateService,
                simulationMarketSessionService,
                batchJobRuntimeControl,
                stockBatchJobLauncher
        );
    }

    @Test
    void portfolioSettlementScheduler_afterSettlementEligibility_runsPostCloseWork() {
        // given
        PortfolioSettlementScheduler scheduler = newPortfolioSettlementScheduler();
        ReflectionTestUtils.setField(scheduler, "marketCloseSchedulerConfigured", true);
        ReflectionTestUtils.setField(scheduler, "settlementSchedulerConfigured", true);
        when(simulationMarketSessionService.currentSimulationDateTime())
                .thenReturn(LocalDateTime.of(2026, 7, 3, 18, 29));
        when(batchJobRuntimeControl.shouldRunScheduledJob(MarketCloseRolloverJob.JOB_NAME, true))
                .thenReturn(true);
        when(batchJobRuntimeControl.shouldRunScheduledJob(PortfolioSettlementJob.JOB_NAME, true))
                .thenReturn(true);
        when(stockBatchJobLauncher.rolloverClosingPricesScheduled())
                .thenReturn(completedResponse(MarketCloseRolloverJob.JOB_NAME));
        when(stockBatchJobLauncher.settlePortfoliosScheduled())
                .thenReturn(completedResponse(PortfolioSettlementJob.JOB_NAME));
        when(marketCloseRolloverService.hasCompletedFullCloseRun(java.time.LocalDate.of(2026, 7, 3)))
                .thenReturn(false, true);
        when(postProcessingCompletionService.isComplete(java.time.LocalDate.of(2026, 7, 3)))
                .thenReturn(false, true);

        // when
        scheduler.rolloverSimulationDayIfNeeded();

        // then
        verify(stockBatchJobLauncher).rolloverClosingPricesScheduled();
        verify(stockBatchJobLauncher).settlePortfoliosScheduled();
    }

    @Test
    void portfolioSettlementScheduler_beforeSettlementEligibility_freezesButDefersSettlement() {
        PortfolioSettlementScheduler scheduler = newPortfolioSettlementScheduler();
        ReflectionTestUtils.setField(scheduler, "marketCloseSchedulerConfigured", true);
        ReflectionTestUtils.setField(scheduler, "settlementSchedulerConfigured", true);
        when(simulationMarketSessionService.currentSimulationDateTime())
                .thenReturn(LocalDateTime.of(2026, 7, 3, 18, 9, 59));
        when(batchJobRuntimeControl.shouldRunScheduledJob(MarketCloseRolloverJob.JOB_NAME, true))
                .thenReturn(true);
        when(stockBatchJobLauncher.rolloverClosingPricesScheduled())
                .thenReturn(completedResponse(MarketCloseRolloverJob.JOB_NAME));
        when(marketCloseRolloverService.hasCompletedFullCloseRun(java.time.LocalDate.of(2026, 7, 3)))
                .thenReturn(false, true);
        when(postProcessingCompletionService.isComplete(java.time.LocalDate.of(2026, 7, 3)))
                .thenReturn(false);
        when(portfolioSettlementLifecycleService.isSettlementEligible(anyLong(), any()))
                .thenReturn(false);

        scheduler.rolloverSimulationDayIfNeeded();

        verify(stockBatchJobLauncher).rolloverClosingPricesScheduled();
        verify(stockBatchJobLauncher, never()).settlePortfoliosScheduled();
        verify(batchJobRuntimeControl, never())
                .shouldRunScheduledJob(PortfolioSettlementJob.JOB_NAME, true);
    }

    @Test
    void portfolioSettlementScheduler_completedZeroCloseWithoutCloseRunDoesNotRunSettlement() {
        PortfolioSettlementScheduler scheduler = newPortfolioSettlementScheduler();
        ReflectionTestUtils.setField(scheduler, "marketCloseSchedulerConfigured", true);
        ReflectionTestUtils.setField(scheduler, "settlementSchedulerConfigured", true);
        when(batchJobRuntimeControl.shouldRunScheduledJob(MarketCloseRolloverJob.JOB_NAME, true))
                .thenReturn(true);
        when(stockBatchJobLauncher.rolloverClosingPricesScheduled())
                .thenReturn(completedResponse(MarketCloseRolloverJob.JOB_NAME, 0));
        when(marketCloseRolloverService.hasCompletedFullCloseRun(java.time.LocalDate.of(2026, 7, 3)))
                .thenReturn(false, false);

        scheduler.rolloverSimulationDayIfNeeded();

        verify(stockBatchJobLauncher).rolloverClosingPricesScheduled();
        verify(stockBatchJobLauncher, never()).settlePortfolios();
    }

    @Test
    void portfolioSettlementScheduler_preOpenRetriesMissingPreviousCloseBeforeOpeningNextDay() {
        // given
        PortfolioSettlementScheduler scheduler = newPortfolioSettlementScheduler();
        ReflectionTestUtils.setField(scheduler, "marketCloseSchedulerConfigured", true);
        ReflectionTestUtils.setField(scheduler, "settlementSchedulerConfigured", true);
        when(simulationMarketSessionService.isAfterCloseSession()).thenReturn(false);
        when(simulationMarketSessionService.currentSession()).thenReturn(SimulationMarketSession.PRE_OPEN);
        when(simulationMarketSessionService.currentSimulationDate())
                .thenReturn(java.time.LocalDate.of(2026, 7, 4));
        when(simulationMarketSessionService.currentSimulationDateTime())
                .thenReturn(LocalDateTime.of(2026, 7, 4, 0, 30));
        when(simulationMarketSessionService.baseSimulationDate())
                .thenReturn(java.time.LocalDate.of(2026, 7, 3));
        when(batchJobRuntimeControl.shouldRunScheduledJob(MarketCloseRolloverJob.JOB_NAME, true))
                .thenReturn(true);
        when(batchJobRuntimeControl.shouldRunScheduledJob(PortfolioSettlementJob.JOB_NAME, true))
                .thenReturn(true);
        when(stockBatchJobLauncher.rolloverClosingPrices(
                java.time.LocalDate.of(2026, 7, 3),
                LocalDateTime.of(2026, 7, 3, 18, 0)
        )).thenReturn(completedResponse(MarketCloseRolloverJob.JOB_NAME));
        when(stockBatchJobLauncher.settlePortfolios(
                java.time.LocalDate.of(2026, 7, 3),
                LocalDateTime.of(2026, 7, 4, 0, 30)
        ))
                .thenReturn(completedResponse(PortfolioSettlementJob.JOB_NAME));
        when(marketCloseRolloverService.hasCompletedFullCloseRun(java.time.LocalDate.of(2026, 7, 3)))
                .thenReturn(false, true);
        when(postProcessingCompletionService.isComplete(java.time.LocalDate.of(2026, 7, 3)))
                .thenReturn(false, false, true);

        // when
        scheduler.rolloverSimulationDayIfNeeded();

        // then
        verify(stockBatchJobLauncher).rolloverClosingPrices(
                java.time.LocalDate.of(2026, 7, 3),
                LocalDateTime.of(2026, 7, 3, 18, 0)
        );
        verify(stockBatchJobLauncher).settlePortfolios(
                java.time.LocalDate.of(2026, 7, 3),
                LocalDateTime.of(2026, 7, 4, 0, 30)
        );
    }

    @Test
    void portfolioSettlementScheduler_preOpenActiveDateWithoutCycle_closesOldestActiveDate() {
        PortfolioSettlementScheduler scheduler = newPortfolioSettlementScheduler();
        ReflectionTestUtils.setField(scheduler, "marketCloseSchedulerConfigured", true);
        ReflectionTestUtils.setField(scheduler, "settlementSchedulerConfigured", true);
        java.time.LocalDate activeDate = java.time.LocalDate.of(2026, 7, 3);
        java.time.LocalDate rawDate = java.time.LocalDate.of(2026, 7, 5);
        when(simulationMarketSessionService.currentSession()).thenReturn(SimulationMarketSession.PRE_OPEN);
        when(simulationMarketSessionService.currentSimulationDate()).thenReturn(rawDate);
        when(simulationMarketSessionService.currentSimulationDateTime())
                .thenReturn(rawDate.atTime(0, 30));
        when(simulationMarketSessionService.baseSimulationDate()).thenReturn(activeDate);
        when(marketSessionFenceService.businessState()).thenReturn(
                new MarketSessionFenceService.MarketBusinessStateSnapshot(
                        activeDate,
                        activeDate.plusDays(1),
                        rawDate,
                        1L
                )
        );
        when(postCloseCycleService.findFullMarketCycle(activeDate)).thenReturn(Optional.empty());
        when(batchJobRuntimeControl.shouldRunScheduledJob(MarketCloseRolloverJob.JOB_NAME, true))
                .thenReturn(true);
        when(stockBatchJobLauncher.rolloverClosingPrices(
                activeDate,
                activeDate.atTime(18, 0)
        )).thenReturn(completedResponse(MarketCloseRolloverJob.JOB_NAME));
        when(marketCloseRolloverService.hasCompletedFullCloseRun(activeDate)).thenReturn(false, true);
        when(postProcessingCompletionService.isComplete(activeDate)).thenReturn(false);

        scheduler.rolloverSimulationDayIfNeeded();

        verify(stockBatchJobLauncher).rolloverClosingPrices(
                activeDate,
                activeDate.atTime(18, 0)
        );
        verify(stockBatchJobLauncher, never()).rolloverClosingPrices(
                rawDate.minusDays(1),
                rawDate.minusDays(1).atTime(18, 0)
        );
    }

    @Test
    void portfolioSettlementScheduler_preOpenRetriesMissingPreviousSettlementWithoutRerunningClose() {
        // given
        PortfolioSettlementScheduler scheduler = newPortfolioSettlementScheduler();
        ReflectionTestUtils.setField(scheduler, "marketCloseSchedulerConfigured", true);
        ReflectionTestUtils.setField(scheduler, "settlementSchedulerConfigured", true);
        when(simulationMarketSessionService.isAfterCloseSession()).thenReturn(false);
        when(simulationMarketSessionService.currentSession()).thenReturn(SimulationMarketSession.PRE_OPEN);
        when(simulationMarketSessionService.currentSimulationDate())
                .thenReturn(java.time.LocalDate.of(2026, 7, 4));
        when(simulationMarketSessionService.currentSimulationDateTime())
                .thenReturn(LocalDateTime.of(2026, 7, 4, 0, 30));
        when(simulationMarketSessionService.baseSimulationDate())
                .thenReturn(java.time.LocalDate.of(2026, 7, 3));
        when(batchJobRuntimeControl.shouldRunScheduledJob(PortfolioSettlementJob.JOB_NAME, true))
                .thenReturn(true);
        when(stockBatchJobLauncher.settlePortfolios(
                java.time.LocalDate.of(2026, 7, 3),
                LocalDateTime.of(2026, 7, 4, 0, 30)
        ))
                .thenReturn(completedResponse(PortfolioSettlementJob.JOB_NAME));
        when(marketCloseRolloverService.hasCompletedFullCloseRun(java.time.LocalDate.of(2026, 7, 3)))
                .thenReturn(true);
        when(postProcessingCompletionService.isComplete(java.time.LocalDate.of(2026, 7, 3)))
                .thenReturn(false, false, true);

        // when
        scheduler.rolloverSimulationDayIfNeeded();

        // then
        verify(stockBatchJobLauncher, never()).rolloverClosingPrices(
                java.time.LocalDate.of(2026, 7, 3),
                LocalDateTime.of(2026, 7, 3, 18, 0)
        );
        verify(stockBatchJobLauncher).settlePortfolios(
                java.time.LocalDate.of(2026, 7, 3),
                LocalDateTime.of(2026, 7, 4, 0, 30)
        );
    }

    @Test
    void portfolioSettlementScheduler_regularOpenSessionWithoutBacklogDoesNotRunPreviousCloseCatchUp() {
        PortfolioSettlementScheduler scheduler = newPortfolioSettlementScheduler();
        ReflectionTestUtils.setField(scheduler, "marketCloseSchedulerConfigured", true);
        ReflectionTestUtils.setField(scheduler, "settlementSchedulerConfigured", true);
        when(simulationMarketSessionService.isAfterCloseSession()).thenReturn(false);
        when(simulationMarketSessionService.currentSession()).thenReturn(SimulationMarketSession.REGULAR);
        when(simulationMarketSessionService.currentSimulationDate())
                .thenReturn(java.time.LocalDate.of(2026, 7, 4));
        when(simulationMarketSessionService.baseSimulationDate())
                .thenReturn(java.time.LocalDate.of(2026, 7, 3));
        when(marketCloseRolloverService.hasCompletedFullCloseRun(java.time.LocalDate.of(2026, 7, 3)))
                .thenReturn(false);

        scheduler.rolloverSimulationDayIfNeeded();

        verifyNoInteractions(batchJobRuntimeControl, stockBatchJobLauncher);
        verify(postCloseCycleService).findOldestUnsettledFullMarketCycle();
        verify(postProcessingCompletionService, never()).isComplete(any());
    }

    @Test
    void portfolioSettlementScheduler_regularRawTimeWithClosedMarketRecoversOldestUnsettledCycle() {
        PortfolioSettlementScheduler scheduler = newPortfolioSettlementScheduler();
        ReflectionTestUtils.setField(scheduler, "marketCloseSchedulerConfigured", true);
        ReflectionTestUtils.setField(scheduler, "settlementSchedulerConfigured", true);
        java.time.LocalDate businessDate = java.time.LocalDate.of(2026, 7, 3);
        LocalDateTime rawSimulationNow = LocalDateTime.of(2026, 7, 4, 10, 0);
        when(simulationMarketSessionService.currentSession()).thenReturn(SimulationMarketSession.REGULAR);
        when(simulationMarketSessionService.currentSimulationDate()).thenReturn(rawSimulationNow.toLocalDate());
        when(simulationMarketSessionService.currentSimulationDateTime()).thenReturn(rawSimulationNow);
        when(postCloseCycleService.findOldestUnsettledFullMarketCycle())
                .thenReturn(Optional.of(frozenCycle(businessDate)));
        when(marketSessionFenceService.hasOpenMarket()).thenReturn(false);
        when(marketCloseRolloverService.hasCompletedFullCloseRun(businessDate)).thenReturn(true);
        when(postProcessingCompletionService.isComplete(businessDate)).thenReturn(false, true);
        when(batchJobRuntimeControl.shouldRunScheduledJob(PortfolioSettlementJob.JOB_NAME, true))
                .thenReturn(true);
        when(stockBatchJobLauncher.settlePortfolios(businessDate, rawSimulationNow))
                .thenReturn(completedResponse(PortfolioSettlementJob.JOB_NAME));

        scheduler.rolloverSimulationDayIfNeeded();

        verify(stockBatchJobLauncher).settlePortfolios(businessDate, rawSimulationNow);
    }

    @Test
    void portfolioSettlementScheduler_regularRawTimeWithOpenMarketDefersOldestUnsettledCycle() {
        PortfolioSettlementScheduler scheduler = newPortfolioSettlementScheduler();
        java.time.LocalDate businessDate = java.time.LocalDate.of(2026, 7, 3);
        when(simulationMarketSessionService.currentSession()).thenReturn(SimulationMarketSession.REGULAR);
        when(simulationMarketSessionService.currentSimulationDate())
                .thenReturn(java.time.LocalDate.of(2026, 7, 4));
        when(postCloseCycleService.findOldestUnsettledFullMarketCycle())
                .thenReturn(Optional.of(frozenCycle(businessDate)));
        when(marketSessionFenceService.hasOpenMarket()).thenReturn(true);

        scheduler.rolloverSimulationDayIfNeeded();

        verifyNoInteractions(batchJobRuntimeControl, stockBatchJobLauncher);
    }

    @Test
    void portfolioSettlementScheduler_regularRawTimeWithClosedMarketRecoversMissingActiveDateCycle() {
        PortfolioSettlementScheduler scheduler = newPortfolioSettlementScheduler();
        ReflectionTestUtils.setField(scheduler, "marketCloseSchedulerConfigured", true);
        ReflectionTestUtils.setField(scheduler, "settlementSchedulerConfigured", false);
        java.time.LocalDate activeDate = java.time.LocalDate.of(2026, 7, 3);
        java.time.LocalDate rawDate = java.time.LocalDate.of(2026, 7, 5);
        when(simulationMarketSessionService.currentSession()).thenReturn(SimulationMarketSession.REGULAR);
        when(simulationMarketSessionService.currentSimulationDate()).thenReturn(rawDate);
        when(simulationMarketSessionService.currentSimulationDateTime()).thenReturn(rawDate.atTime(10, 0));
        when(marketSessionFenceService.businessState()).thenReturn(
                new MarketSessionFenceService.MarketBusinessStateSnapshot(
                        activeDate,
                        activeDate.plusDays(1),
                        rawDate,
                        1L
                )
        );
        when(postCloseCycleService.findFullMarketCycle(activeDate)).thenReturn(Optional.empty());
        when(batchJobRuntimeControl.shouldRunScheduledJob(MarketCloseRolloverJob.JOB_NAME, true))
                .thenReturn(true);
        when(stockBatchJobLauncher.rolloverClosingPrices(
                activeDate,
                activeDate.atTime(18, 0)
        )).thenReturn(completedResponse(MarketCloseRolloverJob.JOB_NAME));
        when(marketCloseRolloverService.hasCompletedFullCloseRun(activeDate)).thenReturn(false, true);

        scheduler.rolloverSimulationDayIfNeeded();

        verify(stockBatchJobLauncher).rolloverClosingPrices(
                activeDate,
                activeDate.atTime(18, 0)
        );
    }

    @Test
    void scheduledJobGuard_runtimeControlThrows_skipsJobWithoutPropagatingException() {
        when(batchJobRuntimeControl.shouldRunScheduledJob(AutoMarketJob.JOB_NAME, true))
                .thenThrow(new IllegalStateException("runtime control db timeout"));

        scheduledJobGuard.runIfEnabled(AutoMarketJob.JOB_NAME, true, stockBatchJobLauncher::runAutoMarket);

        verify(batchJobRuntimeControl).shouldRunScheduledJob(AutoMarketJob.JOB_NAME, true);
        verifyNoInteractions(stockBatchJobLauncher);
    }

    @Test
    void scheduledJobGuard_launcherThrows_doesNotPropagateExceptionToSchedulerThread() {
        when(batchJobRuntimeControl.shouldRunScheduledJob(AutoMarketJob.JOB_NAME, true)).thenReturn(true);
        when(stockBatchJobLauncher.runAutoMarket()).thenThrow(new IllegalStateException("runner escaped failure"));

        scheduledJobGuard.runIfEnabled(AutoMarketJob.JOB_NAME, true, stockBatchJobLauncher::runAutoMarket);

        verify(batchJobRuntimeControl).shouldRunScheduledJob(AutoMarketJob.JOB_NAME, true);
        verify(stockBatchJobLauncher).runAutoMarket();
    }

    @Test
    void scheduledJobGuard_contextClosed_skipsBeforeRuntimeControlLookup() {
        scheduledJobGuard.prepareShutdown();

        scheduledJobGuard.runIfEnabled(AutoMarketJob.JOB_NAME, true, stockBatchJobLauncher::runAutoMarket);

        verifyNoInteractions(batchJobRuntimeControl, stockBatchJobLauncher);
    }

    private void assertSimpleSchedulerGate(String jobName, Runnable scheduledAction, Runnable verifyLaunch) {
        when(batchJobRuntimeControl.shouldRunScheduledJob(jobName, true)).thenReturn(false);

        scheduledAction.run();

        verify(batchJobRuntimeControl).shouldRunScheduledJob(jobName, true);
        verifyNoInteractions(stockBatchJobLauncher);

        reset(stockBatchJobLauncher, batchJobRuntimeControl);
        when(batchJobRuntimeControl.shouldRunScheduledJob(jobName, true)).thenReturn(true);

        scheduledAction.run();

        verify(batchJobRuntimeControl).shouldRunScheduledJob(jobName, true);
        verifyLaunch.run();
    }

    private StockBatchJobRunResponse completedResponse(String jobName) {
        return completedResponse(jobName, 1);
    }

    private StockBatchJobRunResponse completedResponse(String jobName, int processedCount) {
        LocalDateTime now = LocalDateTime.now();
        return new StockBatchJobRunResponse(jobName, "COMPLETED", "test", processedCount, "completed", now, now);
    }

    private PortfolioSettlementScheduler newPortfolioSettlementScheduler() {
        return new PortfolioSettlementScheduler(
                stockBatchJobLauncher,
                scheduledJobGuard,
                simulationMarketSessionService,
                orderBookMarketSessionStateService,
                marketCloseRolloverService,
                postProcessingCompletionService,
                postCloseCycleService,
                portfolioSettlementLifecycleService,
                marketSessionFenceService
        );
    }

    private PostCloseCycle frozenCycle(java.time.LocalDate businessDate) {
        return new PostCloseCycle(
                1L,
                businessDate,
                PostCloseScopeType.FULL_MARKET,
                "ALL",
                stock.batch.service.marketclose.model.PostCloseCycleKind.TRADING,
                null,
                PostClosePhase.LEDGER_FROZEN,
                PostCloseCycleStatus.PENDING,
                1,
                1L,
                1L,
                businessDate.atTime(18, 10),
                0,
                null,
                null,
                null
        );
    }

    private AutoMarketScheduler newAutoMarketScheduler(Executor autoMarketRunTaskExecutor) {
        return new AutoMarketScheduler(
                stockBatchJobLauncher,
                scheduledJobGuard,
                simulationMarketSessionService,
                marketSessionFenceService,
                autoMarketDailyRegimePreCreateService,
                autoMarketProfileQueueReconcileService,
                autoMarketRunTaskExecutor
        );
    }

    private OrderBookExecutionScheduler newOrderBookExecutionScheduler() {
        return new OrderBookExecutionScheduler(
                stockBatchJobLauncher,
                scheduledJobGuard,
                simulationMarketSessionService,
                marketSessionFenceService
        );
    }

    private static final class RecordingExecutor implements Executor {

        private final int maxPendingTasks;
        private final Queue<Runnable> pendingTasks = new ArrayDeque<>();

        private RecordingExecutor(int maxPendingTasks) {
            this.maxPendingTasks = maxPendingTasks;
        }

        @Override
        public void execute(Runnable command) {
            if (pendingTasks.size() >= maxPendingTasks) {
                throw new RejectedExecutionException("saturated");
            }
            pendingTasks.add(command);
        }

        private void runAll() {
            while (!pendingTasks.isEmpty()) {
                pendingTasks.remove().run();
            }
        }
    }
}
