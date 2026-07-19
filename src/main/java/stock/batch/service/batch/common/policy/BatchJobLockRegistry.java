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
    private static final int LOCK_OWNER_COLUMN_LENGTH = 128;
    private static final int UUID_TEXT_LENGTH = 36;

    private final JdbcTemplate jdbcTemplate;
    private final long lockTtlSeconds;
    private final String lockOwner;

    @Autowired
    public BatchJobLockRegistry(
            JdbcTemplate jdbcTemplate,
            @Value("${stock.batch.job-lock.ttl-seconds:180}") long lockTtlSeconds
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
        return tryAcquire(jobName, now, lockOwner);
    }

    /**
     * Acquires a lock for one physical execution token. Using a token distinct from the process
     * owner prevents an old heartbeat or release from mutating a newer execution that reclaimed
     * the same job after TTL expiry on the same JVM.
     */
    public boolean tryAcquire(String jobName, LocalDateTime now, String acquisitionOwner) {
        String normalizedJobName = BatchJobNames.normalize(jobName);
        String normalizedOwner = normalizeOwner(acquisitionOwner);
        LocalDateTime lockedUntil = now.plusSeconds(lockTtlSeconds);
        try {
            jdbcTemplate.update(
                    """
                    insert into stock_batch_job_lock(job_name, lock_owner, locked_until, created_at, updated_at)
                    values (?, ?, ?, ?, ?)
                    """,
                    normalizedJobName,
                    normalizedOwner,
                    lockedUntil,
                    now,
                    now
            );
            return true;
        } catch (DuplicateKeyException ignored) {
            return acquireExpiredLock(normalizedJobName, normalizedOwner, now, lockedUntil);
        }
    }

    public void release(String jobName) {
        release(jobName, lockOwner);
    }

    public void release(String jobName, String acquisitionOwner) {
        String normalizedJobName = BatchJobNames.normalize(jobName);
        String normalizedOwner = normalizeOwner(acquisitionOwner);
        jdbcTemplate.update(
                """
                delete from stock_batch_job_lock
                 where job_name = ?
                   and lock_owner = ?
                """,
                normalizedJobName,
                normalizedOwner
        );
    }

    public boolean renew(String jobName, LocalDateTime now) {
        return renew(jobName, now, lockOwner);
    }

    public boolean renew(String jobName, LocalDateTime now, String acquisitionOwner) {
        String normalizedJobName = BatchJobNames.normalize(jobName);
        String normalizedOwner = normalizeOwner(acquisitionOwner);
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
                normalizedOwner
        );
        return updatedRows == 1;
    }

    public String newAcquisitionOwner() {
        int maxPrefixLength = LOCK_OWNER_COLUMN_LENGTH - UUID_TEXT_LENGTH - 1;
        String prefix = lockOwner.length() <= maxPrefixLength
                ? lockOwner
                : lockOwner.substring(0, maxPrefixLength);
        return prefix + ":" + UUID.randomUUID();
    }

    public long lockTtlSeconds() {
        return lockTtlSeconds;
    }

    public String lockOwner() {
        return lockOwner;
    }

    private boolean acquireExpiredLock(
            String jobName,
            String acquisitionOwner,
            LocalDateTime now,
            LocalDateTime lockedUntil
    ) {
        int updatedRows = jdbcTemplate.update(
                """
                update stock_batch_job_lock
                   set lock_owner = ?,
                       locked_until = ?,
                       updated_at = ?
                 where job_name = ?
                   and locked_until <= ?
                """,
                acquisitionOwner,
                lockedUntil,
                now,
                jobName,
                now
        );
        return updatedRows == 1;
    }

    private String normalizeOwner(String owner) {
        if (!StringUtils.hasText(owner)) {
            throw new IllegalArgumentException("acquisitionOwner is required");
        }
        String normalized = owner.trim();
        if (normalized.length() > LOCK_OWNER_COLUMN_LENGTH) {
            throw new IllegalArgumentException("acquisitionOwner exceeds lock_owner column length");
        }
        return normalized;
    }
}
