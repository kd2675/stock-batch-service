package stock.batch.service.batch.config;

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.EnableJdbcJobRepository;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableBatchProcessing
@EnableJdbcJobRepository(
        dataSourceRef = BatchRepositoryDataSourceConfig.BATCH_METADATA_DATA_SOURCE,
        transactionManagerRef = BatchRepositoryDataSourceConfig.BATCH_METADATA_TRANSACTION_MANAGER,
        tablePrefix = "BATCH_"
)
public class BatchRepositoryConfig {
}
