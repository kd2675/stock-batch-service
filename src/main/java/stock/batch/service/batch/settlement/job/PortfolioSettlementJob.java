package stock.batch.service.batch.settlement.job;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.common.support.StockBatchJob;
import stock.batch.service.settlement.biz.PortfolioSettlementService;

@Component
@RequiredArgsConstructor
public class PortfolioSettlementJob implements StockBatchJob {

    public static final String JOB_NAME = "portfolio-settlement";
    private static final String EXECUTION_MODE = "n/a";

    private final PortfolioSettlementService portfolioSettlementService;

    @Override
    public String jobName() {
        return JOB_NAME;
    }

    @Override
    public String executionMode() {
        return EXECUTION_MODE;
    }

    @Override
    public int run() {
        return portfolioSettlementService.settleToday();
    }

    public int run(LocalDate snapshotDate, LocalDateTime snapshotAt) {
        return portfolioSettlementService.settle(snapshotDate, snapshotAt);
    }
}
