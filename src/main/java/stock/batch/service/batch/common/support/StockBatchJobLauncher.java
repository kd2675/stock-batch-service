package stock.batch.service.batch.common.support;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import stock.batch.service.automarket.biz.AutoParticipantCashFlowRuntimeControl;
import stock.batch.service.batch.automarket.job.AutoMarketDailyRegimePreCreateJob;
import stock.batch.service.batch.automarket.job.AutoMarketJob;
import stock.batch.service.batch.automarket.job.AutoMarketOrderExpiryJob;
import stock.batch.service.batch.automarket.job.AutoMarketProfileQueueReconcileJob;
import stock.batch.service.batch.automarket.job.AutoParticipantCashFlowJob;
import stock.batch.service.batch.automarket.job.ListingAutoMarketJob;
import stock.batch.service.batch.corporateaction.job.CorporateActionJob;
import stock.batch.service.batch.execution.job.OrderBookExecutionJob;
import stock.batch.service.batch.holdingcleanup.job.HoldingCleanupJob;
import stock.batch.service.batch.marketclose.job.MarketCloseRolloverJob;
import stock.batch.service.batch.marketdata.job.MarketDataRefreshJob;
import stock.batch.service.batch.settlement.job.PortfolioSettlementJob;
import stock.batch.service.common.vo.StockBatchJobRunResponse;
import stock.batch.service.simulation.SimulationClockService;
import stock.batch.service.simulation.SimulationMarketSessionService;

@Service
public class StockBatchJobLauncher {

    private static final String TRIGGER_INTERNAL_API = "internal-api";
    private static final String TRIGGER_SCHEDULER = "scheduler";
    private static final String TRIGGER_SIGNAL = "db-signal";
    private static final String MODE_RECURRING_CASH = "recurring-cash";
    private static final String MODE_MANUAL_RECURRING_CASH = "manual-recurring-cash";
    private static final String MODE_DAILY_REGIME = "daily-regime";
    private static final String MODE_CORPORATE_ACTION = "order-book";
    private static final String MODE_PORTFOLIO_SETTLEMENT = "portfolio-snapshot";
    private static final String MODE_MARKET_CLOSE_FULL = "price-limit-base:full";
    private static final String MODE_MARKET_CLOSE_SYMBOL = "price-limit-base:symbol";
    private static final String MODE_CANCEL_OPEN_ORDERS = "halt-open-order-cancel";

    private final StockBatchJobRunner stockBatchJobRunner;
    private final AutoParticipantCashFlowRuntimeControl autoParticipantCashFlowRuntimeControl;
    private final SimulationClockService simulationClockService;
    private final SimulationMarketSessionService simulationMarketSessionService;
    private final MarketDataRefreshJob marketDataRefreshTask;
    private final OrderBookExecutionJob orderBookExecutionTask;
    private final AutoMarketProfileQueueReconcileJob autoMarketProfileQueueReconcileTask;
    private final AutoMarketJob autoMarketTask;
    private final AutoMarketOrderExpiryJob autoMarketOrderExpiryTask;
    private final ListingAutoMarketJob listingAutoMarketTask;
    private final HoldingCleanupJob holdingCleanupTask;
    private final Job autoParticipantCashFlowJob;
    private final Job autoMarketDailyRegimePreCreateJob;
    private final Job portfolioSettlementJob;
    private final Job marketCloseRolloverJob;
    private final Job corporateActionJob;

