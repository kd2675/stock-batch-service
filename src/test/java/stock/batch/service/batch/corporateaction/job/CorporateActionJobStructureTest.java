package stock.batch.service.batch.corporateaction.job;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.flow.FlowJob;
import org.springframework.batch.core.job.flow.support.state.StepState;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.beans.factory.InitializingBean;

import stock.batch.service.batch.common.support.PostClosePhaseAttemptJobExecutionListener;
import stock.batch.service.batch.common.support.StockBatchJobParameters;
import stock.batch.service.corporateaction.biz.CorporateActionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CorporateActionJobStructureTest {

    private final CorporateActionJob configuration = new CorporateActionJob();
    private final JobRepository jobRepository = mock(JobRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final CorporateActionService corporateActionService = mock(CorporateActionService.class);
    private final PostClosePhaseAttemptJobExecutionListener phaseAttemptListener =
            mock(PostClosePhaseAttemptJobExecutionListener.class);

    @Test
    void corporateActionJob_cashAndPreOpenOperationsExposeRestartableStageSteps() throws Exception {
        Step legacy = configuration.applyDueCorporateActionsStep(
                jobRepository,
                transactionManager,
                corporateActionService
        );
        Step cashDividend = configuration.cashDividendPaymentStep(
                jobRepository,
                transactionManager,
                corporateActionService
        );
        Step autoSubscription = configuration.capitalIncreaseAutoSubscriptionStep(
                jobRepository,
                transactionManager,
                corporateActionService
        );
        Step capitalPayment = configuration.capitalIncreasePaymentStep(
                jobRepository,
                transactionManager,
                corporateActionService
        );
        Step cashValidation = configuration.validateCorporateCashStep(
                jobRepository,
                transactionManager,
                corporateActionService
        );
        Step exRights = configuration.applyExRightsStep(
                jobRepository,
                transactionManager,
                corporateActionService
        );
        Step capitalListing = configuration.capitalIncreaseListingStep(
                jobRepository,
                transactionManager,
                corporateActionService
        );
        Step freeShareListing = configuration.freeShareListingStep(
                jobRepository,
                transactionManager,
                corporateActionService
        );
        Step stockSplit = configuration.stockSplitStep(
                jobRepository,
                transactionManager,
                corporateActionService
        );
        Step delisting = configuration.delistingStep(
                jobRepository,
                transactionManager,
                corporateActionService
        );
        Step preOpenValidation = configuration.validatePreOpenSecurityTransformStep(
                jobRepository,
                transactionManager,
                corporateActionService
        );
        var decider = configuration.corporateActionOperationDecider();
        var cashFlow = configuration.corporateCashActionFlow(
                cashDividend,
                autoSubscription,
                capitalPayment,
                cashValidation
        );
        var preOpenFlow = configuration.preOpenSecurityTransformFlow(
                exRights,
                capitalListing,
                freeShareListing,
                stockSplit,
                delisting,
                preOpenValidation
        );
        ((InitializingBean) cashFlow).afterPropertiesSet();
        ((InitializingBean) preOpenFlow).afterPropertiesSet();

        Job job = configuration.corporateActionBatchJob(
                jobRepository,
                decider,
                cashFlow,
                preOpenFlow,
                legacy,
                phaseAttemptListener
        );

        assertThat(job).isInstanceOf(FlowJob.class);
        assertThat(Set.copyOf(((FlowJob) job).getStepNames())).contains(CorporateActionJob.STEP_NAME);
        assertThat(stepNames(cashFlow)).isEqualTo(Set.of(
                CorporateActionJob.CASH_DIVIDEND_STEP_NAME,
                CorporateActionJob.AUTO_SUBSCRIPTION_STEP_NAME,
                CorporateActionJob.CAPITAL_PAYMENT_STEP_NAME,
                CorporateActionJob.CASH_VALIDATION_STEP_NAME
        ));
        assertThat(stepNames(preOpenFlow)).isEqualTo(Set.of(
                CorporateActionJob.EX_RIGHTS_STEP_NAME,
                CorporateActionJob.CAPITAL_LISTING_STEP_NAME,
                CorporateActionJob.FREE_SHARE_LISTING_STEP_NAME,
                CorporateActionJob.STOCK_SPLIT_STEP_NAME,
                CorporateActionJob.DELISTING_STEP_NAME,
                CorporateActionJob.PREOPEN_VALIDATION_STEP_NAME
        ));
        assertThat(Set.of(
                legacy,
                cashDividend,
                autoSubscription,
                capitalPayment,
                cashValidation,
                exRights,
                capitalListing,
                freeShareListing,
                stockSplit,
                delisting,
                preOpenValidation
        )).allMatch(Step::isAllowStartIfComplete);
    }

    @Test
    void corporateActionOperationDecider_usesIdentifyingOperationParameter() {
        JobExecution jobExecution = mock(JobExecution.class);
        when(jobExecution.getJobParameters()).thenReturn(new JobParametersBuilder()
                .addString(
                        StockBatchJobParameters.OPERATION,
                        CorporateActionJob.OPERATION_PREOPEN_SECURITY_TRANSFORMS,
                        true
                )
                .toJobParameters());

        var status = configuration.corporateActionOperationDecider().decide(jobExecution, null);

        assertThat(status.getName()).isEqualTo(CorporateActionJob.OPERATION_PREOPEN_SECURITY_TRANSFORMS);
    }

    private Set<String> stepNames(org.springframework.batch.core.job.flow.Flow flow) {
        return flow.getStates().stream()
                .filter(StepState.class::isInstance)
                .map(StepState.class::cast)
                .map(state -> state.getStep().getName())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
