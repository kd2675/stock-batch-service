package stock.batch.service.batch.automarket.job;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import stock.batch.service.automarket.biz.AutoParticipantCashFlowService;
import stock.batch.service.batch.common.support.StockBatchJob;

@Component
@RequiredArgsConstructor
public class AutoParticipantCashFlowJob implements StockBatchJob {

    public static final String JOB_NAME = "auto-participant-cash-flow";
    private static final String EXECUTION_MODE = "recurring-cash";

    private final AutoParticipantCashFlowService autoParticipantCashFlowService;

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
        return autoParticipantCashFlowService.fundRecurringCash();
    }

    public int runManually() {
        return autoParticipantCashFlowService.fundRecurringCashManually();
    }
}
