package stock.batch.service.batch.common.policy;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import stock.batch.service.common.vo.BatchJobRuntimeStatusResponse;
import stock.batch.service.testsupport.BatchTestDatabaseFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class BatchJobRuntimeCatalogContractTest {

    private static final Pattern JOB_NAME_PATTERN = Pattern.compile(
            "public\\s+static\\s+final\\s+String\\s+JOB_NAME\\s*=\\s*\"([^\"]+)\""
    );

    @Test
    void runtimeCatalog_coversEveryStockBatchJobImplementation() throws IOException {
        List<String> jobImplementationNames = findStockBatchJobNamesFromSource();
        BatchJobRuntimeCatalog catalog = createRuntimeCatalog();

        List<String> catalogJobNames = catalog.statuses().stream()
                .map(response -> response.jobName())
                .toList();

        assertThat(catalogJobNames).containsExactlyInAnyOrderElementsOf(jobImplementationNames);
    }

    @Test
    void runtimeCatalog_configuresMarketCloseAndSettlementIndependently() {
        BatchJobRuntimeCatalog catalog = createRuntimeCatalog(false, true);

        assertThat(catalog.status("market-close-rollover").schedulerConfigured()).isFalse();
        assertThat(catalog.status("portfolio-settlement").schedulerConfigured()).isTrue();
    }

    @Test
    void runtimeCatalog_schedulerConfiguredFalse_keepsRuntimeRowEnabledButEffectiveDisabledForEveryJob() {
        BatchJobRuntimeCatalog catalog = new BatchJobRuntimeCatalog(
                new BatchJobRuntimeControl(createJdbcTemplate()),
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false
        );

        for (BatchJobRuntimeStatusResponse status : catalog.statuses()) {
            assertThat(status.schedulerConfigured()).as(status.jobName()).isFalse();
            assertThat(status.runtimeEnabled()).as(status.jobName()).isTrue();
            assertThat(status.effectiveEnabled()).as(status.jobName()).isFalse();
        }
    }

    private List<String> findStockBatchJobNamesFromSource() throws IOException {
        try (var paths = Files.walk(Path.of("src/main/java/stock/batch/service"))) {
            return paths
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(this::isStockBatchJobImplementation)
                    .filter(this::hasStaticJobName)
                    .map(this::readJobName)
                    .sorted()
                    .toList();
        }
    }

    private boolean isStockBatchJobImplementation(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).contains("implements StockBatchJob");
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read " + path, ex);
        }
    }

    private boolean hasStaticJobName(Path path) {
        try {
            return JOB_NAME_PATTERN.matcher(Files.readString(path, StandardCharsets.UTF_8)).find();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read " + path, ex);
        }
    }

    private String readJobName(Path path) {
        try {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            var matcher = JOB_NAME_PATTERN.matcher(source);
            assertThat(matcher.find()).as(path.toString()).isTrue();
            return matcher.group(1);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read " + path, ex);
        }
    }

    private BatchJobRuntimeCatalog createRuntimeCatalog() {
        return createRuntimeCatalog(true, true);
    }

    private BatchJobRuntimeCatalog createRuntimeCatalog(boolean marketCloseConfigured, boolean settlementConfigured) {
        return new BatchJobRuntimeCatalog(
                new BatchJobRuntimeControl(createJdbcTemplate()),
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                marketCloseConfigured,
                settlementConfigured,
                true
        );
    }

    private JdbcTemplate createJdbcTemplate() {
        return BatchTestDatabaseFactory.createJobControlJdbcTemplate("batch_job_runtime_catalog_contract");
    }
}
