package stock.batch.service.batch.corporateaction.job;

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

import stock.batch.service.batch.config.BatchRepositoryConfig;
import stock.batch.service.corporateaction.biz.CorporateActionService;

@Configuration(proxyBeanMethods = false)
public class CorporateActionJob {

    public static final String JOB_NAME = "corporate-actions";
    public static final String STEP_NAME = "apply-due-corporate-actions-step";

    @Bean(name = JOB_NAME)
    public Job corporateActionBatchJob(
            JobRepository jobRepository,
            @Qualifier(STEP_NAME) Step applyDueCorporateActionsStep
    ) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(applyDueCorporateActionsStep)
                .build();
    }

    @Bean(name = STEP_NAME)
    public Step applyDueCorporateActionsStep(
            JobRepository jobRepository,
            @Qualifier(BatchRepositoryConfig.STOCK_BATCH_TASKLET_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            CorporateActionService corporateActionService
    ) {
        Tasklet tasklet = (contribution, chunkContext) -> {
            int processedCount = corporateActionService.applyDueCorporateActions();
            contribution.incrementWriteCount(processedCount);
            return RepeatStatus.FINISHED;
        };
        return new StepBuilder(STEP_NAME, jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }
}
