package stock.batch.service.batch.signal.biz;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import stock.batch.service.batch.signal.model.BatchJobSignal;

import java.time.LocalDateTime;
import java.util.Optional;

@Component
public class BatchJobSignalReader {

    private final JdbcClient jdbcClient;
    private final long processingTimeoutSeconds;

    public BatchJobSignalReader(
            JdbcClient jdbcClient,
            @Value("${stock.batch.signal.processing-timeout-seconds:1800}") long processingTimeoutSeconds
    ) {
        this.jdbcClient = jdbcClient;
        this.processingTimeoutSeconds = processingTimeoutSeconds;
    }

    @Transactional
    public Optional<BatchJobSignal> claimNext() {
        reclaimStaleProcessingSignals();
        Optional<Long> signalId = jdbcClient.sql(
                        """
                        select id
                          from stock_batch_job_signal
                         where status = 'PENDING'
                         order by requested_at asc, id asc
                         limit 1
                        """
                )
                .query(Long.class)
                .optional();
        if (signalId.isEmpty()) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now();
        int updatedRows = jdbcClient.sql(
                        """
                        update stock_batch_job_signal
                           set status = 'PROCESSING',
                               picked_at = ?,
                               updated_at = ?
                         where id = ?
                           and status = 'PENDING'
                        """
                )
                .param(now)
                .param(now)
                .param(signalId.get())
                .update();
        if (updatedRows != 1) {
            return Optional.empty();
        }
        return findClaimed(signalId.get());
    }

    private void reclaimStaleProcessingSignals() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(Math.max(60, processingTimeoutSeconds));
        jdbcClient.sql(
                        """
                        update stock_batch_job_signal
                           set status = 'PENDING',
                               message = 'Reclaimed stale processing signal',
                               updated_at = ?
                         where status = 'PROCESSING'
                           and picked_at < ?
                        """
                )
                .param(LocalDateTime.now())
                .param(cutoff)
                .update();
    }

    private Optional<BatchJobSignal> findClaimed(long signalId) {
        return jdbcClient.sql(
                        """
                        select id,
                               signal_type,
                               job_name,
                               execution_mode,
                               symbol,
                               requested_by,
                               requested_at
                          from stock_batch_job_signal
                         where id = ?
                           and status = 'PROCESSING'
                        """
                )
                .param(signalId)
                .query((rs, rowNum) -> new BatchJobSignal(
                        rs.getLong("id"),
                        rs.getString("signal_type"),
                        rs.getString("job_name"),
                        rs.getString("execution_mode"),
                        rs.getString("symbol"),
                        rs.getString("requested_by"),
                        rs.getObject("requested_at", LocalDateTime.class)
                ))
                .optional();
    }
}
