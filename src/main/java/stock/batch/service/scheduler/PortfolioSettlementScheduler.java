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
import stock.batch.service.marketclose.biz.OrderBookMarketSessionStateService;
import stock.batch.service.simulation.SimulationMarketSessionService;

@Component
@RequiredArgsConstructor
public class PortfolioSettlementScheduler {

    private final StockBatchJobLauncher stockBatchJobLauncher;
    private final StockBatchScheduledJobGuard scheduledJobGuard;
    private final SimulationMarketSessionService simulationMarketSessionService;
    private final OrderBookMarketSessionStateService orderBookMarketSessionStateService;

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
        if (!simulationMarketSessionService.isAfterCloseSession()) {
            return;
        }
        LocalDate currentSimulationDate = simulationMarketSessionService.currentSimulationDate();
        if (currentSimulationDate.equals(lastPostCloseProcessedDate)) {
            return;
        }
        if (settlePortfolios()) {
            lastPostCloseProcessedDate = currentSimulationDate;
        }
    }

    void rolloverClosingPrices() {
        runMarketCloseRollover();
    }

    boolean settlePortfolios() {
        if (!runMarketCloseRollover()) {
            return false;
        }
        return runPortfolioSettlement();
    }

    private boolean runPortfolioSettlement() {
        if (!settlementSchedulerConfigured) {
            return true;
        }
        StockBatchJobRunResponse response = scheduledJobGuard.runBatchIfEnabled(
                PortfolioSettlementJob.JOB_NAME,
                settlementSchedulerConfigured,
                stockBatchJobLauncher::settlePortfolios
        );
        return isNotFailed(response);
    }

    private boolean runMarketCloseRollover() {
        if (!marketCloseSchedulerConfigured) {
            return true;
        }
        StockBatchJobRunResponse response = scheduledJobGuard.runBatchIfEnabled(
                MarketCloseRolloverJob.JOB_NAME,
                marketCloseSchedulerConfigured,
                stockBatchJobLauncher::rolloverClosingPrices
        );
        return isNotFailed(response);
    }

    private boolean isNotFailed(StockBatchJobRunResponse response) {
        return !StockBatchJobRunResponses.isFailed(response);
    }
}
