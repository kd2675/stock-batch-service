package stock.batch.service.batch.common.policy;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class BatchJobLockRegistry {

    private static final String DEFAULT_LOCK_OWNER_PREFIX = "stock-batch-service-";

    private final JdbcTemplate jdbcTemplate;
    private final long lockTtlSeconds;
    private final String lockOwner;

    @Autowired
    public BatchJobLockRegistry(
            JdbcTemplate jdbcTemplate,
            @Value("${stock.batch.job-lock.ttl-seconds:1800}") long lockTtlSeconds
    ) {
        this(jdbcTemplate, lockTtlSeconds, DEFAULT_LOCK_OWNER_PREFIX + UUID.randomUUID());
    }

    public BatchJobLockRegistry(JdbcTemplate jdbcTemplate, long lockTtlSeconds, String lockOwner) {
        if (lockTtlSeconds <= 0) {
            throw new IllegalArgumentException("lockTtlSeconds must be positive");
        }
        if (!StringUtils.hasText(lockOwner)) {
            throw new IllegalArgumentException("lockOwner is required");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.lockTtlSeconds = lockTtlSeconds;
        this.lockOwner = lockOwner.trim();
    }

    public boolean tryAcquire(String jobName, LocalDateTime now) {
        String normalizedJobName = normalizeJobName(jobName);
        LocalDateTime lockedUntil = now.plusSeconds(lockTtlSeconds);
        try {
            jdbcTemplate.update(
                    """
                    insert into stock_batch_job_lock(job_name, lock_owner, locked_until, created_at, updated_at)
                    values (?, ?, ?, ?, ?)
                    """,
                    normalizedJobName,
                    lockOwner,
                    lockedUntil,
                    now,
                    now
            );
            return true;
        } catch (DuplicateKeyException ignored) {
            return acquireExpiredLock(normalizedJobName, now, lockedUntil);
        }
    }

    public void release(String jobName) {
        String normalizedJobName = normalizeJobName(jobName);
        jdbcTemplate.update(
                """
                delete from stock_batch_job_lock
                 where job_name = ?
                   and lock_owner = ?
                """,
                normalizedJobName,
                lockOwner
        );
    }

    public boolean renew(String jobName, LocalDateTime now) {
        String normalizedJobName = normalizeJobName(jobName);
        LocalDateTime lockedUntil = now.plusSeconds(lockTtlSeconds);
        int updatedRows = jdbcTemplate.update(
                """
                update stock_batch_job_lock
                   set locked_until = ?,
                       updated_at = ?
                 where job_name = ?
                   and lock_owner = ?
                """,
                lockedUntil,
                now,
                normalizedJobName,
                lockOwner
        );
        return updatedRows == 1;
    }

    public long lockTtlSeconds() {
        return lockTtlSeconds;
    }

    private boolean acquireExpiredLock(String jobName, LocalDateTime now, LocalDateTime lockedUntil) {
        int updatedRows = jdbcTemplate.update(
                """
                update stock_batch_job_lock
                   set lock_owner = ?,
                       locked_until = ?,
                       updated_at = ?
                 where job_name = ?
                   and locked_until <= ?
                """,
                lockOwner,
                lockedUntil,
                now,
                jobName,
                now
        );
        return updatedRows == 1;
    }

    private String normalizeJobName(String jobName) {
        if (!StringUtils.hasText(jobName)) {
            throw new IllegalArgumentException("jobName is required");
        }
        return jobName.trim();
    }
}
