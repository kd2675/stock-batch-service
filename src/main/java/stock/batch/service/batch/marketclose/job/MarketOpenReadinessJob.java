package stock.batch.service.batch.marketclose.job;

import java.time.LocalDate;

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
import stock.batch.service.marketclose.biz.PostCloseReadinessService;

@Configuration(proxyBeanMethods = false)
public class MarketOpenReadinessJob {

    public static final String JOB_NAME = "market-open-readiness";
    public static final String STEP_NAME = "validate-market-open-readiness-step";

    @Bean(name = JOB_NAME)
    public Job marketOpenReadinessBatchJob(
            JobRepository jobRepository,
            @Qualifier(STEP_NAME) Step readinessStep,
            PostClosePhaseAttemptJobExecutionListener phaseAttemptListener
    ) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(readinessStep)
                .listener(phaseAttemptListener)
                .build();
    }

    @Bean(name = STEP_NAME)
    public Step marketOpenReadinessStep(
            JobRepository jobRepository,
            @Qualifier(BatchRepositoryConfig.STOCK_BATCH_TASKLET_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            PostCloseReadinessService readinessService
    ) {
        Tasklet tasklet = (contribution, chunkContext) -> {
            var parameters = contribution.getStepExecution().getJobParameters();
            Long cycleId = parameters.getLong(StockBatchJobParameters.CYCLE_ID);
            LocalDate preparingBusinessDate = parameters.getLocalDate(StockBatchJobParameters.BUSINESS_DATE);
            contribution.incrementWriteCount(
                    readinessService.validateReadyToOpen(cycleId, preparingBusinessDate)
            );
            return RepeatStatus.FINISHED;
        };
        return new StepBuilder(STEP_NAME, jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }
}
