package stock.batch.service.batch.config;

import javax.sql.DataSource;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@Configuration
@RequiredArgsConstructor
public class BatchMetadataSchemaInitializer {

    @Bean
    public ApplicationRunner batchMetadataSchemaApplicationRunner(
            @Qualifier(BatchRepositoryDataSourceConfig.BATCH_METADATA_DATA_SOURCE) DataSource dataSource,
            @Value("${stock.batch.repository.schema.initialize:false}") boolean initialize,
            @Value("${stock.batch.repository.schema.location:}") Resource schema
    ) {
        return args -> {
            if (!initialize || !schema.exists()) {
                return;
            }
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator(schema);
            populator.execute(dataSource);
        };
    }
}
