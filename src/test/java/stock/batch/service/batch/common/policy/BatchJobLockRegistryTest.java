package stock.batch.service.batch.common.policy;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import stock.batch.service.testsupport.BatchTestDatabaseFactory;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BatchJobLockRegistryTest {

    @Test
    void tryAcquire_firstInstanceLocksJob_secondInstanceCannotAcquireBeforeTtl() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        BatchJobLockRegistry firstInstance = new BatchJobLockRegistry(jdbcTemplate, 60, "batch-node-1");
        BatchJobLockRegistry secondInstance = new BatchJobLockRegistry(jdbcTemplate, 60, "batch-node-2");
        LocalDateTime now = LocalDateTime.of(2026, 6, 25, 10, 0);

        boolean firstAcquired = firstInstance.tryAcquire("auto-market", now);
        boolean secondAcquired = secondInstance.tryAcquire("auto-market", now.plusSeconds(30));

        assertThat(firstAcquired).isTrue();
        assertThat(secondAcquired).isFalse();
        assertThat(lockOwner(jdbcTemplate, "auto-market")).isEqualTo("batch-node-1");
    }

    @Test
    void tryAcquire_expiredLock_canBeTakenBySecondInstance() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        BatchJobLockRegistry firstInstance = new BatchJobLockRegistry(jdbcTemplate, 60, "batch-node-1");
        BatchJobLockRegistry secondInstance = new BatchJobLockRegistry(jdbcTemplate, 60, "batch-node-2");
        LocalDateTime now = LocalDateTime.of(2026, 6, 25, 10, 0);

        boolean firstAcquired = firstInstance.tryAcquire("auto-market", now);
        boolean secondAcquired = secondInstance.tryAcquire("auto-market", now.plusSeconds(61));

        assertThat(firstAcquired).isTrue();
        assertThat(secondAcquired).isTrue();
        assertThat(lockOwner(jdbcTemplate, "auto-market")).isEqualTo("batch-node-2");
    }

    @Test
    void tryAcquire_lockAtExactExpiry_canBeTakenBySecondInstance() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        BatchJobLockRegistry firstInstance = new BatchJobLockRegistry(jdbcTemplate, 60, "batch-node-1");
        BatchJobLockRegistry secondInstance = new BatchJobLockRegistry(jdbcTemplate, 60, "batch-node-2");
        LocalDateTime now = LocalDateTime.of(2026, 6, 25, 10, 0);

        boolean firstAcquired = firstInstance.tryAcquire("auto-market", now);
        boolean secondAcquired = secondInstance.tryAcquire("auto-market", now.plusSeconds(60));

        assertThat(firstAcquired).isTrue();
        assertThat(secondAcquired).isTrue();
        assertThat(lockOwner(jdbcTemplate, "auto-market")).isEqualTo("batch-node-2");
    }

    @Test
    void renew_currentOwnerExtendsLockAndPreventsExpiredTakeover() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        BatchJobLockRegistry firstInstance = new BatchJobLockRegistry(jdbcTemplate, 60, "batch-node-1");
        BatchJobLockRegistry secondInstance = new BatchJobLockRegistry(jdbcTemplate, 60, "batch-node-2");
        LocalDateTime now = LocalDateTime.of(2026, 6, 25, 10, 0);

        assertThat(firstInstance.tryAcquire("auto-market", now)).isTrue();

        boolean renewed = firstInstance.renew("auto-market", now.plusSeconds(50));
        boolean secondAcquiredAtOriginalExpiry = secondInstance.tryAcquire("auto-market", now.plusSeconds(60));

        assertThat(renewed).isTrue();
        assertThat(secondAcquiredAtOriginalExpiry).isFalse();
        assertThat(lockOwner(jdbcTemplate, "auto-market")).isEqualTo("batch-node-1");
        assertThat(lockedUntil(jdbcTemplate, "auto-market")).isEqualTo(now.plusSeconds(110));
    }

    @Test
    void renew_differentOwnerCannotExtendLock() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        BatchJobLockRegistry firstInstance = new BatchJobLockRegistry(jdbcTemplate, 60, "batch-node-1");
        BatchJobLockRegistry secondInstance = new BatchJobLockRegistry(jdbcTemplate, 60, "batch-node-2");
        LocalDateTime now = LocalDateTime.of(2026, 6, 25, 10, 0);

        assertThat(firstInstance.tryAcquire("auto-market", now)).isTrue();

        boolean renewed = secondInstance.renew("auto-market", now.plusSeconds(50));

        assertThat(renewed).isFalse();
        assertThat(lockOwner(jdbcTemplate, "auto-market")).isEqualTo("batch-node-1");
        assertThat(lockedUntil(jdbcTemplate, "auto-market")).isEqualTo(now.plusSeconds(60));
    }

    @Test
    void release_onlyCurrentOwnerCanDeleteLock() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        BatchJobLockRegistry firstInstance = new BatchJobLockRegistry(jdbcTemplate, 60, "batch-node-1");
        BatchJobLockRegistry secondInstance = new BatchJobLockRegistry(jdbcTemplate, 60, "batch-node-2");
        LocalDateTime now = LocalDateTime.of(2026, 6, 25, 10, 0);

        assertThat(firstInstance.tryAcquire("auto-market", now)).isTrue();

        secondInstance.release("auto-market");

        assertThat(lockRowCount(jdbcTemplate, "auto-market")).isEqualTo(1L);
        assertThat(lockOwner(jdbcTemplate, "auto-market")).isEqualTo("batch-node-1");

        firstInstance.release("auto-market");

        assertThat(lockRowCount(jdbcTemplate, "auto-market")).isZero();
    }

    @Test
    void executionToken_expiredLockReclaimed_oldHeartbeatAndReleaseCannotMutateNewExecution() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        BatchJobLockRegistry registry = new BatchJobLockRegistry(jdbcTemplate, 60, "batch-node-1");
        LocalDateTime now = LocalDateTime.of(2026, 6, 25, 10, 0);
        String firstExecution = "batch-node-1:first-execution";
        String secondExecution = "batch-node-1:second-execution";

        assertThat(registry.tryAcquire("post-close-heavy-admission", now, firstExecution)).isTrue();
        assertThat(registry.tryAcquire(
                "post-close-heavy-admission",
                now.plusSeconds(61),
                secondExecution
        )).isTrue();

        assertThat(registry.renew(
                "post-close-heavy-admission",
                now.plusSeconds(62),
                firstExecution
        )).isFalse();
        registry.release("post-close-heavy-admission", firstExecution);

        assertThat(lockOwner(jdbcTemplate, "post-close-heavy-admission")).isEqualTo(secondExecution);
        assertThat(lockRowCount(jdbcTemplate, "post-close-heavy-admission")).isOne();
    }

    @Test
    void newAcquisitionOwner_eachExecutionIsDistinctAndFitsSchema() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        BatchJobLockRegistry registry = new BatchJobLockRegistry(
                jdbcTemplate,
                60,
                "x".repeat(128)
        );

        String first = registry.newAcquisitionOwner();
        String second = registry.newAcquisitionOwner();

        assertThat(first).hasSizeLessThanOrEqualTo(128).isNotEqualTo(second);
        assertThat(second).hasSizeLessThanOrEqualTo(128);
    }

    @Test
    void tryAcquire_blankJobName_rejectsBeforeWritingDatabase() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        BatchJobLockRegistry registry = new BatchJobLockRegistry(jdbcTemplate, 60, "batch-node-1");

        assertThatThrownBy(() -> registry.tryAcquire("  ", LocalDateTime.of(2026, 6, 25, 10, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jobName is required");
        assertThat(jdbcTemplate.queryForObject("select count(*) from stock_batch_job_lock", Long.class))
                .isZero();
    }

    @Test
    void constructor_invalidLockSettings_rejectsBeforeWritingDatabase() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();

        assertThatThrownBy(() -> new BatchJobLockRegistry(jdbcTemplate, 0, "batch-node-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("lockTtlSeconds must be positive");
        assertThatThrownBy(() -> new BatchJobLockRegistry(jdbcTemplate, 60, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("lockOwner is required");
    }

    private JdbcTemplate createJdbcTemplate() {
        return BatchTestDatabaseFactory.createJobLockJdbcTemplate("batch_job_lock_registry_test");
    }

    private Long lockRowCount(JdbcTemplate jdbcTemplate, String jobName) {
        return jdbcTemplate.queryForObject(
                "select count(*) from stock_batch_job_lock where job_name = ?",
                Long.class,
                jobName
        );
    }

    private String lockOwner(JdbcTemplate jdbcTemplate, String jobName) {
        return jdbcTemplate.queryForObject(
                "select lock_owner from stock_batch_job_lock where job_name = ?",
                String.class,
                jobName
        );
    }

    private LocalDateTime lockedUntil(JdbcTemplate jdbcTemplate, String jobName) {
        return jdbcTemplate.queryForObject(
                "select locked_until from stock_batch_job_lock where job_name = ?",
                LocalDateTime.class,
                jobName
        );
    }
}
