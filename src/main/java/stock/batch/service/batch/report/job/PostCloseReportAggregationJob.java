package stock.batch.service.batch.report.job;

import java.time.LocalDateTime;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
    static final String SYMBOL_AFTER_CONTEXT_KEY = "postCloseReportSymbolAfter";
    static final String ACCOUNT_AFTER_CONTEXT_KEY = "postCloseReportAccountAfter";

    @Bean(name = JOB_NAME)
    public Job postCloseReportAggregationBatchJob(
            JobRepository jobRepository,
            @Qualifier(SYMBOL_STEP_NAME) Step symbolStep,
            @Qualifier(ACCOUNT_STEP_NAME) Step accountStep,
            @Qualifier(SUMMARY_STEP_NAME) Step summaryStep,
            PostClosePhaseAttemptJobExecutionListener phaseAttemptListener
    ) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(symbolStep)
                .next(accountStep)
                .next(summaryStep)
                .listener(phaseAttemptListener)
                .build();
    }

    @Bean(name = SYMBOL_STEP_NAME)
    @StepScope
    public Step aggregateOrderBookDailyReportStep(
            JobRepository jobRepository,
            @Qualifier(BatchRepositoryConfig.STOCK_BATCH_TASKLET_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            PostCloseReportAggregationService aggregationService,
            @Value("#{jobParameters['" + StockBatchJobParameters.CYCLE_ID + "']}") Long closeCycleId,
            @Value("#{jobParameters['" + StockBatchJobParameters.SNAPSHOT_AT + "']}") LocalDateTime aggregatedAt
    ) {
        Tasklet tasklet = (contribution, chunkContext) -> {
            var executionContext = contribution.getStepExecution().getExecutionContext();
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
    @StepScope
    public Step aggregateAccountDailyReportStep(
            JobRepository jobRepository,
            @Qualifier(BatchRepositoryConfig.STOCK_BATCH_TASKLET_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            PostCloseReportAggregationService aggregationService,
            @Value("#{jobParameters['" + StockBatchJobParameters.CYCLE_ID + "']}") Long closeCycleId,
            @Value("#{jobParameters['" + StockBatchJobParameters.SNAPSHOT_AT + "']}") LocalDateTime aggregatedAt
    ) {
        Tasklet tasklet = (contribution, chunkContext) -> {
            var executionContext = contribution.getStepExecution().getExecutionContext();
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
    @StepScope
    public Step rebuildExecutionAccountDaySummaryStep(
            JobRepository jobRepository,
            @Qualifier(BatchRepositoryConfig.STOCK_BATCH_TASKLET_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            PostCloseReportAggregationService aggregationService,
            @Value("#{jobParameters['" + StockBatchJobParameters.CYCLE_ID + "']}") Long closeCycleId,
            @Value("#{jobParameters['" + StockBatchJobParameters.SNAPSHOT_AT + "']}") LocalDateTime rebuiltAt
    ) {
        Tasklet tasklet = (contribution, chunkContext) -> {
            contribution.incrementWriteCount(
                    aggregationService.rebuildAccountDaySummary(closeCycleId, rebuiltAt)
            );
            return RepeatStatus.FINISHED;
        };
        return new StepBuilder(SUMMARY_STEP_NAME, jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }
}
