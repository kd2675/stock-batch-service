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
import stock.batch.service.batch.config.BatchRepositoryConfig;

@Configuration(proxyBeanMethods = false)
public class AutoMarketDailyRegimePreCreateJob {

    public static final String JOB_NAME = "auto-market-daily-regime-pre-create";
    public static final String STEP_NAME = "auto-market-daily-regime-pre-create-step";

    @Bean(name = JOB_NAME)
    public Job autoMarketDailyRegimePreCreateBatchJob(
            JobRepository jobRepository,
            @Qualifier(STEP_NAME) Step autoMarketDailyRegimePreCreateStep
    ) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(autoMarketDailyRegimePreCreateStep)
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
            int processedCount = service.preCreateDailyRegimes();
            contribution.incrementWriteCount(processedCount);
            return RepeatStatus.FINISHED;
        };
        return new StepBuilder(STEP_NAME, jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }
}
