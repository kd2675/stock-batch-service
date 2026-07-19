package stock.batch.service.batch.signal.biz;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import stock.batch.service.batch.signal.model.BatchJobSignal;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.Optional;

@Component
public class BatchJobSignalReader {

    private static final int DEAD_LETTER_SWEEP_LIMIT = 100;

    private static final String CLAIMABLE_SIGNAL_SQL = """
            select id
              from stock_batch_job_signal
             where (
                       (status in ('PENDING', 'DEFERRED') and next_attempt_at <= :systemNow)
                       or (status = 'PROCESSING' and lease_until <= :systemNow)
                   )
               and (eligible_at is null or eligible_at <= :simulationNow)
               and (
                   signal_type <> 'AUTO_PARTICIPANT_CASH_FLOW_RUN'
                   or exists (
                       select 1
                         from stock_post_close_cycle cycle
                        where cycle.business_date = requested_business_date
                          and cycle.scope_type = 'FULL_MARKET'
                          and cycle.scope_key = 'ALL'
                          and cycle.phase in (
                              'PORTFOLIO_SETTLED',
                              'OVERNIGHT_CASH_APPLIED',
                              'CORPORATE_CASH_APPLIED',
                              'REPORTS_AGGREGATED',
                              'PREOPEN_SECURITY_TRANSFORMS_APPLIED',
                              'MARKET_DATA_PREPARED',
                              'AUTO_MARKET_PREPARED',
                              'READY_TO_OPEN',
                              'COMPLETED'
                          )
                   )
               )
               and attempt_count < max_attempts
             order by next_attempt_at asc, id asc
             limit 1
            """;

    private final JdbcClient jdbcClient;
    private final long leaseSeconds;

    public BatchJobSignalReader(
            JdbcClient jdbcClient,
            @Value("${stock.batch.signal.lease-seconds:180}") long leaseSeconds
    ) {
        if (leaseSeconds <= 0) {
            throw new IllegalArgumentException("stock.batch.signal.lease-seconds must be positive");
        }
        this.jdbcClient = jdbcClient;
        this.leaseSeconds = leaseSeconds;
    }

    public boolean hasClaimable(LocalDateTime simulationNow) {
        if (simulationNow == null) {
            throw new IllegalArgumentException("simulationNow is required");
        }
        return findClaimableId(simulationNow, LocalDateTime.now(), false).isPresent();
    }

    /**
     * Renews only the exact signal claim owned by the caller. Signal processing can launch a
     * bounded but long-running Spring Batch job, so the original claim lease must not expire and
     * let another scheduler execute the same manual command concurrently. This is one primary-key
     * update per heartbeat and never reads or locks the order/execution ledgers.
     */
    @Transactional
    public boolean renewLease(BatchJobSignal signal, LocalDateTime systemNow) {
        if (signal == null || systemNow == null || signal.claimToken() == null || signal.claimToken().isBlank()) {
            throw new IllegalArgumentException("Signal, systemNow, and claimToken are required for lease renewal");
        }
        return jdbcClient.sql(
                        """
                        update stock_batch_job_signal
                           set lease_until = :leaseUntil,
                               updated_at = :systemNow
                         where id = :signalId
                           and status = 'PROCESSING'
                           and claim_token = :claimToken
                        """
                )
                .param("leaseUntil", systemNow.plusSeconds(leaseSeconds))
                .param("systemNow", systemNow)
                .param("signalId", signal.id())
                .param("claimToken", signal.claimToken())
                .update() == 1;
    }

