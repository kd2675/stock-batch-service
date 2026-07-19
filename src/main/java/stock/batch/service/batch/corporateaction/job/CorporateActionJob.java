package stock.batch.service.batch.corporateaction.job;

import java.time.LocalDate;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
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
import stock.batch.service.corporateaction.biz.CorporateActionService;

@Configuration(proxyBeanMethods = false)
public class CorporateActionJob {

    public static final String JOB_NAME = "corporate-actions";
    public static final String STEP_NAME = "apply-due-corporate-actions-step";
    public static final String OPERATION_DECIDER_NAME = "corporate-action-operation-decider";
    public static final String CASH_FLOW_NAME = "corporate-cash-action-flow";
    public static final String PREOPEN_FLOW_NAME = "preopen-security-transform-flow";
    public static final String CASH_DIVIDEND_STEP_NAME = "cash-dividend-payment-step";
    public static final String AUTO_SUBSCRIPTION_STEP_NAME = "capital-increase-auto-subscription-step";
    public static final String CAPITAL_PAYMENT_STEP_NAME = "capital-increase-payment-step";
    public static final String CASH_VALIDATION_STEP_NAME = "validate-corporate-cash-step";
    public static final String EX_RIGHTS_STEP_NAME = "apply-ex-rights-step";
    public static final String CAPITAL_LISTING_STEP_NAME = "capital-increase-listing-step";
    public static final String FREE_SHARE_LISTING_STEP_NAME = "free-share-listing-step";
    public static final String STOCK_SPLIT_STEP_NAME = "stock-split-step";
    public static final String DELISTING_STEP_NAME = "delisting-step";
    public static final String PREOPEN_VALIDATION_STEP_NAME = "validate-preopen-security-transform-step";
    public static final String OPERATION_ALL = "ALL";
    public static final String OPERATION_CASH = "CASH";
    public static final String OPERATION_PREOPEN_SECURITY_TRANSFORMS = "PREOPEN_SECURITY_TRANSFORMS";

