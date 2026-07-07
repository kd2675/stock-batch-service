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
        assertThat(lockTtlSeconds).isEqualTo(180);
        assertThat(lockTtlSeconds).isGreaterThanOrEqualTo(heartbeatIntervalSeconds * 3);
    }

    @Test
    void jdbcQueryTimeoutDefault_isPositiveAndExternallyConfigurable() throws IOException {
        PropertySource<?> applicationProperties = loadApplicationProperties();

        Object propertyValue = applicationProperties.getProperty("stock.batch.jdbc.query-timeout-seconds");

        assertThat(propertyValue).isEqualTo("${STOCK_BATCH_JDBC_QUERY_TIMEOUT_SECONDS:30}");
        assertThat(defaultNumber(propertyValue)).isPositive();
    }

    @Test
    void orderBookExecutionLockRetryDefaults_areExternallyConfigurableAndBounded() throws IOException {
        PropertySource<?> applicationProperties = loadApplicationProperties();

        assertThat(applicationProperties.getProperty("stock.batch.execution.scan-limit"))
                .isEqualTo("${STOCK_BATCH_EXECUTION_SCAN_LIMIT:300}");
        assertThat(applicationProperties.getProperty("stock.batch.execution.worker-count"))
                .isEqualTo("${STOCK_BATCH_EXECUTION_WORKER_COUNT:3}");
        assertThat(applicationProperties.getProperty("stock.batch.execution.buy-candidate-scan-limit"))
                .isEqualTo("${STOCK_BATCH_EXECUTION_BUY_CANDIDATE_SCAN_LIMIT:20}");
        assertThat(applicationProperties.getProperty("stock.batch.execution.ready-symbol-fallback-scan-limit"))
                .isEqualTo("${STOCK_BATCH_EXECUTION_READY_SYMBOL_FALLBACK_SCAN_LIMIT:8}");
        assertThat(applicationProperties.getProperty("stock.batch.execution.deadlock-retry-max-attempts"))
                .isEqualTo("${STOCK_BATCH_EXECUTION_DEADLOCK_RETRY_MAX_ATTEMPTS:3}");
        assertThat(applicationProperties.getProperty("stock.batch.execution.deadlock-retry-backoff-ms"))
                .isEqualTo("${STOCK_BATCH_EXECUTION_DEADLOCK_RETRY_BACKOFF_MS:50}");
        assertThat(applicationProperties.getProperty("stock.batch.execution.thread-pool.core-size"))
                .isEqualTo("${STOCK_BATCH_EXECUTION_THREAD_POOL_CORE_SIZE:3}");
        assertThat(applicationProperties.getProperty("stock.batch.execution.thread-pool.max-size"))
                .isEqualTo("${STOCK_BATCH_EXECUTION_THREAD_POOL_MAX_SIZE:6}");
        assertThat(applicationProperties.getProperty("stock.batch.execution.thread-pool.queue-capacity"))
                .isEqualTo("${STOCK_BATCH_EXECUTION_THREAD_POOL_QUEUE_CAPACITY:0}");
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.execution.scan-limit"))).isGreaterThan(0);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.execution.worker-count"))).isBetween(1, 16);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.execution.buy-candidate-scan-limit"))).isBetween(1, 100);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.execution.deadlock-retry-max-attempts"))).isBetween(1, 10);
    }

    @Test
    void autoMarketGenerationDefaults_areExternallyConfigurableAndBounded() throws IOException {
        PropertySource<?> applicationProperties = loadApplicationProperties();

        assertThat(applicationProperties.getProperty("stock.batch.auto-market.fixed-delay-ms"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_FIXED_DELAY_MS:3000}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.daily-regime.fixed-delay-ms"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_DAILY_REGIME_FIXED_DELAY_MS:10000}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.daily-regime.pre-create-before-minutes"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_DAILY_REGIME_PRE_CREATE_BEFORE_MINUTES:30}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.generation-profile-worker-count"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_GENERATION_PROFILE_WORKER_COUNT:12}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.generation-due-limit-per-symbol"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_GENERATION_DUE_LIMIT_PER_SYMBOL:100}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.profile-queue.reconcile-enabled"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_PROFILE_QUEUE_RECONCILE_ENABLED:true}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.profile-queue.reconcile-fixed-delay-ms"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_PROFILE_QUEUE_RECONCILE_FIXED_DELAY_MS:30000}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.profile-queue.reconcile-limit"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_PROFILE_QUEUE_RECONCILE_LIMIT:100}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.deadlock-retry-max-attempts"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_DEADLOCK_RETRY_MAX_ATTEMPTS:5}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.deadlock-retry-backoff-ms"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_DEADLOCK_RETRY_BACKOFF_MS:50}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.thread-pool.core-size"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_THREAD_POOL_CORE_SIZE:12}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.thread-pool.max-size"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_THREAD_POOL_MAX_SIZE:16}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.thread-pool.queue-capacity"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_THREAD_POOL_QUEUE_CAPACITY:0}");
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.auto-market.fixed-delay-ms"))).isBetween(2_000, 10_000);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.auto-market.daily-regime.fixed-delay-ms"))).isBetween(5_000, 10_000);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.auto-market.daily-regime.pre-create-before-minutes"))).isEqualTo(30);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.auto-market.generation-profile-worker-count"))).isBetween(1, 16);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.auto-market.generation-due-limit-per-symbol"))).isBetween(1, 500);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.auto-market.profile-queue.reconcile-fixed-delay-ms"))).isBetween(10_000, 60_000);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.auto-market.profile-queue.reconcile-limit"))).isBetween(1, 1_000);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.auto-market.deadlock-retry-max-attempts"))).isBetween(1, 10);
    }

    @Test
    void listingAutoMarketRetryDefaults_areExternallyConfigurableAndBounded() throws IOException {
        PropertySource<?> applicationProperties = loadApplicationProperties();

        assertThat(applicationProperties.getProperty("stock.batch.listing-auto-market.fixed-delay-ms"))
                .isEqualTo("${STOCK_BATCH_LISTING_AUTO_MARKET_FIXED_DELAY_MS:10000}");
        assertThat(applicationProperties.getProperty("stock.batch.listing-auto-market.deadlock-retry-max-attempts"))
                .isEqualTo("${STOCK_BATCH_LISTING_AUTO_MARKET_DEADLOCK_RETRY_MAX_ATTEMPTS:5}");
        assertThat(applicationProperties.getProperty("stock.batch.listing-auto-market.deadlock-retry-backoff-ms"))
                .isEqualTo("${STOCK_BATCH_LISTING_AUTO_MARKET_DEADLOCK_RETRY_BACKOFF_MS:50}");
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.listing-auto-market.fixed-delay-ms"))).isBetween(5_000, 10_000);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.listing-auto-market.deadlock-retry-max-attempts"))).isBetween(1, 10);
    }

    @Test
    void autoParticipantCashFlowDefaultPollInterval_isNotOneSecondDatabaseScan() throws IOException {
        PropertySource<?> applicationProperties = loadApplicationProperties();
        String schedulerSource = Files.readString(
                Path.of("src/main/java/stock/batch/service/scheduler/AutoParticipantCashFlowScheduler.java"),
                StandardCharsets.UTF_8
        );
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);

        Object propertyValue = applicationProperties.getProperty("stock.batch.auto-participant-cash-flow.fixed-delay-ms");

        assertThat(propertyValue).isEqualTo("${STOCK_BATCH_AUTO_PARTICIPANT_CASH_FLOW_FIXED_DELAY_MS:60000}");
        assertThat(defaultNumber(propertyValue)).isGreaterThanOrEqualTo(60_000);
        assertThat(schedulerSource).contains("stock.batch.auto-participant-cash-flow.fixed-delay-ms:60000");
        assertThat(readme).contains("기본값은 60000ms");
        assertThat(readme).contains("실제 서버 시간이 기준인 polling 간격");
    }

    @Test
    void shutdownConfiguration_waitsForRunningScheduledJobs() throws IOException {
        PropertySource<?> applicationProperties = loadApplicationProperties();

        assertThat(applicationProperties.getProperty("server.shutdown"))
                .isEqualTo("${STOCK_BATCH_SERVER_SHUTDOWN:graceful}");
        assertThat(applicationProperties.getProperty("spring.task.scheduling.shutdown.await-termination"))
                .isEqualTo("${STOCK_BATCH_SCHEDULING_AWAIT_TERMINATION:true}");
        assertThat(applicationProperties.getProperty("spring.task.scheduling.shutdown.await-termination-period"))
                .isEqualTo("${STOCK_BATCH_SCHEDULING_AWAIT_TERMINATION_PERIOD:120s}");
        assertThat(applicationProperties.getProperty("spring.lifecycle.timeout-per-shutdown-phase"))
                .isEqualTo("${STOCK_BATCH_SHUTDOWN_TIMEOUT:130s}");
        assertThat(applicationProperties.getProperty("stock.batch.shutdown.await-running-jobs-seconds"))
                .isEqualTo("${STOCK_BATCH_SHUTDOWN_AWAIT_RUNNING_JOBS_SECONDS:120}");
    }

    @Test
    void dedicatedSchedulerPoolDefaults_areExternallyConfigurableAndBounded() throws IOException {
        PropertySource<?> applicationProperties = loadApplicationProperties();

        assertThat(applicationProperties.getProperty("stock.batch.scheduler-pools.shutdown-await-seconds"))
                .isEqualTo("${STOCK_BATCH_SCHEDULER_POOL_SHUTDOWN_AWAIT_SECONDS:120}");
        assertThat(applicationProperties.getProperty("stock.batch.scheduler-pools.execution.pool-size"))
                .isEqualTo("${STOCK_BATCH_EXECUTION_SCHEDULER_POOL_SIZE:2}");
        assertThat(applicationProperties.getProperty("stock.batch.scheduler-pools.auto-market.pool-size"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_SCHEDULER_POOL_SIZE:2}");
        assertThat(applicationProperties.getProperty("stock.batch.scheduler-pools.maintenance.pool-size"))
                .isEqualTo("${STOCK_BATCH_MAINTENANCE_SCHEDULER_POOL_SIZE:2}");
        assertThat(applicationProperties.getProperty("stock.batch.scheduler-pools.simulation-clock.pool-size"))
                .isEqualTo("${STOCK_BATCH_SIMULATION_CLOCK_SCHEDULER_POOL_SIZE:1}");

        int defaultTotalSchedulerThreads = defaultNumber(applicationProperties.getProperty("stock.batch.scheduler-pools.execution.pool-size"))
                + defaultNumber(applicationProperties.getProperty("stock.batch.scheduler-pools.auto-market.pool-size"))
                + defaultNumber(applicationProperties.getProperty("stock.batch.scheduler-pools.maintenance.pool-size"))
                + defaultNumber(applicationProperties.getProperty("stock.batch.scheduler-pools.simulation-clock.pool-size"));

        assertThat(defaultTotalSchedulerThreads).isLessThanOrEqualTo(7);
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