    public StockBatchJobLauncher(
            StockBatchJobRunner stockBatchJobRunner,
            AutoParticipantCashFlowRuntimeControl autoParticipantCashFlowRuntimeControl,
            SimulationClockService simulationClockService,
            SimulationMarketSessionService simulationMarketSessionService,
            MarketDataRefreshJob marketDataRefreshTask,
            OrderBookExecutionJob orderBookExecutionTask,
            AutoMarketProfileQueueReconcileJob autoMarketProfileQueueReconcileTask,
            AutoMarketJob autoMarketTask,
            AutoMarketOrderExpiryJob autoMarketOrderExpiryTask,
            ListingAutoMarketJob listingAutoMarketTask,
            HoldingCleanupJob holdingCleanupTask,
            @Qualifier(AutoParticipantCashFlowJob.JOB_NAME) Job autoParticipantCashFlowJob,
            @Qualifier(AutoMarketDailyRegimePreCreateJob.JOB_NAME) Job autoMarketDailyRegimePreCreateJob,
            @Qualifier(PortfolioSettlementJob.JOB_NAME) Job portfolioSettlementJob,
            @Qualifier(MarketCloseRolloverJob.JOB_NAME) Job marketCloseRolloverJob,
            @Qualifier(CorporateActionJob.JOB_NAME) Job corporateActionJob
    ) {
        this.stockBatchJobRunner = stockBatchJobRunner;
        this.autoParticipantCashFlowRuntimeControl = autoParticipantCashFlowRuntimeControl;
        this.simulationClockService = simulationClockService;
        this.simulationMarketSessionService = simulationMarketSessionService;
        this.marketDataRefreshTask = marketDataRefreshTask;
        this.orderBookExecutionTask = orderBookExecutionTask;
        this.autoMarketProfileQueueReconcileTask = autoMarketProfileQueueReconcileTask;
        this.autoMarketTask = autoMarketTask;
        this.autoMarketOrderExpiryTask = autoMarketOrderExpiryTask;
        this.listingAutoMarketTask = listingAutoMarketTask;
        this.holdingCleanupTask = holdingCleanupTask;
        this.autoParticipantCashFlowJob = autoParticipantCashFlowJob;
        this.autoMarketDailyRegimePreCreateJob = autoMarketDailyRegimePreCreateJob;
        this.portfolioSettlementJob = portfolioSettlementJob;
        this.marketCloseRolloverJob = marketCloseRolloverJob;
        this.corporateActionJob = corporateActionJob;
    }

    public StockBatchJobRunResponse refreshMarketData() {
        return stockBatchJobRunner.run(marketDataRefreshTask);
    }

    public StockBatchJobRunResponse executeOrderBookOrders() {
        return stockBatchJobRunner.run(orderBookExecutionTask);
    }

    public StockBatchJobRunResponse fundAutoParticipants() {
        LocalDateTime now = simulationClockService.currentMarketDateTime();
        JobParametersBuilder parameters = baseParameters(
                MODE_RECURRING_CASH,
                TRIGGER_SCHEDULER,
                scheduledRequestId(AutoParticipantCashFlowJob.JOB_NAME, now),
                now
        )
                .addString(StockBatchJobParameters.OPERATION, AutoParticipantCashFlowJob.OPERATION_SCHEDULED, true)
                .addLocalDateTime(StockBatchJobParameters.SWEEP_AT, minuteBucket(now), true);
        return stockBatchJobRunner.run(autoParticipantCashFlowJob, MODE_RECURRING_CASH, parameters.toJobParameters());
    }

    public StockBatchJobRunResponse fundAutoParticipantsManually() {
        return fundAutoParticipantsManually(null);
    }

    public StockBatchJobRunResponse fundAutoParticipantsManually(Long signalId) {
        if (!autoParticipantCashFlowRuntimeControl.canRunManualCashFlow()) {
            return StockBatchJobRunResponses.manualCashFlowAutoEnabled(LocalDateTime.now());
        }
        if (!simulationMarketSessionService.isAfterCloseSession()) {
            return StockBatchJobRunResponses.manualCashFlowBeforeMarketClose(LocalDateTime.now());
        }
        LocalDateTime now = simulationClockService.currentMarketDateTime();
        String triggeredBy = signalId == null ? TRIGGER_INTERNAL_API : TRIGGER_SIGNAL;
        String requestId = signalId == null
                ? directRequestId(AutoParticipantCashFlowJob.JOB_NAME, MODE_MANUAL_RECURRING_CASH)
                : signalRequestId(signalId);
        JobParametersBuilder parameters = baseParameters(
                MODE_MANUAL_RECURRING_CASH,
                triggeredBy,
                requestId,
                now
        ).addString(StockBatchJobParameters.OPERATION, AutoParticipantCashFlowJob.OPERATION_MANUAL, true);
        addSignalId(parameters, signalId);
        return stockBatchJobRunner.run(
                autoParticipantCashFlowJob,
                MODE_MANUAL_RECURRING_CASH,
                parameters.toJobParameters()
        );
    }

