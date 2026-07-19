package stock.batch.service.batch.common.support;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import stock.batch.service.batch.common.policy.BatchJobLockRegistry;
import stock.batch.service.batch.common.policy.BatchJobRuntimeControl;
import stock.batch.service.batch.config.BatchRepositoryConfig;
import stock.batch.service.batch.config.BatchRepositoryDataSourceConfig;
import stock.batch.service.batch.metadata.biz.BatchMetadataRetentionService;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBatchTest
@SpringBootTest
@ActiveProfiles("test")
@Import(StockBatchJobRepositoryIntegrationTest.NativeRestartJobConfiguration.class)
class StockBatchJobRepositoryIntegrationTest {

    private static final String TEST_JOB_NAME = "native-restart-contract-job";

    @Autowired
    private JobOperatorTestUtils jobOperatorTestUtils;

    @Autowired
    @Qualifier(TEST_JOB_NAME)
    private Job testJob;

    @Autowired
    private NativeRestartState restartState;

    @Autowired
    private StockBatchJobLauncher stockBatchJobLauncher;

    @Autowired
    private StockBatchJobRunner stockBatchJobRunner;

    @Autowired
    private BatchJobRuntimeControl batchJobRuntimeControl;

    @Autowired
    private BatchJobLockRegistry batchJobLockRegistry;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    @Qualifier(BatchRepositoryDataSourceConfig.BATCH_METADATA_TRANSACTION_MANAGER)
    private PlatformTransactionManager batchMetadataTransactionManager;

    @Autowired
    @Qualifier(BatchRepositoryDataSourceConfig.BATCH_METADATA_DATA_SOURCE)
    private DataSource batchMetadataDataSource;

    @Autowired
    @Qualifier(BatchRepositoryDataSourceConfig.BUSINESS_DATA_SOURCE)
    private DataSource businessDataSource;

    @BeforeEach
    void setUp() {
        restartState.reset();
        jobOperatorTestUtils.setJob(testJob);
    }

    @Test
    void springBatchMetadataTables_areStoredInSeparateRepositoryDataSource() throws SQLException {
        assertThat(tableExists(businessDataSource, "BATCH_JOB_INSTANCE")).isFalse();
        assertThat(tableExists(batchMetadataDataSource, "BATCH_JOB_INSTANCE")).isTrue();
    }

    @Test
    void runtimeControlAndLockTables_remainInBusinessDataSource() throws SQLException {
        String jobName = "datasource-boundary-" + UUID.randomUUID();

        batchJobRuntimeControl.update(jobName, true, false, "TEST");
        boolean acquired = batchJobLockRegistry.tryAcquire(jobName, LocalDateTime.now());

        assertThat(acquired).isTrue();
        assertThat(tableExists(businessDataSource, "stock_batch_job_control")).isTrue();
        assertThat(tableExists(businessDataSource, "stock_batch_job_lock")).isTrue();
        assertThat(tableExists(batchMetadataDataSource, "stock_batch_job_control")).isFalse();
        assertThat(tableExists(batchMetadataDataSource, "stock_batch_job_lock")).isFalse();
        batchJobLockRegistry.release(jobName);
    }

