package stock.batch.service.batch.settlement.job;

import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.common.support.StockBatchJobParameters;
import stock.batch.service.batch.settlement.biz.PortfolioSettlementLifecycleService;
import stock.batch.service.marketclose.model.PostClosePhase;
import stock.batch.service.marketclose.model.PostClosePhaseClaim;

@Component
@RequiredArgsConstructor
@Slf4j
public class PortfolioSettlementJobExecutionListener implements JobExecutionListener {

    private final PortfolioSettlementLifecycleService lifecycleService;

    @Override
    public void afterJob(JobExecution jobExecution) {
        if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
            return;
        }
        var executionContext = jobExecution.getExecutionContext();
        if (!executionContext.containsKey(PortfolioSettlementJob.ATTEMPT_NO_CONTEXT_KEY)
                || !executionContext.containsKey(PortfolioSettlementJob.OWNER_ID_CONTEXT_KEY)) {
            return;
        }
        PostClosePhaseClaim claim = new PostClosePhaseClaim(
                jobExecution.getJobParameters().getLong(StockBatchJobParameters.CYCLE_ID),
                PostClosePhase.LEDGER_FROZEN,
                Math.toIntExact(executionContext.getLong(PortfolioSettlementJob.ATTEMPT_NO_CONTEXT_KEY)),
                executionContext.getString(PortfolioSettlementJob.OWNER_ID_CONTEXT_KEY),
                null
        );
        RuntimeException failure = jobExecution.getAllFailureExceptions().stream()
                .findFirst()
                .map(this::asRuntimeException)
                .orElseGet(() -> new IllegalStateException(
                        "Portfolio settlement ended with status " + jobExecution.getStatus()
                ));
        try {
            lifecycleService.fail(claim, failure, LocalDateTime.now());
        } catch (RuntimeException cleanupFailure) {
            log.error(
                    "Failed to release portfolio settlement cycle claim: cycleId={}, attemptNo={}",
                    claim.cycleId(),
                    claim.attemptNo(),
                    cleanupFailure
            );
        }
    }

    private RuntimeException asRuntimeException(Throwable failure) {
        return failure instanceof RuntimeException runtimeException
                ? runtimeException
                : new IllegalStateException(failure.getMessage(), failure);
    }
}
