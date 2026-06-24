package stock.batch.service.batch.common.support;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StockBatchJobRepositoryRecorder {

    private static final String STEP_SUFFIX = "Step";

    private final JobRepository jobRepository;

    public StockBatchJobExecutionRecord start(StockBatchJob job, LocalDateTime startedAt) {
        JobParameters jobParameters = new JobParametersBuilder()
                .addLocalDate("businessDate", LocalDate.now(), true)
                .addString("jobMode", job.executionMode(), true)
                .addString("runId", UUID.randomUUID().toString(), true)
                .addString("requestId", UUID.randomUUID().toString(), false)
                .addLocalDateTime("triggeredAt", startedAt, false)
                .addString("triggeredBy", "stock-batch-service", false)
                .toJobParameters();

        JobInstance jobInstance = jobRepository.createJobInstance(job.jobName(), jobParameters);
        JobExecution jobExecution = jobRepository.createJobExecution(jobInstance, jobParameters, new ExecutionContext());
        jobExecution.setStartTime(startedAt);
        jobExecution.setStatus(BatchStatus.STARTED);
        jobExecution.setExitStatus(ExitStatus.EXECUTING);
        jobRepository.update(jobExecution);

        StepExecution stepExecution = jobRepository.createStepExecution(job.jobName() + STEP_SUFFIX, jobExecution);
        stepExecution.setStartTime(startedAt);
        stepExecution.setStatus(BatchStatus.STARTED);
        stepExecution.setExitStatus(ExitStatus.EXECUTING);
        jobRepository.update(stepExecution);

        return new StockBatchJobExecutionRecord(jobExecution, stepExecution);
    }

    public void complete(StockBatchJobExecutionRecord record, int processedCount, LocalDateTime endedAt) {
        StepExecution stepExecution = record.stepExecution();
        stepExecution.setWriteCount(processedCount);
        stepExecution.setCommitCount(1);
        stepExecution.setStatus(BatchStatus.COMPLETED);
        stepExecution.setExitStatus(ExitStatus.COMPLETED.addExitDescription("processedCount=" + processedCount));
        stepExecution.setEndTime(endedAt);
        jobRepository.update(stepExecution);

        JobExecution jobExecution = record.jobExecution();
        jobExecution.setStatus(BatchStatus.COMPLETED);
        jobExecution.setExitStatus(ExitStatus.COMPLETED.addExitDescription("processedCount=" + processedCount));
        jobExecution.setEndTime(endedAt);
        jobRepository.update(jobExecution);
    }

    public void skip(StockBatchJobExecutionRecord record, LocalDateTime endedAt) {
        StepExecution stepExecution = record.stepExecution();
        stepExecution.setStatus(BatchStatus.COMPLETED);
        stepExecution.setExitStatus(ExitStatus.NOOP.addExitDescription("Job is already running"));
        stepExecution.setEndTime(endedAt);
        jobRepository.update(stepExecution);

        JobExecution jobExecution = record.jobExecution();
        jobExecution.setStatus(BatchStatus.COMPLETED);
        jobExecution.setExitStatus(ExitStatus.NOOP.addExitDescription("Job is already running"));
        jobExecution.setEndTime(endedAt);
        jobRepository.update(jobExecution);
    }

    public void fail(StockBatchJobExecutionRecord record, RuntimeException ex, LocalDateTime endedAt) {
        StepExecution stepExecution = record.stepExecution();
        stepExecution.setStatus(BatchStatus.FAILED);
        stepExecution.setExitStatus(ExitStatus.FAILED.addExitDescription(ex));
        stepExecution.addFailureException(ex);
        stepExecution.setEndTime(endedAt);
        jobRepository.update(stepExecution);

        JobExecution jobExecution = record.jobExecution();
        jobExecution.setStatus(BatchStatus.FAILED);
        jobExecution.setExitStatus(ExitStatus.FAILED.addExitDescription(ex));
        jobExecution.addFailureException(ex);
        jobExecution.setEndTime(endedAt);
        jobRepository.update(jobExecution);
    }
}