    public StockBatchJobRunResponse preCreateAutoMarketDailyRegimes() {
        LocalDateTime now = simulationClockService.currentMarketDateTime();
        JobParametersBuilder parameters = baseParameters(
                MODE_DAILY_REGIME,
                TRIGGER_SCHEDULER,
                scheduledRequestId(AutoMarketDailyRegimePreCreateJob.JOB_NAME, now),
                now
        );
        return stockBatchJobRunner.run(
                autoMarketDailyRegimePreCreateJob,
                MODE_DAILY_REGIME,
                parameters.toJobParameters()
        );
    }

    public StockBatchJobRunResponse reconcileAutoMarketProfileQueue() {
        return stockBatchJobRunner.run(autoMarketProfileQueueReconcileTask);
    }

    public StockBatchJobRunResponse runAutoMarket() {
        return stockBatchJobRunner.run(autoMarketTask);
    }

    public StockBatchJobRunResponse expireAutoMarketOrders() {
        return stockBatchJobRunner.run(autoMarketOrderExpiryTask);
    }

    public StockBatchJobRunResponse runListingAutoMarket() {
        return stockBatchJobRunner.run(listingAutoMarketTask);
    }

    public StockBatchJobRunResponse settlePortfolios() {
        return settlePortfolios(
                simulationClockService.currentDate(),
                simulationClockService.currentDate().atTime(simulationMarketSessionService.closeTime()),
                true,
                TRIGGER_INTERNAL_API
        );
    }

    public StockBatchJobRunResponse settlePortfoliosScheduled() {
        return settlePortfolios(
                simulationClockService.currentDate(),
                simulationClockService.currentDate().atTime(simulationMarketSessionService.closeTime()),
                true,
                TRIGGER_SCHEDULER
        );
    }

    public StockBatchJobRunResponse settlePortfoliosForce(long runVersion) {
        if (runVersion <= 0) {
            throw new IllegalArgumentException("runVersion must be positive");
        }
        LocalDate businessDate = simulationClockService.currentDate();
        return settlePortfolios(
                businessDate,
                businessDate.atTime(simulationMarketSessionService.closeTime()),
                true,
                TRIGGER_INTERNAL_API,
                runVersion
        );
    }

    public StockBatchJobRunResponse settlePortfolios(LocalDate snapshotDate, LocalDateTime snapshotAt) {
        return settlePortfolios(snapshotDate, snapshotAt, false, TRIGGER_SCHEDULER);
    }

    public StockBatchJobRunResponse rolloverClosingPrices() {
        LocalDate businessDate = simulationClockService.currentDate();
        return rolloverClosingPrices(
                businessDate,
                businessDate.atTime(simulationMarketSessionService.closeTime()),
                TRIGGER_INTERNAL_API,
                null
        );
    }

    public StockBatchJobRunResponse rolloverClosingPricesScheduled() {
        LocalDate businessDate = simulationClockService.currentDate();
        return rolloverClosingPrices(
                businessDate,
                businessDate.atTime(simulationMarketSessionService.closeTime()),
                TRIGGER_SCHEDULER,
                null
        );
    }

    public StockBatchJobRunResponse rolloverClosingPrices(Long signalId) {
        LocalDate businessDate = simulationClockService.currentDate();
        return rolloverClosingPrices(
                businessDate,
                businessDate.atTime(simulationMarketSessionService.closeTime()),
                TRIGGER_SIGNAL,
                signalId
        );
    }

    public StockBatchJobRunResponse rolloverClosingPrices(String symbol) {
        return rolloverClosingPrices(symbol, TRIGGER_INTERNAL_API, null);
    }

