package stock.batch.service.batch.config;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import stock.batch.service.testsupport.BatchTestDatabaseFactory;

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
                new FileSystemResource("missing-batch-schema-does-not-exist.sql")
        );

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
    }

    @Test
    void runner_initializeTrueAndMissingSchemaResource_failsFast() {
        BatchMetadataSchemaInitializer initializer = new BatchMetadataSchemaInitializer();
        var missingSchema = new FileSystemResource("missing-batch-schema-does-not-exist.sql");
        var runner = initializer.batchMetadataSchemaApplicationRunner(createDataSource(), true, missingSchema);

        assertThatThrownBy(() -> runner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Batch metadata schema resource does not exist");
    }

    @Test
    void runner_initializeTrue_executesSchemaResource() throws Exception {
        DataSource dataSource = createDataSource();
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

    private DataSource createDataSource() {
        return BatchTestDatabaseFactory.createDataSource("batch_schema_initializer_test");
    }

    private boolean tableExists(DataSource dataSource, String tableName) throws SQLException {
        try (var connection = dataSource.getConnection();
             var tables = connection.getMetaData().getTables(null, null, tableName, new String[]{"TABLE"})) {
            return tables.next();
        }
    }
}