    @Test
    void nativeJob_recordsRealStepCountsAndRejectsCompletedInstance() throws Exception {
        JobParameters parameters = parameters("complete-contract");

        JobExecution firstExecution = jobOperatorTestUtils.startJob(parameters);
        assertThatThrownBy(() -> jobOperatorTestUtils.startJob(parameters))
                .isInstanceOf(org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException.class);

        assertThat(firstExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(firstExecution.getStepExecutions()).hasSize(2);
        assertThat(firstExecution.getStepExecutions().stream().mapToLong(step -> step.getWriteCount()).sum())
                .isEqualTo(5L);
        assertThat(firstExecution.getStepExecutions().stream().mapToLong(step -> step.getCommitCount()).sum())
                .isPositive();
        assertThat(restartState.firstStepRuns.get()).isEqualTo(1);
        assertThat(restartState.secondStepRuns.get()).isEqualTo(1);
    }

    @Test
    void failedJob_restartUsesSameInstanceAndSkipsCompletedStep() throws Exception {
        restartState.failSecondStepOnce.set(true);
        JobParameters parameters = parameters("restart-contract");

        JobExecution failedExecution = jobOperatorTestUtils.startJob(parameters);
        JobExecution restartedExecution = jobOperatorTestUtils.startJob(parameters);

        assertThat(failedExecution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(restartedExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(restartedExecution.getJobInstanceId()).isEqualTo(failedExecution.getJobInstanceId());
        assertThat(restartedExecution.getId()).isNotEqualTo(failedExecution.getId());
        assertThat(restartState.firstStepRuns.get()).isEqualTo(1);
        assertThat(restartState.secondStepRuns.get()).isEqualTo(2);
    }

    @Test
    void staleStartedExecution_isRecoveredAfterBusinessLockAcquisitionAndRestarted() throws Exception {
        restartState.failSecondStepOnce.set(true);
        JobParameters parameters = parameters("stale-restart-contract");
        JobExecution interruptedExecution = jobOperatorTestUtils.startJob(parameters);
        JdbcTemplate metadata = new JdbcTemplate(batchMetadataDataSource);
        metadata.update(
                """
                update BATCH_STEP_EXECUTION
                   set STATUS = 'STARTED', END_TIME = null, LAST_UPDATED = ?
                 where JOB_EXECUTION_ID = ? and STATUS = 'FAILED'
                """,
                LocalDateTime.now().minusMinutes(1),
                interruptedExecution.getId()
        );
        metadata.update(
                """
                update BATCH_JOB_EXECUTION
                   set STATUS = 'STARTED', END_TIME = null, LAST_UPDATED = ?
                 where JOB_EXECUTION_ID = ?
                """,
                LocalDateTime.now().minusMinutes(1),
                interruptedExecution.getId()
        );

        var response = stockBatchJobRunner.run(testJob, "stale-restart", parameters);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(restartState.firstStepRuns.get()).isEqualTo(1);
        assertThat(restartState.secondStepRuns.get()).isEqualTo(2);
        assertThat(metadata.queryForObject(
                "select STATUS from BATCH_JOB_EXECUTION where JOB_EXECUTION_ID = ?",
                String.class,
                interruptedExecution.getId()
        )).isEqualTo("FAILED");
        assertThat(metadata.queryForObject(
                "select EXIT_MESSAGE from BATCH_JOB_EXECUTION where JOB_EXECUTION_ID = ?",
                String.class,
                interruptedExecution.getId()
        )).contains(StockBatchStaleExecutionRecovery.RECOVERY_EXIT_MESSAGE);
    }

    @Test
    void lightweightTask_doesNotCreateBatchJobOrStepRows() {
        JdbcTemplate metadata = new JdbcTemplate(batchMetadataDataSource);
        long before = countJobInstances(metadata, "market-data-refresh");

        stockBatchJobLauncher.refreshMarketData();

        assertThat(countJobInstances(metadata, "market-data-refresh")).isEqualTo(before);
    }

    @Test
    void metadataRetention_completedInstance_archivesThenDeletesThroughBatchSixRepository() throws Exception {
        JobParameters parameters = parameters("metadata-retention-contract");
        JobExecution execution = jobOperatorTestUtils.startJob(parameters);
        MarketSessionFenceService marketSessionFenceService = mock(MarketSessionFenceService.class);
        when(marketSessionFenceService.hasOpenMarket()).thenReturn(false);
        Clock futureClock = Clock.fixed(
                Instant.now().plusSeconds(60L * 60L * 24L * 40L),
                ZoneId.systemDefault()
        );
        BatchMetadataRetentionService retentionService = new BatchMetadataRetentionService(
                jobRepository,
                marketSessionFenceService,
                batchMetadataDataSource,
                batchMetadataTransactionManager,
                new SimpleMeterRegistry(),
                futureClock,
                30,
                100,
                20,
                200,
                true,
                TEST_JOB_NAME
        );

        retentionService.archiveAndOptionallyPurgeCompletedInstances();

        assertThat(jobRepository.getJobInstance(execution.getJobInstanceId())).isNull();
        assertThat(new JdbcTemplate(batchMetadataDataSource).queryForObject(
                "select PURGED_AT from STOCK_BATCH_JOB_METADATA_ARCHIVE where JOB_EXECUTION_ID = ?",
                LocalDateTime.class,
                execution.getId()
        )).isNotNull();
    }

    private JobParameters parameters(String scenario) {
        return new JobParametersBuilder()
                .addLocalDate("businessDate", LocalDate.of(2026, 7, 14), true)
                .addString("scenario", scenario + ":" + UUID.randomUUID(), true)
                .addLocalDateTime("triggeredAt", LocalDateTime.now(), false)
                .toJobParameters();
    }

    private long countJobInstances(JdbcTemplate metadata, String jobName) {
        Long count = metadata.queryForObject(
                "select count(*) from BATCH_JOB_INSTANCE where JOB_NAME = ?",
                Long.class,
                jobName
        );
        return count == null ? 0L : count;
    }

    private boolean tableExists(DataSource dataSource, String tableName) throws SQLException {
        try (var connection = dataSource.getConnection();
             var resultSet = connection.getMetaData().getTables(null, null, tableName, new String[]{"TABLE"})) {
            return resultSet.next();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class NativeRestartJobConfiguration {

        @Bean
        NativeRestartState nativeRestartState() {
            return new NativeRestartState();
        }

        @Bean(name = "native-restart-contract-first-step")
        Step nativeRestartContractFirstStep(
                JobRepository jobRepository,
                @Qualifier(BatchRepositoryConfig.STOCK_BATCH_TASKLET_TRANSACTION_MANAGER)
                PlatformTransactionManager transactionManager,
                NativeRestartState state
        ) {
            return new StepBuilder("native-restart-contract-first-step", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        state.firstStepRuns.incrementAndGet();
                        contribution.incrementWriteCount(2);
                        return RepeatStatus.FINISHED;
                    }, transactionManager)
                    .build();
        }

        @Bean(name = "native-restart-contract-second-step")
        Step nativeRestartContractSecondStep(
                JobRepository jobRepository,
                @Qualifier(BatchRepositoryConfig.STOCK_BATCH_TASKLET_TRANSACTION_MANAGER)
                PlatformTransactionManager transactionManager,
                NativeRestartState state
        ) {
            return new StepBuilder("native-restart-contract-second-step", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        state.secondStepRuns.incrementAndGet();
                        if (state.failSecondStepOnce.compareAndSet(true, false)) {
                            throw new IllegalStateException("planned restart failure");
                        }
                        contribution.incrementWriteCount(3);
                        return RepeatStatus.FINISHED;
                    }, transactionManager)
                    .build();
        }

        @Bean(name = TEST_JOB_NAME)
        Job nativeRestartContractJob(
                JobRepository jobRepository,
                @Qualifier("native-restart-contract-first-step") Step firstStep,
                @Qualifier("native-restart-contract-second-step") Step secondStep
        ) {
            return new JobBuilder(TEST_JOB_NAME, jobRepository)
                    .start(firstStep)
                    .next(secondStep)
                    .build();
        }
    }

    static final class NativeRestartState {

        private final AtomicInteger firstStepRuns = new AtomicInteger();
        private final AtomicInteger secondStepRuns = new AtomicInteger();
        private final AtomicBoolean failSecondStepOnce = new AtomicBoolean();

        void reset() {
            firstStepRuns.set(0);
            secondStepRuns.set(0);
            failSecondStepOnce.set(false);
        }
    }
}
