package stock.batch.service.batch.common.support;

import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import stock.batch.service.batch.config.BatchRepositoryDataSourceConfig;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class StockBatchJobRepositoryIntegrationTest {

    @Autowired
    private StockBatchJobLauncher stockBatchJobLauncher;

    @Autowired
    @Qualifier(BatchRepositoryDataSourceConfig.BATCH_METADATA_DATA_SOURCE)
    private DataSource batchMetadataDataSource;

    @Autowired
    @Qualifier(BatchRepositoryDataSourceConfig.BUSINESS_DATA_SOURCE)
    private DataSource businessDataSource;

    private JdbcTemplate batchMetadataJdbcTemplate() {
        return new JdbcTemplate(batchMetadataDataSource);
    }

    private JdbcTemplate businessJdbcTemplate() {
        return new JdbcTemplate(businessDataSource);
    }

    @Test
    void springBatchMetadataTables_areStoredInSeparateRepositoryDataSource() throws SQLException {
        assertThat(tableExists(businessDataSource, "BATCH_JOB_INSTANCE")).isFalse();
        assertThat(tableExists(batchMetadataDataSource, "BATCH_JOB_INSTANCE")).isTrue();
    }

    @Test
    void refreshMarketData_recordsJobAndStepExecutionInSpringBatchRepository() {
        var response = stockBatchJobLauncher.refreshMarketData();

        assertThat(response.status()).isEqualTo("COMPLETED");
        Long jobExecutionCount = batchMetadataJdbcTemplate().queryForObject(
                """
                select count(*)
                from BATCH_JOB_INSTANCE ji
                join BATCH_JOB_EXECUTION je on je.JOB_INSTANCE_ID = ji.JOB_INSTANCE_ID
                where ji.JOB_NAME = ?
                  and je.STATUS = 'COMPLETED'
                """,
                Long.class,
                "market-data-refresh"
        );
        Long stepExecutionCount = batchMetadataJdbcTemplate().queryForObject(
                """
                select count(*)
                from BATCH_STEP_EXECUTION se
                join BATCH_JOB_EXECUTION je on je.JOB_EXECUTION_ID = se.JOB_EXECUTION_ID
                join BATCH_JOB_INSTANCE ji on ji.JOB_INSTANCE_ID = je.JOB_INSTANCE_ID
                where ji.JOB_NAME = ?
                  and se.STEP_NAME = ?
                  and se.STATUS = 'COMPLETED'
                """,
                Long.class,
                "market-data-refresh",
                "market-data-refreshStep"
        );

        assertThat(jobExecutionCount).isPositive();
        assertThat(stepExecutionCount).isPositive();
    }

    private boolean tableExists(DataSource dataSource, String tableName) throws SQLException {
        try (var connection = dataSource.getConnection();
             var tables = connection.getMetaData().getTables(null, null, tableName, new String[]{"TABLE"})) {
            return tables.next();
        }
    }
}
