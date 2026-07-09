package stock.batch.service.batch.common.support;

import java.time.LocalDateTime;

import stock.batch.service.common.vo.StockBatchJobRunResponse;

public final class StockBatchJobRunResponses {

    static final String COMPLETED = "COMPLETED";
    static final String SKIPPED = "SKIPPED";
    static final String FAILED = "FAILED";
    static final String ALREADY_RUNNING_MESSAGE = "Job is already running";
    static final String SHUTTING_DOWN_MESSAGE = "Batch service is shutting down";
    public static final String MANUAL_CASH_FLOW_AUTO_ENABLED_MESSAGE =
            "Manual recurring cash is allowed only when automatic cash flow is disabled";
    private static final String SCHEDULED_EXECUTION_MODE = "scheduled";
    private static final String SCHEDULED_DISABLED_MESSAGE = "Scheduled job is disabled";

    private StockBatchJobRunResponses() {
    }

    static StockBatchJobRunResponse shuttingDown(StockBatchJob job, LocalDateTime now) {
        return response(job, SKIPPED, 0, SHUTTING_DOWN_MESSAGE, now, now);
    }

    public static StockBatchJobRunResponse scheduledDisabled(String jobName, LocalDateTime now) {
        return response(jobName, SCHEDULED_EXECUTION_MODE, SKIPPED, 0, SCHEDULED_DISABLED_MESSAGE, now, now);
    }

    public static StockBatchJobRunResponse scheduledFailure(String jobName, RuntimeException exception, LocalDateTime now) {
        return response(jobName, SCHEDULED_EXECUTION_MODE, FAILED, 0, failureMessage(exception), now, now);
    }

    public static boolean isFailed(StockBatchJobRunResponse response) {
        return response == null || FAILED.equals(response.status());
    }

    public static boolean isManualCashFlowAutoEnabledSkip(StockBatchJobRunResponse response) {
        return response != null
                && SKIPPED.equals(response.status())
                && MANUAL_CASH_FLOW_AUTO_ENABLED_MESSAGE.equals(response.message());
    }

    public static StockBatchJobRunResponse manualCashFlowAutoEnabled(LocalDateTime now) {
        return response(
                "auto-participant-cash-flow",
                "manual-recurring-cash",
                SKIPPED,
                0,
                MANUAL_CASH_FLOW_AUTO_ENABLED_MESSAGE,
                now,
                now
        );
    }

    static StockBatchJobRunResponse alreadyRunning(StockBatchJob job, LocalDateTime startedAt, LocalDateTime endedAt) {
        return response(job, SKIPPED, 0, ALREADY_RUNNING_MESSAGE, startedAt, endedAt);
    }

    static StockBatchJobRunResponse completed(
            StockBatchJob job,
            int processedCount,
            LocalDateTime startedAt,
            LocalDateTime endedAt
    ) {
        return response(job, COMPLETED, processedCount, "Job completed", startedAt, endedAt);
    }

    static StockBatchJobRunResponse failed(
            StockBatchJob job,
            RuntimeException exception,
            LocalDateTime startedAt,
            LocalDateTime endedAt
    ) {
        return response(job, FAILED, 0, failureMessage(exception), startedAt, endedAt);
    }

    private static String failureMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message;
    }

    private static StockBatchJobRunResponse response(
            StockBatchJob job,
            String status,
            int processedCount,
            String message,
            LocalDateTime startedAt,
            LocalDateTime endedAt
    ) {
        return response(job.jobName(), job.executionMode(), status, processedCount, message, startedAt, endedAt);
    }

    private static StockBatchJobRunResponse response(
            String jobName,
            String executionMode,
            String status,
            int processedCount,
            String message,
            LocalDateTime startedAt,
            LocalDateTime endedAt
    ) {
        return new StockBatchJobRunResponse(
                jobName,
                status,
                executionMode,
                processedCount,
                message,
                startedAt,
                endedAt
        );
    }
}
