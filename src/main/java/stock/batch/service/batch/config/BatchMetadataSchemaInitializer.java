package stock.batch.service.batch.config;

import javax.sql.DataSource;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@Configuration
@RequiredArgsConstructor
public class BatchMetadataSchemaInitializer {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public ApplicationRunner batchMetadataSchemaApplicationRunner(
            @Qualifier(BatchRepositoryDataSourceConfig.BATCH_METADATA_DATA_SOURCE) DataSource dataSource,
            @Value("${stock.batch.repository.schema.initialize:false}") boolean initialize,
            @Value("${stock.batch.repository.schema.location:}") Resource schema
    ) {
        return args -> {
            if (!initialize) {
                return;
            }
            if (!schema.exists()) {
                throw new IllegalStateException("Batch metadata schema resource does not exist: " + schema);
            }
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator(schema);
            populator.execute(dataSource);
        };
    }
}