    @Transactional
    public Optional<BatchJobSignal> claimNext(LocalDateTime simulationNow) {
        if (simulationNow == null) {
            throw new IllegalArgumentException("simulationNow is required");
        }
        LocalDateTime systemNow = LocalDateTime.now();
        Optional<Long> signalId = findClaimableId(simulationNow, systemNow, true);
        if (signalId.isEmpty()) {
            return Optional.empty();
        }
        String claimToken = UUID.randomUUID().toString();
        LocalDateTime leaseUntil = systemNow.plusSeconds(leaseSeconds);
        int updatedRows = jdbcClient.sql(
                        """
                        update stock_batch_job_signal
                           set status = 'PROCESSING',
                               picked_at = :systemNow,
                               completed_at = null,
                               attempt_count = attempt_count + 1,
                               claim_token = :claimToken,
                               lease_until = :leaseUntil,
                               failure_class = null,
                               updated_at = :systemNow
                         where id = :signalId
                        """
                )
                .param("systemNow", systemNow)
                .param("claimToken", claimToken)
                .param("leaseUntil", leaseUntil)
                .param("signalId", signalId.get())
                .update();
        if (updatedRows != 1) {
            return Optional.empty();
        }
        return findClaimed(signalId.get(), claimToken);
    }

    private Optional<Long> findClaimableId(
            LocalDateTime simulationNow,
            LocalDateTime systemNow,
            boolean lock
    ) {
        String sql = lock ? CLAIMABLE_SIGNAL_SQL + "\nfor update skip locked" : CLAIMABLE_SIGNAL_SQL;
        return jdbcClient.sql(sql)
                .param("systemNow", systemNow)
                .param("simulationNow", simulationNow)
                .query(Long.class)
                .optional();
    }

    @Transactional
    public int deadLetterExhaustedLeases(LocalDateTime systemNow) {
        var signalIds = jdbcClient.sql(
                        """
                        select id
                          from stock_batch_job_signal
                         where status = 'PROCESSING'
                           and lease_until <= ?
                           and attempt_count >= max_attempts
                         order by lease_until asc, id asc
                         limit ?
                         for update skip locked
                        """
                )
                .param(systemNow)
                .param(DEAD_LETTER_SWEEP_LIMIT)
                .query(Long.class)
                .list();
        if (signalIds.isEmpty()) {
            return 0;
        }
        return jdbcClient.sql(
                        """
                        update stock_batch_job_signal
                           set status = 'DEAD_LETTER',
                               completed_at = :systemNow,
                               claim_token = null,
                               lease_until = null,
                               failure_class = 'LEASE_EXHAUSTED',
                               message = 'Signal lease expired after maximum attempts',
                               updated_at = :systemNow
                         where id in (:signalIds)
                           and status = 'PROCESSING'
                           and lease_until <= :systemNow
                           and attempt_count >= max_attempts
                        """
                )
                .param("signalIds", signalIds)
                .param("systemNow", systemNow)
                .update();
    }

    private Optional<BatchJobSignal> findClaimed(long signalId, String claimToken) {
        return jdbcClient.sql(
                        """
                        select id,
                               signal_type,
                               job_name,
                               execution_mode,
                               symbol,
                               requested_by,
                               requested_at,
                               requested_business_date,
                               requested_session_epoch,
                               expected_cycle_id,
                               eligible_at,
                               next_attempt_at,
                               attempt_count,
                               max_attempts,
                               claim_token,
                               lease_until
                          from stock_batch_job_signal
                         where id = ?
                           and status = 'PROCESSING'
                           and claim_token = ?
                        """
                )
                .param(signalId)
                .param(claimToken)
                .query((rs, rowNum) -> new BatchJobSignal(
                        rs.getLong("id"),
                        rs.getString("signal_type"),
                        rs.getString("job_name"),
                        rs.getString("execution_mode"),
                        rs.getString("symbol"),
                        rs.getString("requested_by"),
                        rs.getObject("requested_at", LocalDateTime.class),
                        rs.getObject("requested_business_date", LocalDate.class),
                        rs.getObject("requested_session_epoch", Long.class),
                        rs.getObject("expected_cycle_id", Long.class),
                        rs.getObject("eligible_at", LocalDateTime.class),
                        rs.getObject("next_attempt_at", LocalDateTime.class),
                        rs.getInt("attempt_count"),
                        rs.getInt("max_attempts"),
                        rs.getString("claim_token"),
                        rs.getObject("lease_until", LocalDateTime.class)
                ))
                .optional();
    }
}
