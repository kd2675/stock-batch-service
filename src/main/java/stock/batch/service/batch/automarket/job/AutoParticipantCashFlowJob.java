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

import stock.batch.service.automarket.biz.AutoParticipantCashFlowService;
import stock.batch.service.batch.common.support.PostClosePhaseAttemptJobExecutionListener;
import stock.batch.service.batch.common.support.StockBatchJobParameters;
import stock.batch.service.batch.config.BatchRepositoryConfig;

@Configuration(proxyBeanMethods = false)
public class AutoParticipantCashFlowJob {

    public static final String JOB_NAME = "auto-participant-cash-flow";
    public static final String STEP_NAME = "auto-participant-cash-flow-step";
    public static final String OPERATION_SCHEDULED = "SCHEDULED";
    public static final String OPERATION_MANUAL = "MANUAL";

    @Bean(name = JOB_NAME)
    public Job autoParticipantCashFlowBatchJob(
            JobRepository jobRepository,
            @Qualifier(STEP_NAME) Step autoParticipantCashFlowStep,
            PostClosePhaseAttemptJobExecutionListener phaseAttemptListener
    ) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(autoParticipantCashFlowStep)
                .listener(phaseAttemptListener)
                .build();
    }

    @Bean(name = STEP_NAME)
    public Step autoParticipantCashFlowStep(
            JobRepository jobRepository,
            @Qualifier(BatchRepositoryConfig.STOCK_BATCH_TASKLET_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            AutoParticipantCashFlowService autoParticipantCashFlowService
    ) {
        Tasklet tasklet = (contribution, chunkContext) -> {
            String operation = contribution.getStepExecution().getJobParameters()
                    .getString(StockBatchJobParameters.OPERATION);
            String runKey = contribution.getStepExecution().getJobParameters()
                    .getString(StockBatchJobParameters.REQUEST_ID);
            if (runKey == null || runKey.isBlank()) {
                throw new IllegalArgumentException("Recurring cash requestId is required for restart idempotency");
            }
            int processedCount = switch (operation) {
                case OPERATION_SCHEDULED -> autoParticipantCashFlowService.fundRecurringCash(runKey);
                case OPERATION_MANUAL -> autoParticipantCashFlowService.fundRecurringCashManually(runKey);
                default -> throw new IllegalArgumentException("Unknown auto participant cash flow operation: " + operation);
            };
            contribution.incrementWriteCount(processedCount);
            return RepeatStatus.FINISHED;
        };
        return new StepBuilder(STEP_NAME, jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }
}
