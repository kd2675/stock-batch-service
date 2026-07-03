package stock.batch.service.batch.common.support;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import stock.batch.service.testsupport.BatchTestDatabaseFactory;

import static org.assertj.core.api.Assertions.assertThat;

class StockBatchJobRepositoryRecoveryTest {

    @Test
    void recoverStaleExecutions_marksOnlyPreviousOpenExecutionsAsFailed() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        LocalDateTime recoveredAt = LocalDateTime.of(2026, 7, 3, 10, 0);
        insertJobExecution(jdbcTemplate, 1L, 10L, "stale-started", "STARTED", recoveredAt.minusMinutes(10), null);
        insertStepExecution(jdbcTemplate, 100L, 10L, "stale-startedStep", "STARTED", recoveredAt.minusMinutes(10), null);
        insertJobExecution(jdbcTemplate, 2L, 20L, "stale-starting", "STARTING", null, null);
        insertStepExecution(jdbcTemplate, 200L, 20L, "stale-startingStep", "STARTING", recoveredAt.minusMinutes(8), null);
        insertJobExecution(
                jdbcTemplate,
                3L,
                30L,
                "completed",
                "COMPLETED",
                recoveredAt.minusMinutes(7),
                recoveredAt.minusMinutes(6)
        );
        insertStepExecution(
                jdbcTemplate,
                300L,
                30L,
                "completedStep",
                "COMPLETED",
                recoveredAt.minusMinutes(7),
                recoveredAt.minusMinutes(6)
        );
        insertJobExecution(jdbcTemplate, 4L, 40L, "current-started", "STARTED", recoveredAt.plusSeconds(1), null);
        insertStepExecution(jdbcTemplate, 400L, 40L, "current-startedStep", "STARTED", recoveredAt.plusSeconds(1), null);

        StockBatchJobRepositoryRecovery recovery = new StockBatchJobRepositoryRecovery(
                jdbcTemplate,
                new DataSourceTransactionManager(jdbcTemplate.getDataSource())
        );

        int recoveredCount = recovery.recoverStaleExecutions(recoveredAt);

