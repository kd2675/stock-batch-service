package stock.batch.service.batch.automarket.job;

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

import stock.batch.service.automarket.biz.AutoMarketDailyRegimePreCreateService;
import stock.batch.service.batch.common.support.PostClosePhaseAttemptJobExecutionListener;
import stock.batch.service.batch.common.support.StockBatchJobParameters;
import stock.batch.service.batch.config.BatchRepositoryConfig;

@Configuration(proxyBeanMethods = false)
public class AutoMarketDailyRegimePreCreateJob {

    public static final String JOB_NAME = "auto-market-daily-regime-pre-create";
    public static final String STEP_NAME = "auto-market-daily-regime-pre-create-step";
    public static final String OPERATION_SCHEDULED = "SCHEDULED";
    public static final String OPERATION_POST_CLOSE_PREPARE = "POST_CLOSE_PREPARE";

    @Bean(name = JOB_NAME)
    public Job autoMarketDailyRegimePreCreateBatchJob(
            JobRepository jobRepository,
            @Qualifier(STEP_NAME) Step autoMarketDailyRegimePreCreateStep,
            PostClosePhaseAttemptJobExecutionListener phaseAttemptListener
    ) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(autoMarketDailyRegimePreCreateStep)
                .listener(phaseAttemptListener)
                .build();
    }

    @Bean(name = STEP_NAME)
    public Step autoMarketDailyRegimePreCreateStep(
            JobRepository jobRepository,
            @Qualifier(BatchRepositoryConfig.STOCK_BATCH_TASKLET_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            AutoMarketDailyRegimePreCreateService service
    ) {
        Tasklet tasklet = (contribution, chunkContext) -> {
            var parameters = contribution.getStepExecution().getJobParameters();
            String operation = parameters.getString(
                    StockBatchJobParameters.OPERATION,
                    OPERATION_SCHEDULED
            );
            int processedCount = switch (operation) {
                case OPERATION_SCHEDULED -> service.preCreateDailyRegimes();
                case OPERATION_POST_CLOSE_PREPARE -> service.preCreateDailyRegimes(
                        parameters.getLocalDate(StockBatchJobParameters.BUSINESS_DATE),
                        parameters.getLocalDateTime(StockBatchJobParameters.SWEEP_AT)
                );
                default -> throw new IllegalArgumentException(
                        "Unsupported daily regime operation: " + operation
                );
            };
            contribution.incrementWriteCount(processedCount);
            return RepeatStatus.FINISHED;
        };
        return new StepBuilder(STEP_NAME, jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }
}
