package stock.batch.service.batch.config;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BatchMetadataSchemaInitializerTest {

    @Test
    void runner_initializeFalse_skipsMissingSchemaResource() {
        BatchMetadataSchemaInitializer initializer = new BatchMetadataSchemaInitializer();
        var runner = initializer.batchMetadataSchemaApplicationRunner(
                createDataSource(),
                false,
                new FileSystemResource("missing-batch-schema-%s.sql".formatted(UUID.randomUUID()))
        );

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
    }

    @Test
    void runner_initializeTrueAndMissingSchemaResource_failsFast() {
        BatchMetadataSchemaInitializer initializer = new BatchMetadataSchemaInitializer();
        var missingSchema = new FileSystemResource("missing-batch-schema-%s.sql".formatted(UUID.randomUUID()));
        var runner = initializer.batchMetadataSchemaApplicationRunner(createDataSource(), true, missingSchema);

        assertThatThrownBy(() -> runner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Batch metadata schema resource does not exist");
    }

    @Test
    void runner_initializeTrue_executesSchemaResource() throws Exception {
        DriverManagerDataSource dataSource = createDataSource();
        ByteArrayResource schema = new ByteArrayResource(
                """
                create table batch_schema_initializer_probe (
                  id bigint not null primary key
                );
                """.getBytes(StandardCharsets.UTF_8)
        );
        BatchMetadataSchemaInitializer initializer = new BatchMetadataSchemaInitializer();
        var runner = initializer.batchMetadataSchemaApplicationRunner(dataSource, true, schema);

        runner.run(null);

        assertThat(tableExists(dataSource, "batch_schema_initializer_probe")).isTrue();
    }

    private DriverManagerDataSource createDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:batch_schema_initializer_test_%s;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false".formatted(UUID.randomUUID()));
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private boolean tableExists(DriverManagerDataSource dataSource, String tableName) throws SQLException {
        try (var connection = dataSource.getConnection();
             var tables = connection.getMetaData().getTables(null, null, tableName, new String[]{"TABLE"})) {
            return tables.next();
        }
    }
}