        assertThat(recoveredCount).isEqualTo(2);
        assertThat(jobStatus(jdbcTemplate, 10L)).isEqualTo("FAILED");
        assertThat(jobStatus(jdbcTemplate, 20L)).isEqualTo("FAILED");
        assertThat(stepStatus(jdbcTemplate, 10L)).isEqualTo("FAILED");
        assertThat(stepStatus(jdbcTemplate, 20L)).isEqualTo("FAILED");
        assertThat(jobExitMessage(jdbcTemplate, 10L)).contains(StockBatchJobRepositoryRecovery.RECOVERY_EXIT_MESSAGE);
        assertThat(jobEndTime(jdbcTemplate, 10L)).isEqualTo(recoveredAt);
        assertThat(jobVersion(jdbcTemplate, 10L)).isEqualTo(1L);
        assertThat(stepVersion(jdbcTemplate, 10L)).isEqualTo(1L);
        assertThat(jobStatus(jdbcTemplate, 30L)).isEqualTo("COMPLETED");
        assertThat(jobStatus(jdbcTemplate, 40L)).isEqualTo("STARTED");
        assertThat(stepStatus(jdbcTemplate, 40L)).isEqualTo("STARTED");
    }

    private JdbcTemplate createJdbcTemplate() {
        var dataSource = BatchTestDatabaseFactory.createDataSource("stock_batch_job_repository_recovery_test");
        new ResourceDatabasePopulator(new ClassPathResource("db/schema/batch-metadata-h2.sql")).execute(dataSource);
        return new JdbcTemplate(dataSource);
    }

    private void insertJobExecution(
            JdbcTemplate jdbcTemplate,
            Long jobInstanceId,
            Long jobExecutionId,
            String jobName,
            String status,
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {
        jdbcTemplate.update(
                """
                insert into BATCH_JOB_INSTANCE(JOB_INSTANCE_ID, VERSION, JOB_NAME, JOB_KEY)
                values (?, 0, ?, ?)
                """,
                jobInstanceId,
                jobName,
                jobName
        );
        jdbcTemplate.update(
                """
                insert into BATCH_JOB_EXECUTION(
                    JOB_EXECUTION_ID, VERSION, JOB_INSTANCE_ID, CREATE_TIME, START_TIME,
                    END_TIME, STATUS, EXIT_CODE, EXIT_MESSAGE, LAST_UPDATED
                )
                values (?, 0, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                jobExecutionId,
                jobInstanceId,
                LocalDateTime.of(2026, 7, 3, 9, 0),
                startTime,
                endTime,
                status,
                status,
                status,
                LocalDateTime.of(2026, 7, 3, 9, 0)
        );
    }

    private void insertStepExecution(
            JdbcTemplate jdbcTemplate,
            Long stepExecutionId,
            Long jobExecutionId,
            String stepName,
            String status,
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {
        jdbcTemplate.update(
                """
                insert into BATCH_STEP_EXECUTION(
                    STEP_EXECUTION_ID, VERSION, STEP_NAME, JOB_EXECUTION_ID, CREATE_TIME,
                    START_TIME, END_TIME, STATUS, COMMIT_COUNT, READ_COUNT, FILTER_COUNT,
                    WRITE_COUNT, READ_SKIP_COUNT, WRITE_SKIP_COUNT, PROCESS_SKIP_COUNT,
                    ROLLBACK_COUNT, EXIT_CODE, EXIT_MESSAGE, LAST_UPDATED
                )
                values (?, 0, ?, ?, ?, ?, ?, ?, 0, 0, 0, 0, 0, 0, 0, 0, ?, ?, ?)
                """,
                stepExecutionId,
                stepName,
                jobExecutionId,
                LocalDateTime.of(2026, 7, 3, 9, 0),
                startTime,
                endTime,
                status,
                status,
                status,
                LocalDateTime.of(2026, 7, 3, 9, 0)
        );
    }

    private String jobStatus(JdbcTemplate jdbcTemplate, Long jobExecutionId) {
        return jdbcTemplate.queryForObject(
                "select STATUS from BATCH_JOB_EXECUTION where JOB_EXECUTION_ID = ?",
                String.class,
                jobExecutionId
        );
    }

    private String stepStatus(JdbcTemplate jdbcTemplate, Long jobExecutionId) {
        return jdbcTemplate.queryForObject(
                "select STATUS from BATCH_STEP_EXECUTION where JOB_EXECUTION_ID = ?",
                String.class,
                jobExecutionId
        );
    }

    private String jobExitMessage(JdbcTemplate jdbcTemplate, Long jobExecutionId) {
        return jdbcTemplate.queryForObject(
                "select EXIT_MESSAGE from BATCH_JOB_EXECUTION where JOB_EXECUTION_ID = ?",
                String.class,
                jobExecutionId
        );
    }

    private LocalDateTime jobEndTime(JdbcTemplate jdbcTemplate, Long jobExecutionId) {
        return jdbcTemplate.queryForObject(
                "select END_TIME from BATCH_JOB_EXECUTION where JOB_EXECUTION_ID = ?",
                LocalDateTime.class,
                jobExecutionId
        );
    }

    private Long jobVersion(JdbcTemplate jdbcTemplate, Long jobExecutionId) {
        return jdbcTemplate.queryForObject(
                "select VERSION from BATCH_JOB_EXECUTION where JOB_EXECUTION_ID = ?",
                Long.class,
                jobExecutionId
        );
    }

    private Long stepVersion(JdbcTemplate jdbcTemplate, Long jobExecutionId) {
        return jdbcTemplate.queryForObject(
                "select VERSION from BATCH_STEP_EXECUTION where JOB_EXECUTION_ID = ?",
                Long.class,
                jobExecutionId
        );
    }
}
