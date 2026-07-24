package stock.batch.service.batch.report.job;

import java.time.LocalDateTime;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import stock.batch.service.batch.common.support.PostClosePhaseAttemptJobExecutionListener;
import stock.batch.service.batch.common.support.StockBatchJobParameters;
import stock.batch.service.batch.config.BatchRepositoryConfig;
import stock.batch.service.marketclose.biz.PostCloseReportAggregationService;

@Configuration(proxyBeanMethods = false)
public class PostCloseReportAggregationJob {

    public static final String JOB_NAME = "post-close-report-aggregation";
    public static final String SYMBOL_STEP_NAME = "aggregate-order-book-daily-report-step";
    public static final String ACCOUNT_STEP_NAME = "aggregate-account-daily-report-step";
    public static final String SUMMARY_STEP_NAME = "rebuild-execution-account-day-summary-step";
    public static final String POSITION_STATE_STEP_NAME = "rebuild-auto-participant-position-state-step";
    public static final String FUNDING_BUDGET_EXPIRY_STEP_NAME = "expire-auto-participant-funding-budget-step";
    public static final String FUNDING_BUDGET_STEP_NAME = "validate-auto-participant-funding-budget-step";
    static final String SYMBOL_AFTER_CONTEXT_KEY = "postCloseReportSymbolAfter";
    static final String ACCOUNT_AFTER_CONTEXT_KEY = "postCloseReportAccountAfter";
    static final String FUNDING_BUDGET_EXPIRY_AFTER_CONTEXT_KEY = "postCloseFundingBudgetExpiryAfterId";
    static final String FUNDING_BUDGET_AFTER_CONTEXT_KEY = "postCloseFundingBudgetAfterId";

