package stock.batch.service.batch.automarket.job;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import stock.batch.service.automarket.biz.AutoMarketDailyRegimePreCreateService;
import stock.batch.service.batch.common.support.StockBatchJob;

@Component
@RequiredArgsConstructor
public class AutoMarketDailyRegimePreCreateJob implements StockBatchJob {

    public static final String JOB_NAME = "auto-market-daily-regime-pre-create";
    private static final String EXECUTION_MODE = "daily-regime";

    private final AutoMarketDailyRegimePreCreateService autoMarketDailyRegimePreCreateService;

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
        return autoMarketDailyRegimePreCreateService.preCreateDailyRegimes();
    }
}
