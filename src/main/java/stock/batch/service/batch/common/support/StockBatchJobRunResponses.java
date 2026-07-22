package stock.batch.service.batch.common.support;

import java.time.LocalDateTime;

import stock.batch.service.common.vo.StockBatchJobRunResponse;

public final class StockBatchJobRunResponses {

    static final String COMPLETED = "COMPLETED";
    static final String SKIPPED = "SKIPPED";
    static final String FAILED = "FAILED";
    static final String ALREADY_RUNNING_MESSAGE = "Job is already running";
    public static final String ALREADY_COMPLETE_MESSAGE = "Job instance is already complete";
    static final String SHUTTING_DOWN_MESSAGE = "Batch service is shutting down";
    public static final String MANUAL_CASH_FLOW_AUTO_ENABLED_MESSAGE =
            "Manual recurring cash is allowed only when automatic cash flow is disabled";
    public static final String MANUAL_CASH_FLOW_BEFORE_MARKET_CLOSE_MESSAGE =
            "Manual recurring cash is deferred until market close";
    public static final String MANUAL_CASH_FLOW_OVERNIGHT_DEFERRED_MESSAGE =
            "Manual recurring cash is deferred until corporate cash actions complete and the overnight window";
    private static final String POST_CLOSE_OVERNIGHT_DEFERRED_MESSAGE =
            "Post-close cash phase is deferred until the overnight window";
    private static final String SCHEDULED_EXECUTION_MODE = "scheduled";
    private static final String SCHEDULED_DISABLED_MESSAGE = "Scheduled job is disabled";

    private StockBatchJobRunResponses() {
    }

    static StockBatchJobRunResponse shuttingDown(BatchExecutionDescriptor execution, LocalDateTime now) {
        return response(execution, SKIPPED, 0, SHUTTING_DOWN_MESSAGE, now, now);
    }

    public static StockBatchJobRunResponse scheduledDisabled(String jobName, LocalDateTime now) {
        return response(jobName, SCHEDULED_EXECUTION_MODE, SKIPPED, 0, SCHEDULED_DISABLED_MESSAGE, now, now);
    }

    public static StockBatchJobRunResponse scheduledFailure(String jobName, RuntimeException exception, LocalDateTime now) {
        return response(jobName, SCHEDULED_EXECUTION_MODE, FAILED, 0, failureMessage(exception), now, now);
    }

    public static StockBatchJobRunResponse completedWithoutWork(
            String jobName,
            String executionMode,
            String message,
            LocalDateTime now
    ) {
        return response(jobName, executionMode, COMPLETED, 0, message, now, now);
    }

    public static StockBatchJobRunResponse cycleClaimDeferred(
            String jobName,
            String executionMode,
            LocalDateTime nextEligibleAt,
            LocalDateTime now
    ) {
        String message = nextEligibleAt == null
                ? "Post-close cycle is already running or not claimable"
                : "Post-close cycle retry is deferred until " + nextEligibleAt;
        return response(jobName, executionMode, SKIPPED, 0, message, now, now);
    }

    public static StockBatchJobRunResponse coordinatorManaged(
            String jobName,
            String executionMode,
            LocalDateTime now
    ) {
        return response(
                jobName,
                executionMode,
                SKIPPED,
                0,
                "Post-close coordinator owns this operation; use the current cycle phase",
                now,
                now
        );
    }

    public static boolean isFailed(StockBatchJobRunResponse response) {
        return response == null || FAILED.equals(response.status());
    }

    public static boolean isManualCashFlowAutoEnabledSkip(StockBatchJobRunResponse response) {
        return response != null
                && SKIPPED.equals(response.status())
                && MANUAL_CASH_FLOW_AUTO_ENABLED_MESSAGE.equals(response.message());
    }

    public static boolean isAlreadyCompleteSkip(StockBatchJobRunResponse response) {
        return response != null
                && SKIPPED.equals(response.status())
                && ALREADY_COMPLETE_MESSAGE.equals(response.message());
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

    public static StockBatchJobRunResponse manualCashFlowBeforeMarketClose(LocalDateTime now) {
        return response(
                "auto-participant-cash-flow",
                "manual-recurring-cash",
                SKIPPED,
                0,
                MANUAL_CASH_FLOW_BEFORE_MARKET_CLOSE_MESSAGE,
                now,
                now
        );
    }

    public static StockBatchJobRunResponse manualCashFlowOvernightDeferred(
            LocalDateTime eligibleAt,
            LocalDateTime now
    ) {
        String message = eligibleAt == null
                ? MANUAL_CASH_FLOW_OVERNIGHT_DEFERRED_MESSAGE
                : MANUAL_CASH_FLOW_OVERNIGHT_DEFERRED_MESSAGE + ": eligibleAt=" + eligibleAt;
        return response(
                "auto-participant-cash-flow",
                "manual-recurring-cash",
                SKIPPED,
                0,
                message,
                now,
                now
        );
    }

    public static StockBatchJobRunResponse postCloseOvernightDeferred(
            String jobName,
            String executionMode,
            LocalDateTime eligibleAt,
            LocalDateTime now
    ) {
        String message = POST_CLOSE_OVERNIGHT_DEFERRED_MESSAGE + ": eligibleAt=" + eligibleAt;
        return response(jobName, executionMode, SKIPPED, 0, message, now, now);
    }

    static StockBatchJobRunResponse alreadyRunning(
            BatchExecutionDescriptor execution,
            LocalDateTime startedAt,
            LocalDateTime endedAt
    ) {
        return response(execution, SKIPPED, 0, ALREADY_RUNNING_MESSAGE, startedAt, endedAt);
    }

    static StockBatchJobRunResponse completed(
            BatchExecutionDescriptor execution,
            int processedCount,
            LocalDateTime startedAt,
            LocalDateTime endedAt
    ) {
        return response(execution, COMPLETED, processedCount, "Job completed", startedAt, endedAt);
    }

    static StockBatchJobRunResponse failed(
            BatchExecutionDescriptor execution,
            Throwable exception,
            LocalDateTime startedAt,
            LocalDateTime endedAt
    ) {
        return failed(execution, exception, 0, startedAt, endedAt);
    }

    static StockBatchJobRunResponse failed(
            BatchExecutionDescriptor execution,
            Throwable exception,
            int processedCount,
            LocalDateTime startedAt,
            LocalDateTime endedAt
    ) {
        return response(execution, FAILED, processedCount, failureMessage(exception), startedAt, endedAt);
    }

    static StockBatchJobRunResponse alreadyComplete(
            BatchExecutionDescriptor execution,
            LocalDateTime startedAt,
            LocalDateTime endedAt
    ) {
        return response(execution, SKIPPED, 0, ALREADY_COMPLETE_MESSAGE, startedAt, endedAt);
    }

    private static String failureMessage(Throwable exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message;
    }

    private static StockBatchJobRunResponse response(
            BatchExecutionDescriptor execution,
            String status,
            int processedCount,
            String message,
            LocalDateTime startedAt,
            LocalDateTime endedAt
    ) {
        return response(
                execution.jobName(),
                execution.executionMode(),
                status,
                processedCount,
                message,
                startedAt,
                endedAt
        );
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
