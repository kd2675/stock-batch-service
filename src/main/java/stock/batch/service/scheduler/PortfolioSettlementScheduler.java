package stock.batch.service.scheduler;

import java.time.LocalDate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.batch.common.support.StockBatchJobRunResponses;
import stock.batch.service.batch.marketclose.job.MarketCloseRolloverJob;
import stock.batch.service.batch.settlement.job.PortfolioSettlementJob;
import stock.batch.service.batch.settlement.biz.PortfolioSettlementLifecycleService;
import stock.batch.service.common.vo.StockBatchJobRunResponse;
import stock.batch.service.marketclose.biz.MarketClosePostProcessingCompletionService;
import stock.batch.service.marketclose.biz.MarketCloseRolloverService;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;
import stock.batch.service.marketclose.biz.OrderBookMarketSessionStateService;
import stock.batch.service.marketclose.biz.PostCloseCycleService;
import stock.batch.service.marketclose.model.PostCloseCycle;
import stock.batch.service.simulation.SimulationMarketSessionService;
import web.common.core.simulation.SimulationMarketSession;

@Component
@RequiredArgsConstructor
@Slf4j
public class PortfolioSettlementScheduler {

    private final StockBatchJobLauncher stockBatchJobLauncher;
    private final StockBatchScheduledJobGuard scheduledJobGuard;
    private final SimulationMarketSessionService simulationMarketSessionService;
    private final OrderBookMarketSessionStateService orderBookMarketSessionStateService;
    private final MarketCloseRolloverService marketCloseRolloverService;
    private final MarketClosePostProcessingCompletionService postProcessingCompletionService;
    private final PostCloseCycleService postCloseCycleService;
    private final PortfolioSettlementLifecycleService portfolioSettlementLifecycleService;
    private final MarketSessionFenceService marketSessionFenceService;

    @Value("${stock.batch.market-close.enabled:true}")
    private boolean marketCloseSchedulerConfigured;

    @Value("${stock.batch.settlement.enabled:true}")
    private boolean settlementSchedulerConfigured;

    private LocalDate lastPostCloseProcessedDate;

    @Scheduled(
            scheduler = StockBatchSchedulerNames.POST_CLOSE,
            fixedDelayString = "${stock.batch.market-close.poll-fixed-delay-ms:10000}"
    )
    public void rolloverSimulationDayIfNeeded() {
        if (!marketCloseSchedulerConfigured && !settlementSchedulerConfigured) {
            return;
        }
        orderBookMarketSessionStateService.syncCurrentSession();
        LocalDate closeDate = closeDateNeedingPostCloseWork();
        if (closeDate == null) {
            return;
        }
        if (closeDate.equals(lastPostCloseProcessedDate)) {
            return;
        }
        log.info(
                "Stock batch post-close pipeline started: businessDate={}, session={}",
                closeDate,
                simulationMarketSessionService.currentSession()
        );
        if (settlePortfolios(closeDate) && postProcessingCompletionService.isComplete(closeDate)) {
            lastPostCloseProcessedDate = closeDate;
            log.info("Stock batch post-close pipeline completed: businessDate={}", closeDate);
        } else {
            log.info("Stock batch post-close pipeline pending: businessDate={}", closeDate);
        }
    }

    void rolloverClosingPrices() {
        runMarketCloseRollover(simulationMarketSessionService.currentSimulationDate());
    }

    boolean settlePortfolios() {
        return settlePortfolios(simulationMarketSessionService.currentSimulationDate());
    }

    /**
     * Runs the latency-sensitive freeze and portfolio-settlement prefix for one business date.
     * Overnight and pre-open phases are owned by the coordinator. The return value is true only
     * when the database confirms the frozen settlement cohort, not merely when a job exits.
     */
    boolean settlePortfolios(LocalDate businessDate) {
        if (marketSessionFenceService.hasOpenMarket()) {
            log.info(
                    "Stock batch post-close pipeline deferred to protect regular order traffic: businessDate={}",
                    businessDate
            );
            return false;
        }
        if (!ensureMarketCloseCompleted(businessDate)) {
            log.info("Stock batch post-close pipeline waiting for market close rollover: businessDate={}", businessDate);
            return false;
        }
        if (postProcessingCompletionService.isComplete(businessDate)) {
            log.info("Stock batch post-close pipeline already complete: businessDate={}", businessDate);
            return true;
        }
        if (!runPortfolioSettlement(businessDate)) {
            log.info("Stock batch post-close pipeline waiting for portfolio settlement: businessDate={}", businessDate);
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
            log.info("Stock batch portfolio settlement skipped because scheduler is disabled: businessDate={}", businessDate);
            return true;
        }
        PostCloseCycle cycle = postCloseCycleService.findFullMarketCycle(businessDate).orElse(null);
        if (cycle == null || !portfolioSettlementLifecycleService.isSettlementEligible(
                cycle.id(),
                simulationMarketSessionService.currentSimulationDateTime()
        )) {
            log.info(
                    "Stock batch portfolio settlement deferred until frozen-cycle eligibility: businessDate={}, eligibleAt={}",
                    businessDate,
                    cycle == null ? null : cycle.settlementEligibleAt()
            );
            return false;
        }
        log.info("Stock batch portfolio settlement stage started: businessDate={}", businessDate);
        StockBatchJobRunResponse response;
        if (businessDate.equals(simulationMarketSessionService.currentSimulationDate())) {
            response = scheduledJobGuard.runBatchIfEnabled(
                    PortfolioSettlementJob.JOB_NAME,
                    settlementSchedulerConfigured,
                    stockBatchJobLauncher::settlePortfoliosScheduled
            );
        } else {
            response = scheduledJobGuard.runBatchIfEnabled(
                    PortfolioSettlementJob.JOB_NAME,
                    settlementSchedulerConfigured,
                    () -> stockBatchJobLauncher.settlePortfolios(
                            businessDate,
                            simulationMarketSessionService.currentSimulationDateTime()
                    )
            );
        }
        log.info(
                "Stock batch portfolio settlement stage completed: businessDate={}, status={}, processedCount={}, message={}",
                businessDate,
                responseStatus(response),
                responseProcessedCount(response),
                responseMessage(response)
        );
        return isNotFailed(response);
    }

