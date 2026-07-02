package stock.batch.service.batch.common.support;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import stock.batch.service.batch.common.policy.BatchJobLockRegistry;
import stock.batch.service.batch.common.policy.BatchJobRuntimeControl;
import stock.batch.service.batch.config.BatchRepositoryDataSourceConfig;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class StockBatchJobRepositoryIntegrationTest {

    @Autowired
    private StockBatchJobLauncher stockBatchJobLauncher;

    @Autowired
    private BatchJobRuntimeControl batchJobRuntimeControl;

    @Autowired
    private BatchJobLockRegistry batchJobLockRegistry;

    @Autowired
    private StockBatchJobRepositoryRecorder stockBatchJobRepositoryRecorder;

    @Autowired
    @Qualifier(BatchRepositoryDataSourceConfig.BATCH_METADATA_DATA_SOURCE)
    private DataSource batchMetadataDataSource;

    @Autowired
    @Qualifier(BatchRepositoryDataSourceConfig.BUSINESS_DATA_SOURCE)
    private DataSource businessDataSource;

    private JdbcTemplate batchMetadataJdbcTemplate() {
        return new JdbcTemplate(batchMetadataDataSource);
    }

    private JdbcTemplate businessJdbcTemplate() {
        return new JdbcTemplate(businessDataSource);
    }

    @Test
    void springBatchMetadataTables_areStoredInSeparateRepositoryDataSource() throws SQLException {
        assertThat(tableExists(businessDataSource, "BATCH_JOB_INSTANCE")).isFalse();
        assertThat(tableExists(batchMetadataDataSource, "BATCH_JOB_INSTANCE")).isTrue();
    }

    @Test
    void runtimeControlAndLockTables_areStoredInBusinessDataSource() throws SQLException {
        String jobName = "datasource-boundary-test";

        batchJobRuntimeControl.update(jobName, true, false, "TEST");
        boolean acquired = batchJobLockRegistry.tryAcquire(jobName, LocalDateTime.now());

        assertThat(acquired).isTrue();
        assertThat(tableExists(businessDataSource, "stock_batch_job_control")).isTrue();
        assertThat(tableExists(businessDataSource, "stock_batch_job_lock")).isTrue();
        assertThat(tableExists(batchMetadataDataSource, "stock_batch_job_control")).isFalse();
        assertThat(tableExists(batchMetadataDataSource, "stock_batch_job_lock")).isFalse();
        assertThat(businessJdbcTemplate().queryForObject(
                "select count(*) from stock_batch_job_control where job_name = ?",
                Long.class,
                jobName
        )).isEqualTo(1L);
        assertThat(businessJdbcTemplate().queryForObject(
                "select count(*) from stock_batch_job_lock where job_name = ?",
                Long.class,
                jobName
        )).isEqualTo(1L);
    }

    @Test
    void refreshMarketData_recordsJobAndStepExecutionInSpringBatchRepository() {
        var response = stockBatchJobLauncher.refreshMarketData();

        assertThat(response.status()).isEqualTo("COMPLETED");
        Long jobExecutionId = latestJobExecutionId("market-data-refresh");
        Long jobExecutionCount = batchMetadataJdbcTemplate().queryForObject(
                """
                select count(*)
                from BATCH_JOB_INSTANCE ji
                join BATCH_JOB_EXECUTION je on je.JOB_INSTANCE_ID = ji.JOB_INSTANCE_ID
                where ji.JOB_NAME = ?
                  and je.STATUS = 'COMPLETED'
                """,
                Long.class,
                "market-data-refresh"
        );
        Long stepExecutionCount = batchMetadataJdbcTemplate().queryForObject(
                """
                select count(*)
                from BATCH_STEP_EXECUTION se
                join BATCH_JOB_EXECUTION je on je.JOB_EXECUTION_ID = se.JOB_EXECUTION_ID
                join BATCH_JOB_INSTANCE ji on ji.JOB_INSTANCE_ID = je.JOB_INSTANCE_ID
                where ji.JOB_NAME = ?
                  and se.STEP_NAME = ?
                  and se.STATUS = 'COMPLETED'
                """,
                Long.class,
                "market-data-refresh",
                "market-data-refreshStep"
        );

        assertThat(jobExecutionCount).isPositive();
        assertThat(stepExecutionCount).isPositive();
        assertThat(jobParameterNames(jobExecutionId)).contains(
                "businessDate",
                "jobMode",
                "runId",
                "requestId",
                "triggeredAt",
                "triggeredBy"
        );
        assertThat(jobExitMessage(jobExecutionId)).contains("processedCount=" + response.processedCount());
        assertThat(stepExitMessage(jobExecutionId)).contains("processedCount=" + response.processedCount());
        assertThat(stepWriteCount(jobExecutionId)).isEqualTo(response.processedCount());
        assertThat(stepCommitCount(jobExecutionId)).isEqualTo(1L);
    }

    @Test
    void start_recordsBusinessDateFromSimulationClockAndExpectedIdentifyingParameters() {
        String jobName = "metadata-parameter-test-" + UUID.randomUUID();
        LocalDateTime startedAt = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        StockBatchJob job = new TestStockBatchJob(jobName, "parameter-mode", () -> 0);
        setSimulationDate(LocalDate.of(2026, 1, 2));

        StockBatchJobExecutionRecord record = stockBatchJobRepositoryRecorder.start(job, startedAt);
        stockBatchJobRepositoryRecorder.complete(record, 0, startedAt.plusSeconds(1));

        Long jobExecutionId = latestJobExecutionId(jobName);
        assertThat(jobParameterValues(jobExecutionId)).containsEntry("businessDate", "2026-01-02");
        assertThat(jobParameterValues(jobExecutionId)).containsEntry("jobMode", "parameter-mode");
        assertThat(jobParameterIdentifyingFlags(jobExecutionId)).containsEntry("businessDate", "Y");
        assertThat(jobParameterIdentifyingFlags(jobExecutionId)).containsEntry("jobMode", "Y");
        assertThat(jobParameterIdentifyingFlags(jobExecutionId)).containsEntry("runId", "Y");
        assertThat(jobParameterIdentifyingFlags(jobExecutionId)).containsEntry("requestId", "N");
        assertThat(jobParameterIdentifyingFlags(jobExecutionId)).containsEntry("triggeredAt", "N");
        assertThat(jobParameterIdentifyingFlags(jobExecutionId)).containsEntry("triggeredBy", "N");
    }

    @Test
    void start_recordsRepeatedSameBusinessDateAndModeAsDistinctJobInstances() {
        String jobName = "metadata-repeated-run-test-" + UUID.randomUUID();
        LocalDateTime startedAt = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        StockBatchJob job = new TestStockBatchJob(jobName, "repeated-mode", () -> 0);

        StockBatchJobExecutionRecord firstRecord = stockBatchJobRepositoryRecorder.start(job, startedAt);
        stockBatchJobRepositoryRecorder.complete(firstRecord, 0, startedAt.plusSeconds(1));
        StockBatchJobExecutionRecord secondRecord = stockBatchJobRepositoryRecorder.start(job, startedAt);
        stockBatchJobRepositoryRecorder.complete(secondRecord, 0, startedAt.plusSeconds(2));

        assertThat(jobInstanceCount(jobName)).isEqualTo(2L);
        assertThat(completedJobExecutionCount(jobName)).isEqualTo(2L);
        assertThat(runIdParameterValues(jobName)).hasSize(2).doesNotHaveDuplicates();
        assertThat(identifyingRunIdParameterCount(jobName)).isEqualTo(2L);
    }

    @Test
    void failedJob_recordsFailedJobAndStepExecutionInSpringBatchRepository() {
        String jobName = "metadata-failed-test-" + UUID.randomUUID();
        StockBatchJobRunner runner = new StockBatchJobRunner(batchJobLockRegistry, stockBatchJobRepositoryRecorder);

        var response = runner.run(new TestStockBatchJob(jobName, "test-failure", () -> {
            throw new IllegalStateException("metadata failure");
        }));

        assertThat(response.status()).isEqualTo("FAILED");
        Long jobExecutionId = latestJobExecutionId(jobName);
        assertThat(jobExecutionStatus(jobExecutionId)).isEqualTo("FAILED");
        assertThat(jobExitCode(jobExecutionId)).isEqualTo("FAILED");
        assertThat(jobExitMessage(jobExecutionId)).contains("metadata failure");
        assertThat(stepExecutionStatus(jobExecutionId)).isEqualTo("FAILED");
        assertThat(stepExitCode(jobExecutionId)).isEqualTo("FAILED");
        assertThat(stepExitMessage(jobExecutionId)).contains("metadata failure");
    }

    @Test
    void skippedJob_recordsNoopJobAndStepExecutionInSpringBatchRepository() {
        String jobName = "metadata-skipped-test-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        boolean acquired = batchJobLockRegistry.tryAcquire(jobName, now);
        StockBatchJobRunner runner = new StockBatchJobRunner(batchJobLockRegistry, stockBatchJobRepositoryRecorder);

        try {
            var response = runner.run(new TestStockBatchJob(jobName, "test-skip", () -> 1));

            assertThat(acquired).isTrue();
            assertThat(response.status()).isEqualTo("SKIPPED");
            Long jobExecutionId = latestJobExecutionId(jobName);
            assertThat(jobExecutionStatus(jobExecutionId)).isEqualTo("COMPLETED");
            assertThat(jobExitCode(jobExecutionId)).isEqualTo("NOOP");
            assertThat(jobExitMessage(jobExecutionId)).contains("Job is already running");
            assertThat(stepExecutionStatus(jobExecutionId)).isEqualTo("COMPLETED");
            assertThat(stepExitCode(jobExecutionId)).isEqualTo("NOOP");
            assertThat(stepExitMessage(jobExecutionId)).contains("Job is already running");
            assertThat(stepWriteCount(jobExecutionId)).isZero();
        } finally {
            batchJobLockRegistry.release(jobName);
        }
    }

    private boolean tableExists(DataSource dataSource, String tableName) throws SQLException {
        try (var connection = dataSource.getConnection();
             var tables = connection.getMetaData().getTables(null, null, tableName, new String[]{"TABLE"})) {
            return tables.next();
        }
    }

    private Long latestJobExecutionId(String jobName) {
        return batchMetadataJdbcTemplate().queryForObject(
                """
                select max(je.JOB_EXECUTION_ID)
                  from BATCH_JOB_INSTANCE ji
                  join BATCH_JOB_EXECUTION je on je.JOB_INSTANCE_ID = ji.JOB_INSTANCE_ID
                 where ji.JOB_NAME = ?
                """,
                Long.class,
                jobName
        );
    }

    private void setSimulationDate(LocalDate simulationDate) {
        businessJdbcTemplate().update(
                """
                merge into stock_simulation_clock(
                    clock_id,
                    base_simulation_date,
                    real_seconds_per_simulation_day,
                    accumulated_real_seconds,
                    running,
                    last_started_at,
                    last_heartbeat_at,
                    timezone,
                    created_at,
                    updated_at
                )
                key(clock_id)
                values ('DEFAULT', ?, 7200, 0, false, null, null, 'Asia/Seoul', ?, ?)
                """,
                simulationDate,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private List<String> jobParameterNames(Long jobExecutionId) {
        return batchMetadataJdbcTemplate().queryForList(
                """
                select PARAMETER_NAME
                  from BATCH_JOB_EXECUTION_PARAMS
                 where JOB_EXECUTION_ID = ?
                 order by PARAMETER_NAME
                """,
                String.class,
                jobExecutionId
        );
    }

    private Map<String, String> jobParameterValues(Long jobExecutionId) {
        return batchMetadataJdbcTemplate().query(
                """
                select PARAMETER_NAME, PARAMETER_VALUE
                  from BATCH_JOB_EXECUTION_PARAMS
                 where JOB_EXECUTION_ID = ?
                """,
                rs -> {
                    Map<String, String> values = new java.util.HashMap<>();
                    while (rs.next()) {
                        values.put(rs.getString("PARAMETER_NAME"), rs.getString("PARAMETER_VALUE"));
                    }
                    return values;
                },
                jobExecutionId
        );
    }

    private Map<String, String> jobParameterIdentifyingFlags(Long jobExecutionId) {
        return batchMetadataJdbcTemplate().query(
                """
                select PARAMETER_NAME, IDENTIFYING
                  from BATCH_JOB_EXECUTION_PARAMS
                 where JOB_EXECUTION_ID = ?
                """,
                rs -> {
                    Map<String, String> values = new java.util.HashMap<>();
                    while (rs.next()) {
                        values.put(rs.getString("PARAMETER_NAME"), rs.getString("IDENTIFYING"));
                    }
                    return values;
                },
                jobExecutionId
        );
    }

    private Long jobInstanceCount(String jobName) {
        return batchMetadataJdbcTemplate().queryForObject(
                """
                select count(*)
                  from BATCH_JOB_INSTANCE
                 where JOB_NAME = ?
                """,
                Long.class,
                jobName
        );
    }

    private Long completedJobExecutionCount(String jobName) {
        return batchMetadataJdbcTemplate().queryForObject(
                """
                select count(*)
                  from BATCH_JOB_INSTANCE ji
                  join BATCH_JOB_EXECUTION je on je.JOB_INSTANCE_ID = ji.JOB_INSTANCE_ID
                 where ji.JOB_NAME = ?
                   and je.STATUS = 'COMPLETED'
                """,
                Long.class,
                jobName
        );
    }

    private List<String> runIdParameterValues(String jobName) {
        return batchMetadataJdbcTemplate().queryForList(
                """
                select p.PARAMETER_VALUE
                  from BATCH_JOB_INSTANCE ji
                  join BATCH_JOB_EXECUTION je on je.JOB_INSTANCE_ID = ji.JOB_INSTANCE_ID
                  join BATCH_JOB_EXECUTION_PARAMS p on p.JOB_EXECUTION_ID = je.JOB_EXECUTION_ID
                 where ji.JOB_NAME = ?
                   and p.PARAMETER_NAME = 'runId'
                 order by p.PARAMETER_VALUE
                """,
                String.class,
                jobName
        );
    }

    private Long identifyingRunIdParameterCount(String jobName) {
        return batchMetadataJdbcTemplate().queryForObject(
                """
                select count(*)
                  from BATCH_JOB_INSTANCE ji
                  join BATCH_JOB_EXECUTION je on je.JOB_INSTANCE_ID = ji.JOB_INSTANCE_ID
                  join BATCH_JOB_EXECUTION_PARAMS p on p.JOB_EXECUTION_ID = je.JOB_EXECUTION_ID
                 where ji.JOB_NAME = ?
                   and p.PARAMETER_NAME = 'runId'
                   and p.IDENTIFYING = 'Y'
                """,
                Long.class,
                jobName
        );
    }

    private String jobExecutionStatus(Long jobExecutionId) {
        return batchMetadataJdbcTemplate().queryForObject(
                "select STATUS from BATCH_JOB_EXECUTION where JOB_EXECUTION_ID = ?",
                String.class,
                jobExecutionId
        );
    }

    private String jobExitCode(Long jobExecutionId) {
        return batchMetadataJdbcTemplate().queryForObject(
                "select EXIT_CODE from BATCH_JOB_EXECUTION where JOB_EXECUTION_ID = ?",
                String.class,
                jobExecutionId
        );
    }

    private String jobExitMessage(Long jobExecutionId) {
        return batchMetadataJdbcTemplate().queryForObject(
                "select EXIT_MESSAGE from BATCH_JOB_EXECUTION where JOB_EXECUTION_ID = ?",
                String.class,
                jobExecutionId
        );
    }

    private String stepExecutionStatus(Long jobExecutionId) {
        return batchMetadataJdbcTemplate().queryForObject(
                "select STATUS from BATCH_STEP_EXECUTION where JOB_EXECUTION_ID = ?",
                String.class,
                jobExecutionId
        );
    }

    private String stepExitMessage(Long jobExecutionId) {
        return batchMetadataJdbcTemplate().queryForObject(
                "select EXIT_MESSAGE from BATCH_STEP_EXECUTION where JOB_EXECUTION_ID = ?",
                String.class,
                jobExecutionId
        );
    }

    private Long stepWriteCount(Long jobExecutionId) {
        return batchMetadataJdbcTemplate().queryForObject(
                "select WRITE_COUNT from BATCH_STEP_EXECUTION where JOB_EXECUTION_ID = ?",
                Long.class,
                jobExecutionId
        );
    }

    private Long stepCommitCount(Long jobExecutionId) {
        return batchMetadataJdbcTemplate().queryForObject(
                "select COMMIT_COUNT from BATCH_STEP_EXECUTION where JOB_EXECUTION_ID = ?",
                Long.class,
                jobExecutionId
        );
    }

    private String stepExitCode(Long jobExecutionId) {
        return batchMetadataJdbcTemplate().queryForObject(
                "select EXIT_CODE from BATCH_STEP_EXECUTION where JOB_EXECUTION_ID = ?",
                String.class,
                jobExecutionId
        );
    }

    private record TestStockBatchJob(String jobName, String executionMode, JobAction action) implements StockBatchJob {

        @Override
        public int run() {
            return action.run();
        }
    }

    @FunctionalInterface
    private interface JobAction {

        int run();
    }
}
