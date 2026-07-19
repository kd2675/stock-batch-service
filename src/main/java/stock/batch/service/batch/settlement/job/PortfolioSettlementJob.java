package stock.batch.service.batch.settlement.job;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import stock.batch.service.batch.common.support.StockBatchJobParameters;
import stock.batch.service.batch.config.BatchRepositoryDataSourceConfig;
import stock.batch.service.batch.settlement.biz.PortfolioSettlementLifecycleService;
import stock.batch.service.batch.settlement.model.AccountSettlementTarget;
import stock.batch.service.batch.settlement.model.PortfolioSnapshotCommand;
import stock.batch.service.batch.settlement.processor.PortfolioSnapshotProcessor;
import stock.batch.service.batch.settlement.reader.AccountSettlementTargetReader;
import stock.batch.service.batch.settlement.writer.PortfolioSettlementItemWriter;
import stock.batch.service.batch.settlement.writer.PortfolioSnapshotWriter;
import stock.batch.service.marketclose.model.PostClosePhase;
import stock.batch.service.marketclose.model.PostClosePhaseClaim;

@Configuration(proxyBeanMethods = false)
public class PortfolioSettlementJob {

    private static final int MAX_SETTLEMENT_CHUNK_SIZE = 2_000;
    public static final String JOB_NAME = "portfolio-settlement";
    public static final String VALIDATE_STEP_NAME = "validate-close-snapshot-step";
    public static final String STEP_NAME = "portfolio-settlement-step";
    public static final String COMPLETE_STEP_NAME = "complete-portfolio-settlement-step";
    public static final String READER_NAME = "portfolioSettlementPagingReader";
    public static final String WRITER_NAME = "portfolioSettlementChunkWriter";
    public static final String ATTEMPT_NO_CONTEXT_KEY = "portfolioSettlementAttemptNo";
    public static final String OWNER_ID_CONTEXT_KEY = "portfolioSettlementOwnerId";

    @Bean(name = JOB_NAME)
    public Job portfolioSettlementBatchJob(
            JobRepository jobRepository,
            @Qualifier(VALIDATE_STEP_NAME) Step validateCloseSnapshotStep,
            @Qualifier(STEP_NAME) Step portfolioSettlementStep,
            @Qualifier(COMPLETE_STEP_NAME) Step completePortfolioSettlementStep,
            PortfolioSettlementJobExecutionListener jobExecutionListener
    ) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(validateCloseSnapshotStep)
                .next(portfolioSettlementStep)
                .next(completePortfolioSettlementStep)
                .listener(jobExecutionListener)
                .build();
    }

    @Bean(name = VALIDATE_STEP_NAME)
    public Step validateCloseSnapshotStep(
            JobRepository jobRepository,
            @Qualifier(BatchRepositoryDataSourceConfig.BUSINESS_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            PortfolioSettlementLifecycleService lifecycleService
    ) {
        Tasklet tasklet = (contribution, chunkContext) -> {
            var stepExecution = contribution.getStepExecution();
            var parameters = stepExecution.getJobParameters();
            long closeCycleId = parameters.getLong(StockBatchJobParameters.CYCLE_ID);
            PostClosePhaseClaim claim = lifecycleService.begin(
                    closeCycleId,
                    stepExecution.getJobExecution().getId(),
                    parameters.getLocalDateTime(StockBatchJobParameters.SNAPSHOT_AT),
                    LocalDateTime.now()
            );
            var executionContext = stepExecution.getJobExecution().getExecutionContext();
            executionContext.putLong(ATTEMPT_NO_CONTEXT_KEY, claim.attemptNo());
            executionContext.putString(OWNER_ID_CONTEXT_KEY, claim.ownerId());
            return RepeatStatus.FINISHED;
        };
        return new StepBuilder(VALIDATE_STEP_NAME, jobRepository)
                .tasklet(tasklet, transactionManager)
                .allowStartIfComplete(true)
                .build();
    }

    @Bean(name = STEP_NAME)
    public Step portfolioSettlementStep(
            JobRepository jobRepository,
            @Qualifier(BatchRepositoryDataSourceConfig.BUSINESS_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            @Value("${stock.batch.settlement.chunk-size:200}") int chunkSize,
            @Qualifier(READER_NAME) JdbcPagingItemReader<AccountSettlementTarget> reader,
            PortfolioSnapshotProcessor processor,
            @Qualifier(WRITER_NAME) PortfolioSettlementItemWriter writer
    ) {
        validateChunkSize(chunkSize);
        return new StepBuilder(STEP_NAME, jobRepository)
                .<AccountSettlementTarget, PortfolioSnapshotCommand>chunk(chunkSize)
                .reader(reader)
                .processor(processor::process)
                .writer(writer)
                .transactionManager(transactionManager)
                .build();
    }

    @Bean(name = READER_NAME)
    @StepScope
    public JdbcPagingItemReader<AccountSettlementTarget> portfolioSettlementPagingReader(
            AccountSettlementTargetReader readerFactory,
            @Value("${stock.batch.settlement.chunk-size:200}") int pageSize,
            @Value("#{jobParameters['" + StockBatchJobParameters.CYCLE_ID + "']}") Long closeCycleId
    ) throws Exception {
        validateChunkSize(pageSize);
        return readerFactory.create(pageSize, closeCycleId);
    }

    @Bean(name = WRITER_NAME)
    @StepScope
    public PortfolioSettlementItemWriter portfolioSettlementChunkWriter(
            PortfolioSnapshotWriter writer,
            @Value("#{jobParameters['" + StockBatchJobParameters.BUSINESS_DATE + "']}") LocalDate snapshotDate,
            @Value("#{jobParameters['" + StockBatchJobParameters.SNAPSHOT_AT + "']}") LocalDateTime snapshotAt
    ) {
        return new PortfolioSettlementItemWriter(writer, snapshotDate, snapshotAt);
    }

    @Bean(name = COMPLETE_STEP_NAME)
    public Step completePortfolioSettlementStep(
            JobRepository jobRepository,
            @Qualifier(BatchRepositoryDataSourceConfig.BUSINESS_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            PortfolioSettlementLifecycleService lifecycleService
    ) {
        Tasklet tasklet = (contribution, chunkContext) -> {
            var jobExecution = contribution.getStepExecution().getJobExecution();
            var parameters = jobExecution.getJobParameters();
            var executionContext = jobExecution.getExecutionContext();
            PostClosePhaseClaim claim = new PostClosePhaseClaim(
                    parameters.getLong(StockBatchJobParameters.CYCLE_ID),
                    PostClosePhase.LEDGER_FROZEN,
                    Math.toIntExact(executionContext.getLong(ATTEMPT_NO_CONTEXT_KEY)),
                    executionContext.getString(OWNER_ID_CONTEXT_KEY),
                    null
            );
            lifecycleService.complete(claim, LocalDateTime.now());
            return RepeatStatus.FINISHED;
        };
        return new StepBuilder(COMPLETE_STEP_NAME, jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }

    static void validateChunkSize(int chunkSize) {
        if (chunkSize < 1 || chunkSize > MAX_SETTLEMENT_CHUNK_SIZE) {
            throw new IllegalArgumentException(
                    "stock.batch.settlement.chunk-size must be between 1 and %d: %d"
                            .formatted(MAX_SETTLEMENT_CHUNK_SIZE, chunkSize)
            );
        }
    }
}
