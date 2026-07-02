package stock.batch.service.batch.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static stock.batch.service.batch.config.BatchRepositoryDataSourceConfig.BATCH_METADATA_DATA_SOURCE;
import static stock.batch.service.batch.config.BatchRepositoryDataSourceConfig.BUSINESS_DATA_SOURCE;

class BatchRepositoryDataSourceConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(BatchRepositoryDataSourceConfig.class)
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:stock_business_binding_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                    "spring.datasource.driver-class-name=org.h2.Driver",
                    "spring.datasource.username=sa",
                    "spring.datasource.password=",
                    "spring.datasource.hikari.pool-name=stock-business-pool",
                    "spring.datasource.hikari.maximum-pool-size=8",
                    "spring.datasource.hikari.minimum-idle=1",
                    "spring.datasource.hikari.connection-timeout=30000",
                    "spring.datasource.hikari.validation-timeout=5000",
                    "spring.datasource.hikari.idle-timeout=240000",
                    "spring.datasource.hikari.max-lifetime=300000",
                    "spring.datasource.hikari.keepalive-time=120000",
                    "stock.batch.repository.datasource.url=jdbc:h2:mem:stock_metadata_binding_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                    "stock.batch.repository.datasource.driver-class-name=org.h2.Driver",
                    "stock.batch.repository.datasource.username=sa",
                    "stock.batch.repository.datasource.password=",
                    "stock.batch.repository.datasource.hikari.pool-name=stock-batch-metadata-pool",
                    "stock.batch.repository.datasource.hikari.maximum-pool-size=4",
                    "stock.batch.repository.datasource.hikari.minimum-idle=1",
                    "stock.batch.repository.datasource.hikari.connection-timeout=30000",
                    "stock.batch.repository.datasource.hikari.validation-timeout=5000",
                    "stock.batch.repository.datasource.hikari.idle-timeout=240000",
                    "stock.batch.repository.datasource.hikari.max-lifetime=300000",
                    "stock.batch.repository.datasource.hikari.keepalive-time=120000",
                    "stock.batch.jdbc.query-timeout-seconds=25"
            );

    @Test
    void dataSources_bindHikariPoolTimeoutsAndNames() {
        contextRunner.run(context -> {
            assertHikariDataSource(
                    context.getBean(BUSINESS_DATA_SOURCE, DataSource.class),
                    "stock-business-pool",
                    8
            );
            assertHikariDataSource(
                    context.getBean(BATCH_METADATA_DATA_SOURCE, DataSource.class),
                    "stock-batch-metadata-pool",
                    4
            );
        });
    }

    @Test
    void jdbcTemplate_appliesConfiguredQueryTimeout() {
        contextRunner.run(context -> {
            JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);

            assertThat(jdbcTemplate.getQueryTimeout()).isEqualTo(25);
        });
    }

    @Test
    void jdbcTemplate_rejectsNonPositiveQueryTimeout() {
        contextRunner.withPropertyValues("stock.batch.jdbc.query-timeout-seconds=0")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseInstanceOf(IllegalArgumentException.class)
                        .hasRootCauseMessage("stock.batch.jdbc.query-timeout-seconds must be positive"));
    }

    private void assertHikariDataSource(DataSource dataSource, String poolName, int maximumPoolSize) {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);

        HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
        assertThat(hikariDataSource.getPoolName()).isEqualTo(poolName);
        assertThat(hikariDataSource.getMaximumPoolSize()).isEqualTo(maximumPoolSize);
        assertThat(hikariDataSource.getMinimumIdle()).isEqualTo(1);
        assertThat(hikariDataSource.getConnectionTimeout()).isEqualTo(30_000);
        assertThat(hikariDataSource.getValidationTimeout()).isEqualTo(5_000);
        assertThat(hikariDataSource.getIdleTimeout()).isEqualTo(240_000);
        assertThat(hikariDataSource.getMaxLifetime()).isEqualTo(300_000);
        assertThat(hikariDataSource.getKeepaliveTime()).isEqualTo(120_000);
        assertThat(hikariDataSource.getKeepaliveTime()).isLessThan(hikariDataSource.getMaxLifetime());
        assertThat(hikariDataSource.getValidationTimeout()).isLessThan(hikariDataSource.getConnectionTimeout());
    }
}
