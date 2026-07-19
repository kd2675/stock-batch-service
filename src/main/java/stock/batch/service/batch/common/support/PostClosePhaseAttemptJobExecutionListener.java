package stock.batch.service.batch.common.support;

import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.stereotype.Component;

import stock.batch.service.marketclose.biz.PostCloseCycleService;

/**
 * Links a coordinator-owned logical phase attempt to its physical Spring Batch execution.
 *
 * <p>Only jobs carrying a cycle ID perform the small control-table update. Legacy scheduled jobs
 * and regular-session lightweight order/execution tasks do no additional database work.</p>
 */
@Component
@RequiredArgsConstructor
public class PostClosePhaseAttemptJobExecutionListener implements JobExecutionListener {

    private final PostCloseCycleService postCloseCycleService;

    @Override
    public void beforeJob(JobExecution jobExecution) {
        Long cycleId = jobExecution.getJobParameters().getLong(StockBatchJobParameters.CYCLE_ID);
        if (cycleId == null) {
            return;
        }
        postCloseCycleService.linkOwnedBatchJobExecution(
                cycleId,
                jobExecution.getId(),
                LocalDateTime.now()
        );
    }
}
