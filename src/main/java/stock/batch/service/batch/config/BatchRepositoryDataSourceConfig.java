package stock.batch.service.batch.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class BatchRepositoryDataSourceConfig {

    public static final String BUSINESS_DATA_SOURCE = "stockBusinessDataSource";
    public static final String BUSINESS_TRANSACTION_MANAGER = "stockBusinessTransactionManager";
    public static final String BATCH_METADATA_DATA_SOURCE = "batchMetadataDataSource";
    public static final String BATCH_METADATA_TRANSACTION_MANAGER = "batchMetadataTransactionManager";

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties stockBusinessDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = BUSINESS_DATA_SOURCE)
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource stockBusinessDataSource(
            @Qualifier("stockBusinessDataSourceProperties") DataSourceProperties properties
    ) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean(name = BUSINESS_TRANSACTION_MANAGER)
    @Primary
    public PlatformTransactionManager stockBusinessTransactionManager(
            @Qualifier(BUSINESS_DATA_SOURCE) DataSource dataSource
    ) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    @ConfigurationProperties("stock.batch.repository.datasource")
    public DataSourceProperties batchMetadataDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = BATCH_METADATA_DATA_SOURCE)
    @ConfigurationProperties("stock.batch.repository.datasource.hikari")
    public DataSource batchMetadataDataSource(
            @Qualifier("batchMetadataDataSourceProperties") DataSourceProperties properties
    ) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean(name = BATCH_METADATA_TRANSACTION_MANAGER)
    public PlatformTransactionManager batchMetadataTransactionManager(
            @Qualifier(BATCH_METADATA_DATA_SOURCE) DataSource dataSource
    ) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(@Qualifier(BUSINESS_DATA_SOURCE) DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
