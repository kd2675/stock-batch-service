package stock.batch.service.batch.marketclose.job;

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

import stock.batch.service.batch.common.support.StockBatchJobParameters;
import stock.batch.service.batch.config.BatchRepositoryConfig;
import stock.batch.service.marketclose.biz.MarketCloseRolloverService;

@Configuration(proxyBeanMethods = false)
public class MarketCloseRolloverJob {

    public static final String JOB_NAME = "market-close-rollover";
    public static final String STEP_NAME = "market-close-snapshot-step";
    public static final String OPERATION_FULL = "FULL";
    public static final String OPERATION_SYMBOL = "SYMBOL";
    public static final String OPERATION_CANCEL_OPEN_ORDERS = "CANCEL_OPEN_ORDERS";

    @Bean(name = JOB_NAME)
    public Job marketCloseRolloverBatchJob(
            JobRepository jobRepository,
            @Qualifier(STEP_NAME) Step marketCloseSnapshotStep
    ) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(marketCloseSnapshotStep)
                .build();
    }

    @Bean(name = STEP_NAME)
    public Step marketCloseSnapshotStep(
            JobRepository jobRepository,
            @Qualifier(BatchRepositoryConfig.STOCK_BATCH_TASKLET_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager,
            MarketCloseRolloverService marketCloseRolloverService
    ) {
        Tasklet tasklet = (contribution, chunkContext) -> {
            var parameters = contribution.getStepExecution().getJobParameters();
            String operation = parameters.getString(StockBatchJobParameters.OPERATION);
            String symbol = parameters.getString(StockBatchJobParameters.SYMBOL);
            int processedCount = switch (operation) {
                case OPERATION_FULL -> marketCloseRolloverService.rolloverClosingPrices(
                        parameters.getLocalDate(StockBatchJobParameters.BUSINESS_DATE),
                        parameters.getLocalDateTime(StockBatchJobParameters.CLOSED_AT)
                );
                case OPERATION_SYMBOL -> marketCloseRolloverService.rolloverClosingPrices(
                        symbol,
                        parameters.getLocalDate(StockBatchJobParameters.BUSINESS_DATE),
                        parameters.getLocalDateTime(StockBatchJobParameters.CLOSED_AT)
                );
                case OPERATION_CANCEL_OPEN_ORDERS -> marketCloseRolloverService.cancelOpenOrderBookOrders(symbol);
                default -> throw new IllegalArgumentException("Unknown market close operation: " + operation);
            };
            contribution.incrementWriteCount(processedCount);
            return RepeatStatus.FINISHED;
        };
        return new StepBuilder(STEP_NAME, jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }
}
