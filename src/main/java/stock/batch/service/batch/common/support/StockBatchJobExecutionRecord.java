package stock.batch.service.batch.common.support;

import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;

public record StockBatchJobExecutionRecord(
        JobExecution jobExecution,
        StepExecution stepExecution
) {
}
