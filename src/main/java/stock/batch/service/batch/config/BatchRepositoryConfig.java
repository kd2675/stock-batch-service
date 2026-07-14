package stock.batch.service.batch.config;

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.EnableJdbcJobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@EnableBatchProcessing(taskExecutorRef = BatchRepositoryConfig.STOCK_BATCH_JOB_TASK_EXECUTOR)
@EnableJdbcJobRepository(
        dataSourceRef = BatchRepositoryDataSourceConfig.BATCH_METADATA_DATA_SOURCE,
        transactionManagerRef = BatchRepositoryDataSourceConfig.BATCH_METADATA_TRANSACTION_MANAGER,
        tablePrefix = "BATCH_"
)
public class BatchRepositoryConfig {

    public static final String STOCK_BATCH_JOB_TASK_EXECUTOR = "stockBatchJobTaskExecutor";
    public static final String STOCK_BATCH_TASKLET_TRANSACTION_MANAGER = "stockBatchTaskletTransactionManager";

    @Bean(name = STOCK_BATCH_JOB_TASK_EXECUTOR)
    public TaskExecutor stockBatchJobTaskExecutor() {
        return new SyncTaskExecutor();
    }

    @Bean(name = STOCK_BATCH_TASKLET_TRANSACTION_MANAGER)
    public PlatformTransactionManager stockBatchTaskletTransactionManager() {
        return new ResourcelessTransactionManager();
    }
}