    public StockBatchJobRunResponse rolloverClosingPrices(String symbol, Long signalId) {
        return rolloverClosingPrices(symbol, TRIGGER_SIGNAL, signalId);
    }

    public StockBatchJobRunResponse rolloverClosingPrices(LocalDate businessDate, LocalDateTime closedAt) {
        return rolloverClosingPrices(businessDate, closedAt, TRIGGER_SCHEDULER, null);
    }

    public StockBatchJobRunResponse cancelOpenOrderBookOrders(String symbol) {
        return cancelOpenOrderBookOrders(symbol, null);
    }

    public StockBatchJobRunResponse cancelOpenOrderBookOrders(String symbol, Long signalId) {
        LocalDateTime now = simulationClockService.currentMarketDateTime();
        String normalizedSymbol = normalizeSymbol(symbol);
        String triggeredBy = signalId == null ? TRIGGER_INTERNAL_API : TRIGGER_SIGNAL;
        JobParametersBuilder parameters = baseParameters(
                MODE_CANCEL_OPEN_ORDERS,
                triggeredBy,
                signalId == null
                        ? directRequestId(MarketCloseRolloverJob.JOB_NAME, MODE_CANCEL_OPEN_ORDERS + ":" + normalizedSymbol)
                        : signalRequestId(signalId),
                now
        )
                .addString(StockBatchJobParameters.OPERATION, MarketCloseRolloverJob.OPERATION_CANCEL_OPEN_ORDERS, true)
                .addString(StockBatchJobParameters.SYMBOL, normalizedSymbol, true)
                .addLocalDateTime(StockBatchJobParameters.CLOSED_AT, now, true);
        addSignalId(parameters, signalId);
        return stockBatchJobRunner.run(marketCloseRolloverJob, MODE_CANCEL_OPEN_ORDERS, parameters.toJobParameters());
    }

    public StockBatchJobRunResponse applyCorporateActions() {
        return applyCorporateActions(TRIGGER_INTERNAL_API);
    }

    public StockBatchJobRunResponse applyCorporateActionsScheduled() {
        return applyCorporateActions(TRIGGER_SCHEDULER);
    }

    public StockBatchJobRunResponse cleanupEmptyHoldings() {
        return stockBatchJobRunner.run(holdingCleanupTask);
    }

    private StockBatchJobRunResponse settlePortfolios(
            LocalDate snapshotDate,
            LocalDateTime snapshotAt,
            boolean enforceClose,
            String triggeredBy
    ) {
        return settlePortfolios(snapshotDate, snapshotAt, enforceClose, triggeredBy, null);
    }

    private StockBatchJobRunResponse settlePortfolios(
            LocalDate snapshotDate,
            LocalDateTime snapshotAt,
            boolean enforceClose,
            String triggeredBy,
            Long runVersion
    ) {
        JobParametersBuilder parameters = StockBatchJobParameters.base(
                snapshotDate,
                MODE_PORTFOLIO_SETTLEMENT,
                triggeredBy,
                requestId(triggeredBy, PortfolioSettlementJob.JOB_NAME, snapshotDate.toString()),
                LocalDateTime.now()
        )
                .addLocalDateTime(StockBatchJobParameters.SNAPSHOT_AT, snapshotAt, true)
                .addString(StockBatchJobParameters.ENFORCE_CLOSE, Boolean.toString(enforceClose), true);
        if (runVersion != null) {
            parameters.addLong(StockBatchJobParameters.RUN_VERSION, runVersion, true);
        }
        return stockBatchJobRunner.run(
                portfolioSettlementJob,
                MODE_PORTFOLIO_SETTLEMENT,
                parameters.toJobParameters()
        );
    }

