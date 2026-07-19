package stock.batch.service.batch.execution.job;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.common.support.LightweightBatchTask;
import stock.batch.service.execution.biz.ExecutionAccountDaySummaryAccumulator;

@Component
@RequiredArgsConstructor
public class ExecutionAccountDaySummaryFlushJob implements LightweightBatchTask {

    public static final String JOB_NAME = "execution-account-day-summary-flush";

    private final ExecutionAccountDaySummaryAccumulator accumulator;

    @Override
    public String taskName() {
        return JOB_NAME;
    }

    @Override
    public String executionMode() {
        return "bounded-bulk-flush";
    }

    @Override
    public boolean requiresJobLock() {
        return true;
    }

    public boolean hasWork() {
        return accumulator.hasPendingDeltas();
    }

    @Override
    public int run() {
        return accumulator.flush();
    }
}
