package stock.batch.service.batch.metadata.biz;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameter;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import stock.batch.service.marketclose.biz.MarketSessionFenceService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BatchMetadataRetentionServiceTest {

    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZONE_ID);
    private static final LocalDateTime OLD_END_TIME = LocalDateTime.of(2026, 6, 1, 12, 0);

    @Mock
    private JobRepository jobRepository;

    @Mock
    private MarketSessionFenceService marketSessionFenceService;

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource h2 = new DriverManagerDataSource();
        h2.setDriverClassName("org.h2.Driver");
        h2.setUrl(
                "jdbc:h2:mem:batch_metadata_retention_"
                        + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
        );
        h2.setUsername("sa");
        h2.setPassword("");
        dataSource = h2;
        new ResourceDatabasePopulator(new ClassPathResource("db/schema/batch-metadata-h2.sql"))
                .execute(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);
        lenient().when(marketSessionFenceService.hasOpenMarket()).thenReturn(false);
    }

    @Test
    void archiveAndOptionallyPurgeCompletedInstances_oldCompletedInstance_archivesSummary() {
        insertCompletedInstance(1L, 10L, "test-job", OLD_END_TIME, 1);
        stubCompletedInstance(1L, 10L, "test-job", OLD_END_TIME, 1);

        BatchMetadataRetentionService.RetentionResult result = service(false, "", 20, 200)
                .archiveAndOptionallyPurgeCompletedInstances();

        assertThat(result).isEqualTo(new BatchMetadataRetentionService.RetentionResult(
                1,
                1,
                1,
                1,
                0,
                0,
                0,
                LocalDateTime.now(CLOCK).minusDays(30)
        ));
    }

    @Test
    void archiveAndOptionallyPurgeCompletedInstances_archiveOnly_keepsJobRepositoryInstance() {
        insertCompletedInstance(1L, 10L, "test-job", OLD_END_TIME, 1);
        JobInstance instance = stubCompletedInstance(1L, 10L, "test-job", OLD_END_TIME, 1);

        service(false, "", 20, 200).archiveAndOptionallyPurgeCompletedInstances();

        verify(jobRepository, never()).deleteJobInstance(instance);
    }

    @Test
    void archiveAndOptionallyPurgeCompletedInstances_secondArchiveRun_skipsArchivedInstance() {
        insertCompletedInstance(1L, 10L, "test-job", OLD_END_TIME, 1);
        stubCompletedInstance(1L, 10L, "test-job", OLD_END_TIME, 1);
        BatchMetadataRetentionService service = service(false, "", 20, 200);
        service.archiveAndOptionallyPurgeCompletedInstances();

        BatchMetadataRetentionService.RetentionResult second = service.archiveAndOptionallyPurgeCompletedInstances();

        assertThat(second.candidateInstances()).isZero();
    }

    @Test
    void archiveAndOptionallyPurgeCompletedInstances_purgeAllowlistedJob_deletesThroughRepository() {
        insertCompletedInstance(1L, 10L, "test-job", OLD_END_TIME, 1);
        JobInstance instance = stubCompletedInstance(1L, 10L, "test-job", OLD_END_TIME, 1);

        service(true, "test-job", 20, 200).archiveAndOptionallyPurgeCompletedInstances();

        verify(jobRepository).deleteJobInstance(instance);
    }

    @Test
    void archiveAndOptionallyPurgeCompletedInstances_purgeAllowlistedJob_marksArchivePurged() {
        insertCompletedInstance(1L, 10L, "test-job", OLD_END_TIME, 1);
        stubCompletedInstance(1L, 10L, "test-job", OLD_END_TIME, 1);

        service(true, "test-job", 20, 200).archiveAndOptionallyPurgeCompletedInstances();

        assertThat(jdbcTemplate.queryForObject(
                "select PURGED_AT from STOCK_BATCH_JOB_METADATA_ARCHIVE where JOB_EXECUTION_ID = 10",
                LocalDateTime.class
        )).isEqualTo(LocalDateTime.now(CLOCK));
    }

    @Test
    void archiveAndOptionallyPurgeCompletedInstances_failedInstance_doesNotLoadRepositoryEntity() {
        insertExecution(1L, 10L, "test-job", OLD_END_TIME, "FAILED");

        BatchMetadataRetentionService.RetentionResult result = service(false, "", 20, 200)
                .archiveAndOptionallyPurgeCompletedInstances();

        assertThat(result.candidateInstances()).isZero();
        verifyNoInteractions(jobRepository);
    }

    @Test
    void archiveAndOptionallyPurgeCompletedInstances_mixedNullStatusExecution_excludesWholeInstance() {
        insertCompletedInstance(1L, 10L, "test-job", OLD_END_TIME, 0);
        insertJobExecution(1L, 11L, OLD_END_TIME, null);

        BatchMetadataRetentionService.RetentionResult result = service(false, "", 20, 200)
                .archiveAndOptionallyPurgeCompletedInstances();

        assertThat(result.candidateInstances()).isZero();
        verifyNoInteractions(jobRepository);
    }

    @Test
    void archiveAndOptionallyPurgeCompletedInstances_oversizedStepHistory_skipsBeforeEntityLoad() {
        insertCompletedInstance(1L, 10L, "test-job", OLD_END_TIME, 3);

        BatchMetadataRetentionService.RetentionResult result = service(false, "", 20, 2)
                .archiveAndOptionallyPurgeCompletedInstances();

        assertThat(result.skippedOversizedInstances()).isEqualTo(1);
        verifyNoInteractions(jobRepository);
    }

    @Test
    void archiveAndOptionallyPurgeCompletedInstances_marketOpen_rejectsRetention() {
        when(marketSessionFenceService.hasOpenMarket()).thenReturn(true);

        assertThatThrownBy(() -> service(false, "", 20, 200)
                .archiveAndOptionallyPurgeCompletedInstances())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prohibited while a stock market is open");
    }

    @Test
    void constructor_purgeWithoutAllowlist_rejectsUnsafeConfiguration() {
        assertThatThrownBy(() -> service(true, "", 20, 200))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("purge-job-names");
    }

    private BatchMetadataRetentionService service(
            boolean purgeEnabled,
            String purgeJobNames,
            int maxExecutions,
            int maxSteps
    ) {
        return new BatchMetadataRetentionService(
                jobRepository,
                marketSessionFenceService,
                dataSource,
                new DataSourceTransactionManager(dataSource),
                new SimpleMeterRegistry(),
                CLOCK,
                30,
                25,
                maxExecutions,
                maxSteps,
                purgeEnabled,
                purgeJobNames
        );
    }

    private void insertCompletedInstance(
            long instanceId,
            long executionId,
            String jobName,
            LocalDateTime endTime,
            int stepCount
    ) {
        insertExecution(instanceId, executionId, jobName, endTime, "COMPLETED");
        for (int index = 1; index <= stepCount; index++) {
            jdbcTemplate.update(
                    """
                    insert into BATCH_STEP_EXECUTION(
                        STEP_EXECUTION_ID,
                        VERSION,
                        STEP_NAME,
                        JOB_EXECUTION_ID,
                        CREATE_TIME,
                        START_TIME,
                        END_TIME,
                        STATUS,
                        COMMIT_COUNT,
                        READ_COUNT,
                        FILTER_COUNT,
                        WRITE_COUNT,
                        READ_SKIP_COUNT,
                        WRITE_SKIP_COUNT,
                        PROCESS_SKIP_COUNT,
                        ROLLBACK_COUNT,
                        EXIT_CODE,
                        EXIT_MESSAGE,
                        LAST_UPDATED
                    )
                    values (?, 0, ?, ?, ?, ?, ?, 'COMPLETED', 1, 2, 0, 2, 0, 0, 0, 0, 'COMPLETED', '', ?)
                    """,
                    executionId * 100 + index,
                    "step-" + index,
                    executionId,
                    endTime.minusMinutes(2),
                    endTime.minusMinutes(1),
                    endTime,
                    endTime
            );
        }
    }

    private void insertExecution(
            long instanceId,
            long executionId,
            String jobName,
            LocalDateTime endTime,
            String status
    ) {
        jdbcTemplate.update(
                "insert into BATCH_JOB_INSTANCE(JOB_INSTANCE_ID, VERSION, JOB_NAME, JOB_KEY) values (?, 0, ?, ?)",
                instanceId,
                jobName,
                "job-key-" + instanceId
        );
        insertJobExecution(instanceId, executionId, endTime, status);
    }

    private void insertJobExecution(
            long instanceId,
            long executionId,
            LocalDateTime endTime,
            String status
    ) {
        jdbcTemplate.update(
                """
                insert into BATCH_JOB_EXECUTION(
                    JOB_EXECUTION_ID,
                    VERSION,
                    JOB_INSTANCE_ID,
                    CREATE_TIME,
                    START_TIME,
                    END_TIME,
                    STATUS,
                    EXIT_CODE,
                    EXIT_MESSAGE,
                    LAST_UPDATED
                )
                values (?, 0, ?, ?, ?, ?, ?, ?, '', ?)
                """,
                executionId,
                instanceId,
                endTime.minusMinutes(2),
                endTime.minusMinutes(1),
                endTime,
                status,
                status,
                endTime
        );
    }

    private JobInstance stubCompletedInstance(
            long instanceId,
            long executionId,
            String jobName,
            LocalDateTime endTime,
            int stepCount
    ) {
        JobInstance instance = new JobInstance(instanceId, jobName);
        JobParameters parameters = new JobParameters(Set.of(
                new JobParameter<>("businessDate", "2026-06-01", String.class, true)
        ));
        JobExecution execution = new JobExecution(executionId, instance, parameters);
        execution.setCreateTime(endTime.minusMinutes(2));
        execution.setStartTime(endTime.minusMinutes(1));
        execution.setEndTime(endTime);
        execution.setStatus(BatchStatus.COMPLETED);
        execution.setExitStatus(ExitStatus.COMPLETED);
        for (int index = 1; index <= stepCount; index++) {
            StepExecution step = new StepExecution(executionId * 100 + index, "step-" + index, execution);
            step.setStatus(BatchStatus.COMPLETED);
            step.setReadCount(2);
            step.setWriteCount(2);
            step.setCommitCount(1);
            step.setEndTime(endTime);
            execution.addStepExecution(step);
        }
        when(jobRepository.getJobInstance(instanceId)).thenReturn(instance);
        when(jobRepository.getJobExecutions(instance)).thenReturn(List.of(execution));
        return instance;
    }
}
