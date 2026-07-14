package stock.batch.service.batch.common.support;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.batch.core.job.parameters.JobParametersBuilder;

public final class StockBatchJobParameters {

    public static final String BUSINESS_DATE = "businessDate";
    public static final String CLOSED_AT = "closedAt";
    public static final String ENFORCE_CLOSE = "enforceClose";
    public static final String JOB_MODE = "jobMode";
    public static final String OPERATION = "operation";
    public static final String REQUEST_ID = "requestId";
    public static final String RUN_VERSION = "runVersion";
    public static final String SESSION = "session";
    public static final String SIGNAL_ID = "signalId";
    public static final String SNAPSHOT_AT = "snapshotAt";
    public static final String SWEEP_AT = "sweepAt";
    public static final String SYMBOL = "symbol";
    public static final String TRIGGERED_AT = "triggeredAt";
    public static final String TRIGGERED_BY = "triggeredBy";

    private StockBatchJobParameters() {
    }

    public static JobParametersBuilder base(
            LocalDate businessDate,
            String jobMode,
            String triggeredBy,
            String requestId,
            LocalDateTime triggeredAt
    ) {
        return new JobParametersBuilder()
                .addLocalDate(BUSINESS_DATE, businessDate, true)
                .addString(JOB_MODE, jobMode, true)
                .addString(TRIGGERED_BY, triggeredBy, false)
                .addString(REQUEST_ID, requestId, false)
                .addLocalDateTime(TRIGGERED_AT, triggeredAt, false);
    }
}
