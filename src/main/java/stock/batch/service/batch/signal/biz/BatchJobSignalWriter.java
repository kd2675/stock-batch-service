package stock.batch.service.batch.signal.biz;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import stock.batch.service.batch.signal.model.BatchJobSignal;
import stock.batch.service.common.vo.StockBatchJobRunResponse;

import java.time.LocalDateTime;

@Component
public class BatchJobSignalWriter {

    private final JdbcClient jdbcClient;
    private final long retryBaseSeconds;
    private final long retryMaxSeconds;

    public BatchJobSignalWriter(
            JdbcClient jdbcClient,
            @Value("${stock.batch.signal.retry-base-seconds:2}") long retryBaseSeconds,
            @Value("${stock.batch.signal.retry-max-seconds:300}") long retryMaxSeconds
    ) {
        if (retryBaseSeconds <= 0 || retryMaxSeconds < retryBaseSeconds) {
            throw new IllegalArgumentException("Signal retry backoff configuration is invalid");
        }
        this.jdbcClient = jdbcClient;
        this.retryBaseSeconds = retryBaseSeconds;
        this.retryMaxSeconds = retryMaxSeconds;
    }

    @Transactional
    public void complete(BatchJobSignal signal, StockBatchJobRunResponse response) {
        LocalDateTime now = LocalDateTime.now();
        int updated = jdbcClient.sql(
                        """
                        update stock_batch_job_signal
                           set status = ?,
                               completed_at = ?,
                               processed_count = ?,
                               message = ?,
                               error_message = null,
                               claim_token = null,
                               lease_until = null,
                               failure_class = null,
                               updated_at = ?
                         where id = ?
                           and status = 'PROCESSING'
                           and claim_token = ?
                        """
                )
                .param(normalizeStatus(response.status()))
                .param(response.completedAt() == null ? now : response.completedAt())
                .param(response.processedCount())
                .param(truncate(response.message(), 500))
                .param(now)
                .param(signal.id())
                .param(signal.claimToken())
                .update();
        requireClaimUpdate(updated, signal);
    }

    @Transactional
    public void fail(BatchJobSignal signal, RuntimeException failure) {
        LocalDateTime now = LocalDateTime.now();
        int updated = jdbcClient.sql(
                        """
                        update stock_batch_job_signal
                           set status = 'FAILED',
                               completed_at = ?,
                               message = 'Batch signal processing failed',
                               error_message = ?,
                               claim_token = null,
                               lease_until = null,
                               failure_class = 'PERMANENT',
                               updated_at = ?
                         where id = ?
                           and status = 'PROCESSING'
                           and claim_token = ?
                        """
                )
                .param(now)
                .param(truncate(failure.getMessage(), 1000))
                .param(now)
                .param(signal.id())
                .param(signal.claimToken())
                .update();
        requireClaimUpdate(updated, signal);
    }

    @Transactional
    public void defer(BatchJobSignal signal, StockBatchJobRunResponse response) {
        defer(signal, response == null ? "Batch signal deferred" : response.message(), "PHASE_DEFERRED");
    }

    @Transactional
    public void retry(BatchJobSignal signal, RuntimeException failure) {
        defer(signal, failure == null ? "Transient batch signal failure" : failure.getMessage(), "TRANSIENT");
    }

    private void defer(BatchJobSignal signal, String message, String failureClass) {
        LocalDateTime now = LocalDateTime.now();
        boolean exhausted = signal.attemptCount() >= signal.maxAttempts();
        String nextStatus = exhausted ? "DEAD_LETTER" : "DEFERRED";
        LocalDateTime nextAttemptAt = exhausted ? signal.nextAttemptAt() : now.plusSeconds(backoffSeconds(signal));
        int updated = jdbcClient.sql(
                        """
                        update stock_batch_job_signal
                           set status = :status,
                               completed_at = :completedAt,
                               processed_count = :processedCount,
                               message = :message,
                               error_message = :errorMessage,
                               next_attempt_at = :nextAttemptAt,
                               claim_token = null,
                               lease_until = null,
                               failure_class = :failureClass,
                               updated_at = :now
                         where id = :signalId
                           and status = 'PROCESSING'
                           and claim_token = :claimToken
                        """
                )
                .param("status", nextStatus)
                .param("completedAt", exhausted ? now : null)
                .param("processedCount", 0)
                .param("message", truncate(message, 500))
                .param("errorMessage", exhausted ? truncate(message, 1000) : null)
                .param("nextAttemptAt", nextAttemptAt)
                .param("failureClass", exhausted ? "MAX_ATTEMPTS" : failureClass)
                .param("now", now)
                .param("signalId", signal.id())
                .param("claimToken", signal.claimToken())
                .update();
        requireClaimUpdate(updated, signal);
    }

    private String normalizeStatus(String status) {
        return "FAILED".equals(status) ? "FAILED" : "COMPLETED";
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private long backoffSeconds(BatchJobSignal signal) {
        int exponent = Math.max(0, Math.min(20, signal.attemptCount() - 1));
        long multiplier = 1L << exponent;
        if (retryBaseSeconds > retryMaxSeconds / multiplier) {
            return retryMaxSeconds;
        }
        return Math.min(retryMaxSeconds, retryBaseSeconds * multiplier);
    }

    private void requireClaimUpdate(int updated, BatchJobSignal signal) {
        if (updated != 1) {
            throw new BatchJobSignalClaimLostException(signal.id());
        }
    }
}
