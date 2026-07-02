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
import stock.batch.service.simulation.SimulationClockService;

@Component
@RequiredArgsConstructor
public class PortfolioSettlementScheduler {

    private final StockBatchJobLauncher stockBatchJobLauncher;
    private final StockBatchScheduledJobGuard scheduledJobGuard;
    private final SimulationClockService simulationClockService;

    @Value("${stock.batch.market-close.enabled:true}")
    private boolean marketCloseSchedulerConfigured;

    @Value("${stock.batch.settlement.enabled:true}")
    private boolean settlementSchedulerConfigured;

    private LocalDate lastObservedSimulationDate;

    @Scheduled(fixedDelayString = "${stock.batch.market-close.poll-fixed-delay-ms:5000}")
    public void rolloverSimulationDayIfNeeded() {
        LocalDate currentSimulationDate = simulationClockService.currentDate();
        if (lastObservedSimulationDate == null) {
            lastObservedSimulationDate = currentSimulationDate;
            return;
        }
        if (!currentSimulationDate.isAfter(lastObservedSimulationDate)) {
            return;
        }
        if (settlePortfolios()) {
            lastObservedSimulationDate = currentSimulationDate;
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
