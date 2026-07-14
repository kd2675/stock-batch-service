package stock.batch.service.batch.common.support;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import stock.batch.service.testsupport.BatchTestDatabaseFactory;

import static org.assertj.core.api.Assertions.assertThat;

class StockBatchStaleExecutionRecoveryTest {

    @Test
    void recover_onlyFailsOpenExecutionsForLockedNativeJob() {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        LocalDateTime recoveredAt = LocalDateTime.of(2026, 7, 14, 10, 0);
        insertExecution(jdbcTemplate, 1L, 10L, "portfolio-settlement", "STARTED", recoveredAt.minusMinutes(3));
        insertStep(jdbcTemplate, 100L, 10L, "portfolio-settlement-step", "STARTED", recoveredAt.minusMinutes(3));
        insertExecution(jdbcTemplate, 2L, 20L, "portfolio-settlement", "STOPPING", recoveredAt.minusMinutes(2));
        insertStep(jdbcTemplate, 200L, 20L, "portfolio-settlement-step", "STOPPING", recoveredAt.minusMinutes(2));
        insertExecution(jdbcTemplate, 3L, 30L, "corporate-actions", "STARTED", recoveredAt.minusMinutes(1));
        insertStep(jdbcTemplate, 300L, 30L, "apply-due-corporate-actions-step", "STARTED", recoveredAt.minusMinutes(1));
        insertExecution(jdbcTemplate, 4L, 40L, "portfolio-settlement", "STARTED", recoveredAt.plusSeconds(1));
        insertStep(jdbcTemplate, 400L, 40L, "portfolio-settlement-step", "STARTED", recoveredAt.plusSeconds(1));
        StockBatchStaleExecutionRecovery recovery = new StockBatchStaleExecutionRecovery(
                jdbcTemplate,
                new DataSourceTransactionManager(jdbcTemplate.getDataSource())
        );

        int recoveredCount = recovery.recover("portfolio-settlement", recoveredAt);

        assertThat(recoveredCount).isEqualTo(2);
        assertThat(status(jdbcTemplate, "BATCH_JOB_EXECUTION", "JOB_EXECUTION_ID", 10L)).isEqualTo("FAILED");
        assertThat(status(jdbcTemplate, "BATCH_STEP_EXECUTION", "JOB_EXECUTION_ID", 10L)).isEqualTo("FAILED");
        assertThat(status(jdbcTemplate, "BATCH_JOB_EXECUTION", "JOB_EXECUTION_ID", 20L)).isEqualTo("FAILED");
        assertThat(status(jdbcTemplate, "BATCH_JOB_EXECUTION", "JOB_EXECUTION_ID", 30L)).isEqualTo("STARTED");
        assertThat(status(jdbcTemplate, "BATCH_JOB_EXECUTION", "JOB_EXECUTION_ID", 40L)).isEqualTo("STARTED");
    }

    private JdbcTemplate createJdbcTemplate() {
        var dataSource = BatchTestDatabaseFactory.createDataSource("stock_batch_stale_execution_recovery_test");
        new ResourceDatabasePopulator(new ClassPathResource("db/schema/batch-metadata-h2.sql")).execute(dataSource);
        return new JdbcTemplate(dataSource);
    }

    private void insertExecution(
            JdbcTemplate jdbcTemplate,
            long instanceId,
            long executionId,
            String jobName,
            String status,
            LocalDateTime lastUpdated
    ) {
        jdbcTemplate.update(
                "insert into BATCH_JOB_INSTANCE(JOB_INSTANCE_ID, VERSION, JOB_NAME, JOB_KEY) values (?, 0, ?, ?)",
                instanceId,
                jobName,
                jobName + instanceId
        );
        jdbcTemplate.update(
                """
                insert into BATCH_JOB_EXECUTION(
                    JOB_EXECUTION_ID, VERSION, JOB_INSTANCE_ID, CREATE_TIME, START_TIME,
                    END_TIME, STATUS, EXIT_CODE, EXIT_MESSAGE, LAST_UPDATED
                ) values (?, 0, ?, ?, ?, null, ?, ?, ?, ?)
                """,
                executionId,
                instanceId,
                lastUpdated,
                lastUpdated,
                status,
                status,
                status,
                lastUpdated
        );
    }

    private void insertStep(
            JdbcTemplate jdbcTemplate,
            long stepExecutionId,
            long jobExecutionId,
            String stepName,
            String status,
            LocalDateTime lastUpdated
    ) {
        jdbcTemplate.update(
                """
                insert into BATCH_STEP_EXECUTION(
                    STEP_EXECUTION_ID, VERSION, STEP_NAME, JOB_EXECUTION_ID, CREATE_TIME,
                    START_TIME, END_TIME, STATUS, COMMIT_COUNT, READ_COUNT, FILTER_COUNT,
                    WRITE_COUNT, READ_SKIP_COUNT, WRITE_SKIP_COUNT, PROCESS_SKIP_COUNT,
                    ROLLBACK_COUNT, EXIT_CODE, EXIT_MESSAGE, LAST_UPDATED
                ) values (?, 0, ?, ?, ?, ?, null, ?, 0, 0, 0, 0, 0, 0, 0, 0, ?, ?, ?)
                """,
                stepExecutionId,
                stepName,
                jobExecutionId,
                lastUpdated,
                lastUpdated,
                status,
                status,
                status,
                lastUpdated
        );
    }

    private String status(JdbcTemplate jdbcTemplate, String table, String idColumn, long id) {
        return jdbcTemplate.queryForObject(
                "select STATUS from " + table + " where " + idColumn + " = ?",
                String.class,
                id
        );
    }
}