    /**
     * Market-close rollover owns open-order cancellation, holding snapshots, daily symbol
     * snapshots, and previous-close rollover. Settlement is intentionally separate and checked
     * after this method.
     */
    private boolean ensureMarketCloseCompleted(LocalDate businessDate) {
        if (marketCloseRolloverService.hasCompletedFullCloseRun(businessDate)) {
            log.info("Stock batch market close rollover already complete: businessDate={}", businessDate);
            return true;
        }
        if (!runMarketCloseRollover(businessDate)) {
            return false;
        }
        return marketCloseRolloverService.hasCompletedFullCloseRun(businessDate);
    }

    private boolean runMarketCloseRollover(LocalDate businessDate) {
        if (!marketCloseSchedulerConfigured) {
            log.info("Stock batch market close rollover skipped because scheduler is disabled: businessDate={}", businessDate);
            return false;
        }
        PostCloseCycle existingCycle = postCloseCycleService.findFullMarketCycle(businessDate).orElse(null);
        if (existingCycle != null
                && !postCloseCycleService.isPhaseClaimEligible(existingCycle, java.time.LocalDateTime.now())) {
            log.info(
                    "Stock batch market close retry deferred before Job launch: businessDate={}, status={}, nextRetryAt={}, leaseUntil={}",
                    businessDate,
                    existingCycle.status(),
                    existingCycle.nextRetryAt(),
                    existingCycle.leaseUntil()
            );
            return false;
        }
        log.info("Stock batch market close rollover stage started: businessDate={}", businessDate);
        StockBatchJobRunResponse response;
        if (businessDate.equals(simulationMarketSessionService.currentSimulationDate())) {
            response = scheduledJobGuard.runBatchIfEnabled(
                    MarketCloseRolloverJob.JOB_NAME,
                    marketCloseSchedulerConfigured,
                    stockBatchJobLauncher::rolloverClosingPricesScheduled
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
        log.info(
                "Stock batch market close rollover stage completed: businessDate={}, status={}, processedCount={}, message={}",
                businessDate,
                responseStatus(response),
                responseProcessedCount(response),
                responseMessage(response)
        );
        return isNotFailed(response);
    }

    private LocalDate closeDateNeedingPostCloseWork() {
        SimulationMarketSession session = simulationMarketSessionService.currentSession();
        LocalDate currentDate = simulationMarketSessionService.currentSimulationDate();
        PostCloseCycle oldestUnsettled = postCloseCycleService.findOldestUnsettledFullMarketCycle()
                .orElse(null);
        if (oldestUnsettled != null) {
            return oldestUnsettled.businessDate();
        }
        MarketSessionFenceService.MarketBusinessStateSnapshot state = marketSessionFenceService.businessState();
        if (state != null && !state.activeBusinessDate().equals(currentDate)) {
            if (postCloseCycleService.findFullMarketCycle(state.activeBusinessDate()).isEmpty()
                    && !state.activeBusinessDate().isBefore(
                    simulationMarketSessionService.baseSimulationDate()
            )) {
                return state.activeBusinessDate();
            }
            return null;
        }
        // Raw simulation time can enter REGULAR while a failed previous close keeps every market
        // CLOSED. Continue only the frozen-prefix recovery in that state; settlePortfolios()
        // performs the authoritative hasOpenMarket() guard before launching any heavy work. A
        // normal open session reaches this branch with no stale active date and returns without
        // touching order/execution ledgers.
        if (session == SimulationMarketSession.REGULAR) {
            return null;
        }
        if (postCloseCycleService.isSkippedCompleted(currentDate)) {
            return null;
        }
        return switch (session) {
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

    private String responseStatus(StockBatchJobRunResponse response) {
        return response == null ? "UNKNOWN" : response.status();
    }

    private int responseProcessedCount(StockBatchJobRunResponse response) {
        return response == null ? 0 : response.processedCount();
    }

    private String responseMessage(StockBatchJobRunResponse response) {
        return response == null ? "No batch response returned" : response.message();
    }
}
