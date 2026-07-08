package stock.batch.service.batch.signal.biz;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import stock.batch.service.common.vo.StockBatchJobRunResponse;

import java.time.LocalDateTime;

@Component
public class BatchJobSignalWriter {

    private final JdbcClient jdbcClient;

    public BatchJobSignalWriter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public void complete(long signalId, StockBatchJobRunResponse response) {
        LocalDateTime now = LocalDateTime.now();
        jdbcClient.sql(
                        """
                        update stock_batch_job_signal
                           set status = ?,
                               completed_at = ?,
                               processed_count = ?,
                               message = ?,
                               error_message = null,
                               updated_at = ?
                         where id = ?
                        """
                )
                .param(normalizeStatus(response.status()))
                .param(response.completedAt() == null ? now : response.completedAt())
                .param(response.processedCount())
                .param(truncate(response.message(), 500))
                .param(now)
                .param(signalId)
                .update();
    }

    @Transactional
    public void fail(long signalId, RuntimeException failure) {
        LocalDateTime now = LocalDateTime.now();
        jdbcClient.sql(
                        """
                        update stock_batch_job_signal
                           set status = 'FAILED',
                               completed_at = ?,
                               message = 'Batch signal processing failed',
                               error_message = ?,
                               updated_at = ?
                         where id = ?
                        """
                )
                .param(now)
                .param(truncate(failure.getMessage(), 1000))
                .param(now)
                .param(signalId)
                .update();
    }

    @Transactional
    public void defer(long signalId, StockBatchJobRunResponse response) {
        LocalDateTime now = LocalDateTime.now();
        jdbcClient.sql(
                        """
                        update stock_batch_job_signal
                           set status = 'PENDING',
                               picked_at = null,
                               completed_at = null,
                               processed_count = ?,
                               message = ?,
                               error_message = null,
                               updated_at = ?
                         where id = ?
                        """
                )
                .param(response == null ? 0 : response.processedCount())
                .param(truncate(response == null ? "Batch signal deferred" : response.message(), 500))
                .param(now)
                .param(signalId)
                .update();
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
}