    @Bean(name = JOB_NAME)
    public Job corporateActionBatchJob(
            JobRepository jobRepository,
            @Qualifier(OPERATION_DECIDER_NAME) JobExecutionDecider operationDecider,
            @Qualifier(CASH_FLOW_NAME) Flow corporateCashActionFlow,
            @Qualifier(PREOPEN_FLOW_NAME) Flow preOpenSecurityTransformFlow,
            @Qualifier(STEP_NAME) Step applyDueCorporateActionsStep,
            PostClosePhaseAttemptJobExecutionListener phaseAttemptListener
    ) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(operationDecider)
                .on(OPERATION_CASH).to(corporateCashActionFlow)
                .from(operationDecider).on(OPERATION_PREOPEN_SECURITY_TRANSFORMS).to(preOpenSecurityTransformFlow)
                .from(operationDecider).on(OPERATION_ALL).to(applyDueCorporateActionsStep)
                .from(operationDecider).on("*").fail()
                .end()
                .listener(phaseAttemptListener)
                .build();
    }

    @Bean(name = OPERATION_DECIDER_NAME)
    public JobExecutionDecider corporateActionOperationDecider() {
        return (jobExecution, stepExecution) -> new FlowExecutionStatus(
                jobExecution.getJobParameters().getString(StockBatchJobParameters.OPERATION, OPERATION_ALL)
        );
    }

    @Bean(name = CASH_FLOW_NAME)
    public Flow corporateCashActionFlow(
            @Qualifier(CASH_DIVIDEND_STEP_NAME) Step cashDividendPaymentStep,
            @Qualifier(AUTO_SUBSCRIPTION_STEP_NAME) Step capitalIncreaseAutoSubscriptionStep,
            @Qualifier(CAPITAL_PAYMENT_STEP_NAME) Step capitalIncreasePaymentStep,
            @Qualifier(CASH_VALIDATION_STEP_NAME) Step validateCorporateCashStep
    ) {
        return new FlowBuilder<Flow>(CASH_FLOW_NAME)
                .start(cashDividendPaymentStep)
                .next(capitalIncreaseAutoSubscriptionStep)
                .next(capitalIncreasePaymentStep)
                .next(validateCorporateCashStep)
                .build();
    }

    @Bean(name = PREOPEN_FLOW_NAME)
    public Flow preOpenSecurityTransformFlow(
            @Qualifier(EX_RIGHTS_STEP_NAME) Step applyExRightsStep,
            @Qualifier(CAPITAL_LISTING_STEP_NAME) Step capitalIncreaseListingStep,
            @Qualifier(FREE_SHARE_LISTING_STEP_NAME) Step freeShareListingStep,
            @Qualifier(STOCK_SPLIT_STEP_NAME) Step stockSplitStep,
            @Qualifier(DELISTING_STEP_NAME) Step delistingStep,
            @Qualifier(PREOPEN_VALIDATION_STEP_NAME) Step validatePreOpenSecurityTransformStep
    ) {
        return new FlowBuilder<Flow>(PREOPEN_FLOW_NAME)
                .start(applyExRightsStep)
                .next(capitalIncreaseListingStep)
                .next(freeShareListingStep)
                .next(stockSplitStep)
                .next(delistingStep)
                .next(validatePreOpenSecurityTransformStep)
                .build();
    }

    @Bean(name = STEP_NAME)
    public Step applyDueCorporateActionsStep(
            JobRepository jobRepository,
            @Qualifier(BatchRepositoryConfig.STOCK_BATCH_TASKLET_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            CorporateActionService corporateActionService
    ) {
        return stageStep(
                STEP_NAME,
                jobRepository,
                transactionManager,
                (businessDate, requiredCloseDate) -> corporateActionService.applyDueCorporateActions()
        );
    }

    @Bean(name = CASH_DIVIDEND_STEP_NAME)
    public Step cashDividendPaymentStep(
            JobRepository jobRepository,
            @Qualifier(BatchRepositoryConfig.STOCK_BATCH_TASKLET_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            CorporateActionService corporateActionService
    ) {
        return stageStep(
                CASH_DIVIDEND_STEP_NAME,
                jobRepository,
                transactionManager,
                (businessDate, requiredCloseDate) ->
                        corporateActionService.processCashDividendPaymentStep(businessDate)
        );
    }

    @Bean(name = AUTO_SUBSCRIPTION_STEP_NAME)
    public Step capitalIncreaseAutoSubscriptionStep(
            JobRepository jobRepository,
            @Qualifier(BatchRepositoryConfig.STOCK_BATCH_TASKLET_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            CorporateActionService corporateActionService
    ) {
        return stageStep(
                AUTO_SUBSCRIPTION_STEP_NAME,
                jobRepository,
                transactionManager,
                (businessDate, requiredCloseDate) ->
                        corporateActionService.processCapitalIncreaseAutoSubscriptionStep(businessDate)
        );
    }

    @Bean(name = CAPITAL_PAYMENT_STEP_NAME)
    public Step capitalIncreasePaymentStep(
            JobRepository jobRepository,
            @Qualifier(BatchRepositoryConfig.STOCK_BATCH_TASKLET_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            CorporateActionService corporateActionService
    ) {
        return stageStep(
                CAPITAL_PAYMENT_STEP_NAME,
                jobRepository,
                transactionManager,
                (businessDate, requiredCloseDate) ->
                        corporateActionService.processCapitalIncreasePaymentStep(businessDate)
        );
    }

    @Bean(name = CASH_VALIDATION_STEP_NAME)
    public Step validateCorporateCashStep(
            JobRepository jobRepository,
            @Qualifier(BatchRepositoryConfig.STOCK_BATCH_TASKLET_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            CorporateActionService corporateActionService
    ) {
        return stageStep(
                CASH_VALIDATION_STEP_NAME,
                jobRepository,
                transactionManager,
                (businessDate, requiredCloseDate) -> corporateActionService.validateCorporateCashStep(businessDate)
        );
    }

    @Bean(name = EX_RIGHTS_STEP_NAME)
    public Step applyExRightsStep(
            JobRepository jobRepository,
            @Qualifier(BatchRepositoryConfig.STOCK_BATCH_TASKLET_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            CorporateActionService corporateActionService
    ) {
        return preOpenStageStep(
                EX_RIGHTS_STEP_NAME,
                jobRepository,
                transactionManager,
                corporateActionService::processExRightsStep
        );
    }

    @Bean(name = CAPITAL_LISTING_STEP_NAME)
    public Step capitalIncreaseListingStep(
            JobRepository jobRepository,
            @Qualifier(BatchRepositoryConfig.STOCK_BATCH_TASKLET_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            CorporateActionService corporateActionService
    ) {
        return preOpenStageStep(
                CAPITAL_LISTING_STEP_NAME,
                jobRepository,
                transactionManager,
                corporateActionService::processCapitalIncreaseListingStep
        );
    }

    @Bean(name = FREE_SHARE_LISTING_STEP_NAME)
    public Step freeShareListingStep(
            JobRepository jobRepository,
            @Qualifier(BatchRepositoryConfig.STOCK_BATCH_TASKLET_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            CorporateActionService corporateActionService
    ) {
        return preOpenStageStep(
                FREE_SHARE_LISTING_STEP_NAME,
                jobRepository,
                transactionManager,
                corporateActionService::processFreeShareListingStep
        );
    }

    @Bean(name = STOCK_SPLIT_STEP_NAME)
    public Step stockSplitStep(
            JobRepository jobRepository,
            @Qualifier(BatchRepositoryConfig.STOCK_BATCH_TASKLET_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            CorporateActionService corporateActionService
    ) {
        return preOpenStageStep(
                STOCK_SPLIT_STEP_NAME,
                jobRepository,
                transactionManager,
                corporateActionService::processStockSplitStep
        );
    }

    @Bean(name = DELISTING_STEP_NAME)
    public Step delistingStep(
            JobRepository jobRepository,
            @Qualifier(BatchRepositoryConfig.STOCK_BATCH_TASKLET_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            CorporateActionService corporateActionService
    ) {
        return preOpenStageStep(
                DELISTING_STEP_NAME,
                jobRepository,
                transactionManager,
                corporateActionService::processDelistingStep
        );
    }

    @Bean(name = PREOPEN_VALIDATION_STEP_NAME)
    public Step validatePreOpenSecurityTransformStep(
            JobRepository jobRepository,
            @Qualifier(BatchRepositoryConfig.STOCK_BATCH_TASKLET_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            CorporateActionService corporateActionService
    ) {
        return preOpenStageStep(
                PREOPEN_VALIDATION_STEP_NAME,
                jobRepository,
                transactionManager,
                corporateActionService::validatePreOpenSecurityTransformsStep
        );
    }

    private Step preOpenStageStep(
            String stepName,
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            CorporateActionStage stage
    ) {
        return stageStep(stepName, jobRepository, transactionManager, stage);
    }

    private Step stageStep(
            String stepName,
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            CorporateActionStage stage
    ) {
        Tasklet tasklet = (contribution, chunkContext) -> {
            var parameters = contribution.getStepExecution().getJobParameters();
            LocalDate businessDate = parameters.getLocalDate(StockBatchJobParameters.BUSINESS_DATE);
            LocalDate requiredCloseDate = parameters.getLocalDate(StockBatchJobParameters.REQUIRED_CLOSE_DATE);
            int processedCount = stage.execute(businessDate, requiredCloseDate);
            contribution.incrementWriteCount(processedCount);
            return RepeatStatus.FINISHED;
        };
        return new StepBuilder(stepName, jobRepository)
                .tasklet(tasklet, transactionManager)
                // Every service stage deliberately processes only a bounded action/account
                // cohort. If the validation Step finds more due work, the same failed
                // JobInstance is restarted. Completed stage Steps therefore have to run again
                // so the next bounded cohort can advance; all mutations are protected by the
                // corporate-action processing ledger and are idempotent on restart.
                .allowStartIfComplete(true)
                .build();
    }

    @FunctionalInterface
    private interface CorporateActionStage {

        int execute(LocalDate businessDate, LocalDate requiredCloseDate);
    }
}
