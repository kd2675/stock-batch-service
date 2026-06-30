package stock.batch.service.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class StockBatchSchedulerConfigurationContractTest {

    private static final Pattern CONDITIONAL_ENABLED_PREFIX = Pattern.compile(
            "@ConditionalOnProperty\\(prefix = \"([^\"]+)\", name = \"enabled\""
    );
    private static final Pattern VALUE_ENABLED_PROPERTY = Pattern.compile(
            "@Value\\(\"\\$\\{([^:}]+\\.enabled):true}\"\\)"
    );
    private static final Pattern DEFAULT_NUMBER_PLACEHOLDER = Pattern.compile("\\$\\{[^:}]+:(\\d+)}");
    private static final List<String> REQUIRED_MYSQL_JDBC_OPTIONS = List.of(
            "connectTimeout=5000",
            "socketTimeout=30000",
            "tcpKeepAlive=true"
    );

    @Test
    void schedulerEnabledProperties_areExternallyConfigurable() throws IOException {
        PropertySource<?> applicationProperties = loadApplicationProperties();

        for (String propertyName : findSchedulerEnabledProperties()) {
            Object configuredValue = applicationProperties.getProperty(propertyName);

            assertThat(configuredValue)
                    .as(propertyName)
                    .isInstanceOf(String.class);
            assertThat((String) configuredValue)
                    .as(propertyName)
                    .startsWith("${")
                    .endsWith(":true}");
        }
    }

    @Test
    void runtimeControlState_isStoredInDatabaseNotExternalRuntimeEnabledProperties() throws IOException {
        String applicationConfig = Files.readString(Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8);
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);
        List<Path> sourceFiles = Files.walk(Path.of("src/main/java/stock/batch/service"))
                .filter(path -> path.getFileName().toString().endsWith(".java"))
                .sorted()
                .toList();

        assertThat(applicationConfig).doesNotContain("runtime-enabled", "_RUNTIME_ENABLED");
        assertThat(readme).doesNotContain("runtime-enabled", "_RUNTIME_ENABLED");
        for (Path sourceFile : sourceFiles) {
            assertThat(Files.readString(sourceFile, StandardCharsets.UTF_8))
                    .as(sourceFile.toString())
                    .doesNotContain("runtime-enabled", "_RUNTIME_ENABLED");
        }
    }

    @Test
    void schedulerControlProperties_areDocumentedInReadme() throws IOException {
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);

        for (String propertyName : schedulerControlProperties()) {
            assertThat(readme).as(propertyName).contains(propertyName);
        }
    }

    @Test
    void jobLockDefaults_keepHeartbeatShorterThanLockTtl() throws IOException {
        PropertySource<?> applicationProperties = loadApplicationProperties();

        int lockTtlSeconds = defaultNumber(applicationProperties.getProperty("stock.batch.job-lock.ttl-seconds"));
        int heartbeatIntervalSeconds = defaultNumber(applicationProperties.getProperty("stock.batch.job-lock.heartbeat-interval-seconds"));

        assertThat(lockTtlSeconds).isPositive();
        assertThat(heartbeatIntervalSeconds).isPositive();
        assertThat(heartbeatIntervalSeconds).isLessThan(lockTtlSeconds);
    }

    @Test
    void shutdownConfiguration_waitsForRunningScheduledJobs() throws IOException {
        PropertySource<?> applicationProperties = loadApplicationProperties();

        assertThat(applicationProperties.getProperty("server.shutdown"))
                .isEqualTo("${STOCK_BATCH_SERVER_SHUTDOWN:graceful}");
        assertThat(applicationProperties.getProperty("spring.task.scheduling.shutdown.await-termination"))
                .isEqualTo("${STOCK_BATCH_SCHEDULING_AWAIT_TERMINATION:true}");
        assertThat(applicationProperties.getProperty("spring.task.scheduling.shutdown.await-termination-period"))
                .isEqualTo("${STOCK_BATCH_SCHEDULING_AWAIT_TERMINATION_PERIOD:60s}");
        assertThat(applicationProperties.getProperty("spring.lifecycle.timeout-per-shutdown-phase"))
                .isEqualTo("${STOCK_BATCH_SHUTDOWN_TIMEOUT:70s}");
        assertThat(applicationProperties.getProperty("stock.batch.shutdown.await-running-jobs-seconds"))
                .isEqualTo("${STOCK_BATCH_SHUTDOWN_AWAIT_RUNNING_JOBS_SECONDS:60}");
    }

    @Test
    void prodProfile_requiresExplicitInternalBatchTokenAndDisallowsEmptyToken() throws IOException {
        String prodConfig = Files.readString(Path.of("src/main/resources/application-prod.yml"), StandardCharsets.UTF_8);

        assertRequiresExplicitInternalBatchTokenAndDisallowsEmptyToken(prodConfig);
    }

    @Test
    void devProfile_requiresExplicitInternalBatchTokenAndDisallowsEmptyToken() throws IOException {
        String devConfig = Files.readString(Path.of("src/main/resources/application-dev.yml"), StandardCharsets.UTF_8);

        assertRequiresExplicitInternalBatchTokenAndDisallowsEmptyToken(devConfig);
    }

    @Test
    void localProfile_usesSharedLocalInternalTokenAndDisallowsEmptyToken() throws IOException {
        String localConfig = Files.readString(Path.of("src/main/resources/application-local.yml"), StandardCharsets.UTF_8);

        assertThat(localConfig).contains("token: ${STOCK_BATCH_INTERNAL_TOKEN:local-stock-batch-internal-token}");
        assertThat(localConfig).contains("allow-empty-token: false");
        assertThat(localConfig).doesNotContain("allow-empty-token: true");
    }

    @Test
    void mysqlJdbcUrls_defineTimeoutAndKeepAliveOptions() throws IOException {
        for (Path configPath : List.of(
                Path.of("src/main/resources/application.yml"),
                Path.of("src/main/resources/application-local.yml"),
                Path.of("src/main/resources/application-dev.yml"),
                Path.of("src/main/resources/application-prod.yml")
        )) {
            assertMysqlJdbcUrlOptions(configPath);
        }
    }

    private void assertRequiresExplicitInternalBatchTokenAndDisallowsEmptyToken(String config) {
        assertThat(config).contains("token: ${STOCK_BATCH_INTERNAL_TOKEN}");
        assertThat(config).contains("allow-empty-token: false");
        assertThat(config).doesNotContain("token: ${STOCK_BATCH_INTERNAL_TOKEN:");
        assertThat(config).doesNotContain("allow-empty-token: true");
    }

    private void assertMysqlJdbcUrlOptions(Path configPath) throws IOException {
        List<String> mysqlUrlLines = Files.readAllLines(configPath, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> line.startsWith("url:"))
                .filter(line -> line.contains("jdbc:mysql")
                        || line.contains("STOCK_DB_URL")
                        || line.contains("STOCK_BATCH_DB_URL"))
                .toList();

        assertThat(mysqlUrlLines).as(configPath.toString()).isNotEmpty();
        for (String mysqlUrlLine : mysqlUrlLines) {
            assertThat(mysqlUrlLine)
                    .as(configPath + " " + mysqlUrlLine)
                    .contains(REQUIRED_MYSQL_JDBC_OPTIONS.toArray(String[]::new));
        }
    }

    private List<String> findSchedulerEnabledProperties() throws IOException {
        try (var paths = Files.walk(Path.of("src/main/java/stock/batch/service/scheduler"))) {
            List<Path> schedulerFiles = paths
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();

            List<String> conditionalProperties = schedulerFiles.stream()
                    .flatMap(path -> matcherResults(path, CONDITIONAL_ENABLED_PREFIX))
                    .map(match -> match.group(1) + ".enabled")
                    .toList();
            List<String> valueProperties = schedulerFiles.stream()
                    .flatMap(path -> matcherResults(path, VALUE_ENABLED_PROPERTY))
                    .map(match -> match.group(1))
                    .toList();

            return java.util.stream.Stream.concat(conditionalProperties.stream(), valueProperties.stream())
                    .distinct()
                    .sorted()
                    .toList();
        }
    }

    private java.util.stream.Stream<java.util.regex.MatchResult> matcherResults(Path path, Pattern pattern) {
        try {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            return pattern.matcher(source).results();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read scheduler source: " + path, ex);
        }
    }

    private PropertySource<?> loadApplicationProperties() throws IOException {
        return new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .get(0);
    }

    private int defaultNumber(Object propertyValue) {
        assertThat(propertyValue).isInstanceOf(String.class);
        var matcher = DEFAULT_NUMBER_PLACEHOLDER.matcher((String) propertyValue);
        assertThat(matcher.matches()).as((String) propertyValue).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private List<String> schedulerControlProperties() throws IOException {
        return findSchedulerEnabledProperties().stream()
                .sorted()
                .toList();
    }
}