    @Bean(name = JOB_NAME)
    public Job postCloseReportAggregationBatchJob(
            JobRepository jobRepository,
            @Qualifier(SYMBOL_STEP_NAME) Step symbolStep,
            @Qualifier(ACCOUNT_STEP_NAME) Step accountStep,
            @Qualifier(SUMMARY_STEP_NAME) Step summaryStep,
            @Qualifier(POSITION_STATE_STEP_NAME) Step positionStateStep,
            @Qualifier(FUNDING_BUDGET_EXPIRY_STEP_NAME) Step fundingBudgetExpiryStep,
            @Qualifier(FUNDING_BUDGET_STEP_NAME) Step fundingBudgetStep,
            PostClosePhaseAttemptJobExecutionListener phaseAttemptListener
    ) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(symbolStep)
                .next(accountStep)
                .next(summaryStep)
                .next(positionStateStep)
                .next(fundingBudgetExpiryStep)
                .next(fundingBudgetStep)
                .listener(phaseAttemptListener)
                .build();
    }

    @Bean(name = SYMBOL_STEP_NAME)
    public Step aggregateOrderBookDailyReportStep(
            JobRepository jobRepository,
            @Qualifier(BatchRepositoryConfig.STOCK_BATCH_TASKLET_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            PostCloseReportAggregationService aggregationService
    ) {
        Tasklet tasklet = (contribution, chunkContext) -> {
            var stepExecution = contribution.getStepExecution();
            var jobParameters = stepExecution.getJobParameters();
            long closeCycleId = jobParameters.getLong(StockBatchJobParameters.CYCLE_ID);
            LocalDateTime aggregatedAt = jobParameters.getLocalDateTime(StockBatchJobParameters.SNAPSHOT_AT);
            var executionContext = stepExecution.getExecutionContext();
            var result = aggregationService.aggregateOrderBookDailySnapshotChunk(
                    closeCycleId,
                    aggregatedAt,
                    executionContext.getString(SYMBOL_AFTER_CONTEXT_KEY, "")
            );
            contribution.incrementWriteCount(result.processedCount());
            executionContext.putString(SYMBOL_AFTER_CONTEXT_KEY, result.lastSymbol());
            return RepeatStatus.continueIf(!result.finished());
        };
        return new StepBuilder(SYMBOL_STEP_NAME, jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }

    @Bean(name = ACCOUNT_STEP_NAME)
    public Step aggregateAccountDailyReportStep(
            JobRepository jobRepository,
            @Qualifier(BatchRepositoryConfig.STOCK_BATCH_TASKLET_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            PostCloseReportAggregationService aggregationService
    ) {
        Tasklet tasklet = (contribution, chunkContext) -> {
            var stepExecution = contribution.getStepExecution();
            var jobParameters = stepExecution.getJobParameters();
            long closeCycleId = jobParameters.getLong(StockBatchJobParameters.CYCLE_ID);
            LocalDateTime aggregatedAt = jobParameters.getLocalDateTime(StockBatchJobParameters.SNAPSHOT_AT);
            var executionContext = stepExecution.getExecutionContext();
            var result = aggregationService.aggregateAccountDailySnapshotChunk(
                    closeCycleId,
                    aggregatedAt,
                    executionContext.getString(ACCOUNT_AFTER_CONTEXT_KEY, "")
            );
            contribution.incrementWriteCount(result.processedCount());
            executionContext.putString(ACCOUNT_AFTER_CONTEXT_KEY, result.lastSymbol());
            return RepeatStatus.continueIf(!result.finished());
        };
        return new StepBuilder(ACCOUNT_STEP_NAME, jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }

    @Bean(name = SUMMARY_STEP_NAME)
    public Step rebuildExecutionAccountDaySummaryStep(
            JobRepository jobRepository,
            @Qualifier(BatchRepositoryConfig.STOCK_BATCH_TASKLET_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            PostCloseReportAggregationService aggregationService
    ) {
        Tasklet tasklet = (contribution, chunkContext) -> {
            var jobParameters = contribution.getStepExecution().getJobParameters();
            long closeCycleId = jobParameters.getLong(StockBatchJobParameters.CYCLE_ID);
            LocalDateTime rebuiltAt = jobParameters.getLocalDateTime(StockBatchJobParameters.SNAPSHOT_AT);
            contribution.incrementWriteCount(
                    aggregationService.rebuildAccountDaySummary(closeCycleId, rebuiltAt)
            );
            return RepeatStatus.FINISHED;
        };
        return new StepBuilder(SUMMARY_STEP_NAME, jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }

    @Bean(name = POSITION_STATE_STEP_NAME)
    public Step rebuildAutoParticipantPositionStateStep(
            JobRepository jobRepository,
            @Qualifier(BatchRepositoryConfig.STOCK_BATCH_TASKLET_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            PostCloseReportAggregationService aggregationService
    ) {
        Tasklet tasklet = (contribution, chunkContext) -> {
            var jobParameters = contribution.getStepExecution().getJobParameters();
            long closeCycleId = jobParameters.getLong(StockBatchJobParameters.CYCLE_ID);
            LocalDateTime rebuiltAt = jobParameters.getLocalDateTime(StockBatchJobParameters.SNAPSHOT_AT);
            contribution.incrementWriteCount(
                    aggregationService.rebuildAutoParticipantPositionState(closeCycleId, rebuiltAt)
            );
            return RepeatStatus.FINISHED;
        };
        return new StepBuilder(POSITION_STATE_STEP_NAME, jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }

    @Bean(name = FUNDING_BUDGET_EXPIRY_STEP_NAME)
    public Step expireAutoParticipantFundingBudgetStep(
            JobRepository jobRepository,
            @Qualifier(BatchRepositoryConfig.STOCK_BATCH_TASKLET_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            PostCloseReportAggregationService aggregationService
    ) {
        Tasklet tasklet = (contribution, chunkContext) -> {
            var stepExecution = contribution.getStepExecution();
            var jobParameters = stepExecution.getJobParameters();
            long closeCycleId = jobParameters.getLong(StockBatchJobParameters.CYCLE_ID);
            LocalDateTime expiredAt = jobParameters.getLocalDateTime(StockBatchJobParameters.SNAPSHOT_AT);
            var executionContext = stepExecution.getExecutionContext();
            var result = aggregationService.expireUnusedFundingBudgetChunk(
                    closeCycleId,
                    expiredAt,
                    executionContext.getLong(FUNDING_BUDGET_EXPIRY_AFTER_CONTEXT_KEY, 0L)
            );
            contribution.incrementWriteCount(result.expiredCount());
            executionContext.putLong(FUNDING_BUDGET_EXPIRY_AFTER_CONTEXT_KEY, result.lastBudgetId());
            return RepeatStatus.continueIf(!result.finished());
        };
        return new StepBuilder(FUNDING_BUDGET_EXPIRY_STEP_NAME, jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }

    @Bean(name = FUNDING_BUDGET_STEP_NAME)
    public Step validateAutoParticipantFundingBudgetStep(
            JobRepository jobRepository,
            @Qualifier(BatchRepositoryConfig.STOCK_BATCH_TASKLET_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            PostCloseReportAggregationService aggregationService
    ) {
        Tasklet tasklet = (contribution, chunkContext) -> {
            var stepExecution = contribution.getStepExecution();
            long closeCycleId = stepExecution.getJobParameters()
                    .getLong(StockBatchJobParameters.CYCLE_ID);
            var executionContext = stepExecution.getExecutionContext();
            var result = aggregationService.validateFundingBudgetReconciliationChunk(
                    closeCycleId,
                    executionContext.getLong(FUNDING_BUDGET_AFTER_CONTEXT_KEY, 0L)
            );
            contribution.incrementWriteCount(result.processedCount());
            executionContext.putLong(FUNDING_BUDGET_AFTER_CONTEXT_KEY, result.lastBudgetId());
            return RepeatStatus.continueIf(!result.finished());
        };
        return new StepBuilder(FUNDING_BUDGET_STEP_NAME, jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }
}
