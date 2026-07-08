package stock.batch.service.scheduler;

import java.time.LocalDate;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.batch.common.support.StockBatchJobRunResponses;
import stock.batch.service.batch.marketclose.job.MarketCloseRolloverJob;
import stock.batch.service.batch.settlement.job.PortfolioSettlementJob;
import stock.batch.service.common.vo.StockBatchJobRunResponse;
import stock.batch.service.marketclose.biz.MarketClosePostProcessingCompletionService;
import stock.batch.service.marketclose.biz.MarketCloseRolloverService;
import stock.batch.service.marketclose.biz.OrderBookMarketSessionStateService;
import stock.batch.service.simulation.SimulationMarketSessionService;

@Component
@RequiredArgsConstructor
public class PortfolioSettlementScheduler {

    private final StockBatchJobLauncher stockBatchJobLauncher;
    private final StockBatchScheduledJobGuard scheduledJobGuard;
    private final SimulationMarketSessionService simulationMarketSessionService;
    private final OrderBookMarketSessionStateService orderBookMarketSessionStateService;
    private final MarketCloseRolloverService marketCloseRolloverService;
    private final MarketClosePostProcessingCompletionService postProcessingCompletionService;

    @Value("${stock.batch.market-close.enabled:true}")
    private boolean marketCloseSchedulerConfigured;

    @Value("${stock.batch.settlement.enabled:true}")
    private boolean settlementSchedulerConfigured;

    private LocalDate lastPostCloseProcessedDate;

    @Scheduled(
            scheduler = StockBatchSchedulerNames.MAINTENANCE,
            fixedDelayString = "${stock.batch.market-close.poll-fixed-delay-ms:5000}"
    )
    public void rolloverSimulationDayIfNeeded() {
        orderBookMarketSessionStateService.syncCurrentSession();
        LocalDate closeDate = closeDateNeedingPostCloseWork();
        if (closeDate == null) {
            return;
        }
        if (closeDate.equals(lastPostCloseProcessedDate)) {
            return;
        }
        if (settlePortfolios(closeDate) && postProcessingCompletionService.isComplete(closeDate)) {
            lastPostCloseProcessedDate = closeDate;
        }
    }

    void rolloverClosingPrices() {
        runMarketCloseRollover(simulationMarketSessionService.currentSimulationDate());
    }

    boolean settlePortfolios() {
        return settlePortfolios(simulationMarketSessionService.currentSimulationDate());
    }

    /**
     * Runs the complete post-close pipeline for one business date. The return value is true only
     * when the database confirms every required post-close item, not merely when a job exits
     * without throwing.
     */
    boolean settlePortfolios(LocalDate businessDate) {
        if (!ensureMarketCloseCompleted(businessDate)) {
            return false;
        }
        if (postProcessingCompletionService.isComplete(businessDate)) {
            return true;
        }
        if (!runPortfolioSettlement(businessDate)) {
            return false;
        }
        return postProcessingCompletionService.isComplete(businessDate);
    }

    /**
     * Current-date settlement uses the normal job path. Previous-date catch-up passes an explicit
     * snapshot date so PRE_OPEN recovery cannot write snapshots under the new simulation date.
     */
    private boolean runPortfolioSettlement(LocalDate businessDate) {
        if (!settlementSchedulerConfigured) {
            return true;
        }
        StockBatchJobRunResponse response;
        if (businessDate.equals(simulationMarketSessionService.currentSimulationDate())) {
            response = scheduledJobGuard.runBatchIfEnabled(
                    PortfolioSettlementJob.JOB_NAME,
                    settlementSchedulerConfigured,
                    stockBatchJobLauncher::settlePortfolios
            );
        } else {
            response = scheduledJobGuard.runBatchIfEnabled(
                    PortfolioSettlementJob.JOB_NAME,
                    settlementSchedulerConfigured,
                    () -> stockBatchJobLauncher.settlePortfolios(
                            businessDate,
                            businessDate.atTime(simulationMarketSessionService.closeTime())
                    )
            );
        }
        return isNotFailed(response);
    }

    /**
     * Market-close rollover owns open-order cancellation, holding snapshots, daily symbol
     * snapshots, and previous-close rollover. Settlement is intentionally separate and checked
     * after this method.
     */
    private boolean ensureMarketCloseCompleted(LocalDate businessDate) {
        if (marketCloseRolloverService.hasCompletedFullCloseRun(businessDate)) {
            return true;
        }
        if (!runMarketCloseRollover(businessDate)) {
            return false;
        }
        return marketCloseRolloverService.hasCompletedFullCloseRun(businessDate);
    }

    private boolean runMarketCloseRollover(LocalDate businessDate) {
        if (!marketCloseSchedulerConfigured) {
            return false;
        }
        StockBatchJobRunResponse response;
        if (businessDate.equals(simulationMarketSessionService.currentSimulationDate())) {
            response = scheduledJobGuard.runBatchIfEnabled(
                    MarketCloseRolloverJob.JOB_NAME,
                    marketCloseSchedulerConfigured,
                    stockBatchJobLauncher::rolloverClosingPrices
            );
        } else {
            response = scheduledJobGuard.runBatchIfEnabled(
                    MarketCloseRolloverJob.JOB_NAME,
                    marketCloseSchedulerConfigured,
                    () -> stockBatchJobLauncher.rolloverClosingPrices(
                            businessDate,
                            businessDate.atTime(simulationMarketSessionService.closeTime())
                    )
            );
        }
        return isNotFailed(response);
    }

    private LocalDate closeDateNeedingPostCloseWork() {
        LocalDate currentDate = simulationMarketSessionService.currentSimulationDate();
        return switch (simulationMarketSessionService.currentSession()) {
            case AFTER_CLOSE -> currentDate;
            case PRE_OPEN -> closeDateNeedingPreOpenCatchUp(currentDate);
            case REGULAR -> null;
        };
    }

    /**
     * PRE_OPEN is a recovery window: if the previous day was not fully post-processed because the
     * batch server stopped, retry that date before allowing the next regular session to open.
     */
    private LocalDate closeDateNeedingPreOpenCatchUp(LocalDate currentDate) {
        LocalDate previousDate = currentDate.minusDays(1);
        if (previousDate.isBefore(simulationMarketSessionService.baseSimulationDate())) {
            return null;
        }
        return postProcessingCompletionService.isComplete(previousDate) ? null : previousDate;
    }

    private boolean isNotFailed(StockBatchJobRunResponse response) {
        return !StockBatchJobRunResponses.isFailed(response);
    }
}
