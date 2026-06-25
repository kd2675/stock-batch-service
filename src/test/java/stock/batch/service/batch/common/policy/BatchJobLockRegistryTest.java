package stock.batch.service.batch.common.policy;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.UUID;

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
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:batch_job_lock_registry_test_%s;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false".formatted(UUID.randomUUID()));
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.execute("""
                create table if not exists stock_batch_job_lock (
                  job_name varchar(100) not null primary key,
                  lock_owner varchar(128) not null,
                  locked_until timestamp not null,
                  created_at timestamp not null,
                  updated_at timestamp not null,
                  constraint chk_stock_batch_job_lock_name check (job_name <> ''),
                  constraint chk_stock_batch_job_lock_owner check (lock_owner <> '')
                )
                """);
        return template;
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