    private StockBatchJobRunResponse rolloverClosingPrices(
            LocalDate businessDate,
            LocalDateTime closedAt,
            String triggeredBy,
            Long signalId
    ) {
        JobParametersBuilder parameters = StockBatchJobParameters.base(
                businessDate,
                MODE_MARKET_CLOSE_FULL,
                triggeredBy,
                signalId == null
                        ? requestId(triggeredBy, MarketCloseRolloverJob.JOB_NAME, businessDate.toString())
                        : signalRequestId(signalId),
                LocalDateTime.now()
        )
                .addString(StockBatchJobParameters.OPERATION, MarketCloseRolloverJob.OPERATION_FULL, true)
                .addLocalDateTime(StockBatchJobParameters.CLOSED_AT, closedAt, true);
        addSignalId(parameters, signalId);
        return stockBatchJobRunner.run(marketCloseRolloverJob, MODE_MARKET_CLOSE_FULL, parameters.toJobParameters());
    }

    private StockBatchJobRunResponse rolloverClosingPrices(String symbol, String triggeredBy, Long signalId) {
        LocalDate businessDate = simulationClockService.currentDate();
        LocalDateTime now = simulationClockService.currentMarketDateTime();
        String normalizedSymbol = normalizeSymbol(symbol);
        JobParametersBuilder parameters = StockBatchJobParameters.base(
                businessDate,
                MODE_MARKET_CLOSE_SYMBOL,
                triggeredBy,
                signalId == null
                        ? requestId(triggeredBy, MarketCloseRolloverJob.JOB_NAME, normalizedSymbol)
                        : signalRequestId(signalId),
                LocalDateTime.now()
        )
                .addString(StockBatchJobParameters.OPERATION, MarketCloseRolloverJob.OPERATION_SYMBOL, true)
                .addString(StockBatchJobParameters.SYMBOL, normalizedSymbol, true)
                .addLocalDateTime(StockBatchJobParameters.CLOSED_AT, now, true);
        addSignalId(parameters, signalId);
        return stockBatchJobRunner.run(marketCloseRolloverJob, MODE_MARKET_CLOSE_SYMBOL, parameters.toJobParameters());
    }

    private StockBatchJobRunResponse applyCorporateActions(String triggeredBy) {
        LocalDateTime now = simulationClockService.currentMarketDateTime();
        JobParametersBuilder parameters = baseParameters(
                MODE_CORPORATE_ACTION,
                triggeredBy,
                requestId(triggeredBy, CorporateActionJob.JOB_NAME, minuteBucket(now).toString()),
                now
        )
                .addString(
                        StockBatchJobParameters.SESSION,
                        simulationMarketSessionService.currentSession().name(),
                        true
                )
                .addLocalDateTime(StockBatchJobParameters.SWEEP_AT, minuteBucket(now), true);
        return stockBatchJobRunner.run(corporateActionJob, MODE_CORPORATE_ACTION, parameters.toJobParameters());
    }

    private JobParametersBuilder baseParameters(
            String mode,
            String triggeredBy,
            String requestId,
            LocalDateTime simulationNow
    ) {
        return StockBatchJobParameters.base(
                simulationNow.toLocalDate(),
                mode,
                triggeredBy,
                requestId,
                LocalDateTime.now()
        );
    }

    private void addSignalId(JobParametersBuilder parameters, Long signalId) {
        if (signalId != null) {
            parameters.addLong(StockBatchJobParameters.SIGNAL_ID, signalId, true);
        }
    }

    private LocalDateTime minuteBucket(LocalDateTime value) {
        return value.withSecond(0).withNano(0);
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        return symbol.trim().toUpperCase();
    }

    private String scheduledRequestId(String jobName, LocalDateTime now) {
        return requestId(TRIGGER_SCHEDULER, jobName, minuteBucket(now).toString());
    }

    private String directRequestId(String jobName, String scope) {
        return requestId(TRIGGER_INTERNAL_API, jobName, simulationClockService.currentDate() + ":" + scope);
    }

    private String signalRequestId(long signalId) {
        return TRIGGER_SIGNAL + ":" + signalId;
    }

    private String requestId(String trigger, String jobName, String scope) {
        return trigger + ":" + jobName + ":" + scope;
    }
}
