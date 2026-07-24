package stock.batch.service.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import stock.batch.service.scheduler.StockBatchSchedulerConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void envExample_usesLoadSafeCurrentSchedulerProperties() throws IOException {
        String envExample = Files.readString(Path.of(".env.example"), StandardCharsets.UTF_8);

        assertThat(envExample)
                .contains(
                        "STOCK_BATCH_AUTO_MARKET_FIXED_RATE_MS=5000",
                        "STOCK_BATCH_MARKET_CLOSE_POLL_FIXED_DELAY_MS=10000",
                        "STOCK_BATCH_POST_CLOSE_COORDINATOR_POLL_FIXED_DELAY_MS=10000",
                        "STOCK_BATCH_POST_CLOSE_RETRY_BASE_SECONDS=30",
                        "STOCK_BATCH_POST_CLOSE_RETRY_MAX_SECONDS=900",
                        "STOCK_BATCH_CORPORATE_ACTION_ACTION_BATCH_LIMIT=25",
                        "STOCK_BATCH_POST_CLOSE_REPORT_AGGREGATION_SYMBOL_CHUNK_SIZE=25",
                        "STOCK_BATCH_SIGNAL_HEARTBEAT_INTERVAL_SECONDS=30",
                        "STOCK_BATCH_SETTLEMENT_CHUNK_SIZE=200"
                )
                .doesNotContain(
                        "STOCK_BATCH_AUTO_MARKET_FIXED_DELAY_MS",
                        "STOCK_BATCH_SETTLEMENT_CRON",
                        "STOCK_BATCH_SETTLEMENT_ZONE"
                );

        assertThat(Files.readString(
                Path.of("src/main/resources/application.yml"),
                StandardCharsets.UTF_8
        )).doesNotContain("stock.batch.auto-market.fixed-delay-ms", "STOCK_BATCH_AUTO_MARKET_FIXED_DELAY_MS");
    }

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
    void signalLeaseDefaults_keepHeartbeatShorterThanClaimLease() throws IOException {
        PropertySource<?> applicationProperties = loadApplicationProperties();

        int leaseSeconds = defaultNumber(applicationProperties.getProperty("stock.batch.signal.lease-seconds"));
        int heartbeatSeconds = defaultNumber(
                applicationProperties.getProperty("stock.batch.signal.heartbeat-interval-seconds")
        );

        assertThat(leaseSeconds).isEqualTo(180);
        assertThat(heartbeatSeconds).isEqualTo(30);
        assertThat(heartbeatSeconds).isLessThan(leaseSeconds);
        assertThat(leaseSeconds).isGreaterThanOrEqualTo(heartbeatSeconds * 3);
    }

    @Test
    void overnightVolumeDefaults_boundActionAndReportCheckpointCohorts() throws IOException {
        PropertySource<?> applicationProperties = loadApplicationProperties();

        assertThat(applicationProperties.getProperty("stock.batch.corporate-action.action-batch-limit"))
                .isEqualTo("${STOCK_BATCH_CORPORATE_ACTION_ACTION_BATCH_LIMIT:25}");
        assertThat(applicationProperties.getProperty("stock.batch.post-close.report-aggregation.symbol-chunk-size"))
                .isEqualTo("${STOCK_BATCH_POST_CLOSE_REPORT_AGGREGATION_SYMBOL_CHUNK_SIZE:25}");
        assertThat(applicationProperties.getProperty("stock.batch.post-close.report-aggregation.funding-budget-chunk-size"))
                .isEqualTo("${STOCK_BATCH_POST_CLOSE_REPORT_AGGREGATION_FUNDING_BUDGET_CHUNK_SIZE:500}");
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.corporate-action.action-batch-limit")))
                .isEqualTo(25);
        assertThat(defaultNumber(
                applicationProperties.getProperty("stock.batch.post-close.report-aggregation.symbol-chunk-size")
        )).isEqualTo(25);
        assertThat(defaultNumber(
                applicationProperties.getProperty("stock.batch.post-close.report-aggregation.funding-budget-chunk-size")
        )).isEqualTo(500);
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
        assertThat(applicationProperties.getProperty("stock.batch.execution.buy-candidate-scan-limit"))
                .isEqualTo("${STOCK_BATCH_EXECUTION_BUY_CANDIDATE_SCAN_LIMIT:20}");
        assertThat(applicationProperties.getProperty("stock.batch.execution.ready-symbol-fallback-scan-limit"))
                .isEqualTo("${STOCK_BATCH_EXECUTION_READY_SYMBOL_FALLBACK_SCAN_LIMIT:8}");
        assertThat(applicationProperties.getProperty("stock.batch.execution.deadlock-retry-max-attempts"))
                .isEqualTo("${STOCK_BATCH_EXECUTION_DEADLOCK_RETRY_MAX_ATTEMPTS:3}");
        assertThat(applicationProperties.getProperty("stock.batch.execution.deadlock-retry-backoff-ms"))
                .isEqualTo("${STOCK_BATCH_EXECUTION_DEADLOCK_RETRY_BACKOFF_MS:50}");
        assertThat(applicationProperties.getProperty("stock.batch.execution.symbol-chunk-limit"))
                .isEqualTo("${STOCK_BATCH_EXECUTION_SYMBOL_CHUNK_LIMIT:5}");
        assertThat(applicationProperties.getProperty("stock.batch.execution.symbol-chunk-max-duration-ms"))
                .isEqualTo("${STOCK_BATCH_EXECUTION_SYMBOL_CHUNK_MAX_DURATION_MS:500}");
        assertThat(applicationProperties.getProperty("stock.batch.order-book-execution.fixed-delay-ms"))
                .isEqualTo("${STOCK_BATCH_ORDER_BOOK_EXECUTION_FIXED_DELAY_MS:30000}");
        assertThat(applicationProperties.getProperty("stock.batch.order-book-execution.worker.count"))
                .isEqualTo("${STOCK_BATCH_ORDER_BOOK_EXECUTION_WORKER_COUNT:2}");
        assertThat(applicationProperties.getProperty("stock.batch.order-book-execution.worker.idle-delay-ms"))
                .isEqualTo("${STOCK_BATCH_ORDER_BOOK_EXECUTION_WORKER_IDLE_DELAY_MS:100}");
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.execution.scan-limit"))).isGreaterThan(0);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.execution.buy-candidate-scan-limit"))).isBetween(1, 100);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.execution.deadlock-retry-max-attempts"))).isBetween(1, 10);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.execution.symbol-chunk-limit"))).isEqualTo(5);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.execution.symbol-chunk-max-duration-ms"))).isEqualTo(500);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.order-book-execution.fixed-delay-ms"))).isEqualTo(30_000);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.order-book-execution.worker.count"))).isEqualTo(2);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.order-book-execution.worker.idle-delay-ms"))).isEqualTo(100);
    }

    @Test
    void executionAccountSummaryDefaults_areExternallyConfigurableAndBounded() throws IOException {
        PropertySource<?> applicationProperties = loadApplicationProperties();

        assertThat(applicationProperties.getProperty("stock.batch.execution-account-summary.flush-fixed-delay-ms"))
                .isEqualTo("${STOCK_BATCH_EXECUTION_ACCOUNT_SUMMARY_FLUSH_FIXED_DELAY_MS:30000}");
        assertThat(applicationProperties.getProperty("stock.batch.execution-account-summary.flush-batch-size"))
                .isEqualTo("${STOCK_BATCH_EXECUTION_ACCOUNT_SUMMARY_FLUSH_BATCH_SIZE:5000}");
        assertThat(applicationProperties.getProperty("stock.batch.execution-account-summary.max-pending-deltas"))
                .isEqualTo("${STOCK_BATCH_EXECUTION_ACCOUNT_SUMMARY_MAX_PENDING_DELTAS:100000}");
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.execution-account-summary.flush-fixed-delay-ms")))
                .isGreaterThanOrEqualTo(30_000);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.execution-account-summary.flush-batch-size")))
                .isBetween(1, 5_000);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.execution-account-summary.max-pending-deltas")))
                .isBetween(1, 1_000_000);
    }

    @Test
    void portfolioSettlementDefault_isExternallyConfigurableAndBounded() throws IOException {
        PropertySource<?> applicationProperties = loadApplicationProperties();

        assertThat(applicationProperties.getProperty("stock.batch.settlement.chunk-size"))
                .isEqualTo("${STOCK_BATCH_SETTLEMENT_CHUNK_SIZE:200}");
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.settlement.chunk-size")))
                .isBetween(1, 2_000);
    }

    @Test
    void autoMarketGenerationDefaults_areExternallyConfigurableAndBounded() throws IOException {
        PropertySource<?> applicationProperties = loadApplicationProperties();

        assertThat(applicationProperties.getProperty("stock.batch.auto-market.fixed-rate-ms"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_FIXED_RATE_MS:5000}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.daily-regime.fixed-delay-ms"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_DAILY_REGIME_FIXED_DELAY_MS:10000}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.daily-regime.pre-create-before-minutes"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_DAILY_REGIME_PRE_CREATE_BEFORE_MINUTES:30}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.generation-profile-worker-count"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_GENERATION_PROFILE_WORKER_COUNT:9}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.generation-participant-chunk-size"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_GENERATION_PARTICIPANT_CHUNK_SIZE:25}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.generation-due-limit-per-symbol"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_GENERATION_DUE_LIMIT_PER_SYMBOL:100}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.generation-candidate-row-limit"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_GENERATION_CANDIDATE_ROW_LIMIT:2000}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.max-open-order-quantity-multiplier"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_MAX_OPEN_ORDER_QUANTITY_MULTIPLIER:10}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.profile-queue.reconcile-enabled"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_PROFILE_QUEUE_RECONCILE_ENABLED:true}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.profile-queue.reconcile-initial-delay-ms"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_PROFILE_QUEUE_RECONCILE_INITIAL_DELAY_MS:4000}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.profile-queue.reconcile-fixed-delay-ms"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_PROFILE_QUEUE_RECONCILE_FIXED_DELAY_MS:600000}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.profile-queue.reconcile-limit"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_PROFILE_QUEUE_RECONCILE_LIMIT:100}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.deadlock-retry-max-attempts"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_DEADLOCK_RETRY_MAX_ATTEMPTS:5}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.deadlock-retry-backoff-ms"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_DEADLOCK_RETRY_BACKOFF_MS:50}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.thread-pool.core-size"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_THREAD_POOL_CORE_SIZE:12}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.thread-pool.max-size"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_THREAD_POOL_MAX_SIZE:12}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.thread-pool.queue-capacity"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_THREAD_POOL_QUEUE_CAPACITY:0}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.run-dispatcher.thread-pool.core-size"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_RUN_THREAD_POOL_CORE_SIZE:1}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.run-dispatcher.thread-pool.max-size"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_RUN_THREAD_POOL_MAX_SIZE:1}");
        assertThat(applicationProperties.getProperty("stock.batch.auto-market.run-dispatcher.thread-pool.queue-capacity"))
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_RUN_THREAD_POOL_QUEUE_CAPACITY:0}");
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.auto-market.fixed-rate-ms"))).isEqualTo(5_000);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.auto-market-order-expiry.fixed-delay-ms")))
                .isGreaterThanOrEqualTo(1_000);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.auto-market-order-expiry.symbol-limit-per-run")))
                .isBetween(1, 500);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.auto-market.daily-regime.fixed-delay-ms"))).isBetween(5_000, 10_000);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.auto-market.daily-regime.pre-create-before-minutes"))).isEqualTo(30);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.auto-market.generation-profile-worker-count"))).isBetween(1, 16);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.auto-market.generation-profile-worker-count"))).isEqualTo(9);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.auto-market.generation-participant-chunk-size"))).isBetween(1, 100);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.auto-market.generation-due-limit-per-symbol"))).isBetween(1, 500);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.auto-market.generation-candidate-row-limit"))).isBetween(1, 10_000);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.auto-market.max-open-order-quantity-multiplier"))).isEqualTo(10);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.auto-market.profile-queue.reconcile-initial-delay-ms"))).isEqualTo(4_000);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.auto-market.profile-queue.reconcile-fixed-delay-ms"))).isEqualTo(600_000);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.auto-market.profile-queue.reconcile-limit"))).isBetween(1, 1_000);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.auto-market.deadlock-retry-max-attempts"))).isBetween(1, 10);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.auto-market.thread-pool.core-size"))).isBetween(1, 16);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.auto-market.thread-pool.max-size"))).isBetween(1, 16);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.auto-market.thread-pool.queue-capacity"))).isZero();
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.auto-market.run-dispatcher.thread-pool.core-size"))).isEqualTo(1);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.auto-market.run-dispatcher.thread-pool.max-size"))).isEqualTo(1);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.auto-market.run-dispatcher.thread-pool.queue-capacity"))).isEqualTo(0);
    }

    @Test
    void listingAutoMarketRetryDefaults_areExternallyConfigurableAndBounded() throws IOException {
        PropertySource<?> applicationProperties = loadApplicationProperties();

        assertThat(applicationProperties.getProperty("stock.batch.listing-auto-market.fixed-delay-ms"))
                .isEqualTo("${STOCK_BATCH_LISTING_AUTO_MARKET_FIXED_DELAY_MS:10000}");
        assertThat(applicationProperties.getProperty("stock.batch.listing-auto-market.deadlock-retry-max-attempts"))
                .isEqualTo("${STOCK_BATCH_LISTING_AUTO_MARKET_DEADLOCK_RETRY_MAX_ATTEMPTS:5}");
        assertThat(applicationProperties.getProperty("stock.batch.listing-auto-market.symbol-limit-per-run"))
                .isEqualTo("${STOCK_BATCH_LISTING_AUTO_MARKET_SYMBOL_LIMIT_PER_RUN:100}");
        assertThat(applicationProperties.getProperty("stock.batch.listing-auto-market.deadlock-retry-backoff-ms"))
                .isEqualTo("${STOCK_BATCH_LISTING_AUTO_MARKET_DEADLOCK_RETRY_BACKOFF_MS:50}");
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.listing-auto-market.fixed-delay-ms"))).isBetween(5_000, 10_000);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.listing-auto-market.symbol-limit-per-run"))).isBetween(1, 500);
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

        assertThat(propertyValue).isEqualTo("${STOCK_BATCH_AUTO_PARTICIPANT_CASH_FLOW_FIXED_DELAY_MS:300000}");
        assertThat(defaultNumber(propertyValue)).isGreaterThanOrEqualTo(300_000);
        assertThat(schedulerSource).contains("stock.batch.auto-participant-cash-flow.fixed-delay-ms:300000");
        assertThat(readme).contains("기본값은 300000ms");
        assertThat(readme).contains("polling 간격은 실제 서버 시간 기준");
    }

    @Test
    void marketCloseVolumeDefaults_areBoundedAndExternallyConfigurable() throws IOException {
        PropertySource<?> applicationProperties = loadApplicationProperties();

        assertThat(applicationProperties.getProperty("stock.batch.market-close.order-capture-chunk-size"))
                .isEqualTo("${STOCK_BATCH_MARKET_CLOSE_ORDER_CAPTURE_CHUNK_SIZE:1000}");
        assertThat(applicationProperties.getProperty("stock.batch.market-close.order-cancel-chunk-size"))
                .isEqualTo("${STOCK_BATCH_MARKET_CLOSE_ORDER_CANCEL_CHUNK_SIZE:500}");
        assertThat(applicationProperties.getProperty("stock.batch.market-close.holding-snapshot-account-chunk-size"))
                .isEqualTo("${STOCK_BATCH_MARKET_CLOSE_HOLDING_SNAPSHOT_ACCOUNT_CHUNK_SIZE:500}");
        assertThat(applicationProperties.getProperty("stock.batch.market-close.account-snapshot-chunk-size"))
                .isEqualTo("${STOCK_BATCH_MARKET_CLOSE_ACCOUNT_SNAPSHOT_CHUNK_SIZE:500}");
        assertThat(applicationProperties.getProperty("stock.batch.market-close.reconciliation-account-chunk-size"))
                .isEqualTo("${STOCK_BATCH_MARKET_CLOSE_RECONCILIATION_ACCOUNT_CHUNK_SIZE:500}");

        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.market-close.order-capture-chunk-size")))
                .isBetween(1, 10_000);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.market-close.order-cancel-chunk-size")))
                .isBetween(1, 5_000);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.market-close.holding-snapshot-account-chunk-size")))
                .isBetween(1, 2_000);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.market-close.account-snapshot-chunk-size")))
                .isBetween(1, 2_000);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.market-close.reconciliation-account-chunk-size")))
                .isBetween(1, 2_000);
    }

    @Test
    void postCloseRetryDefaults_backOffFailedJobsWithoutLongDatabasePressure() throws IOException {
        PropertySource<?> applicationProperties = loadApplicationProperties();

        assertThat(applicationProperties.getProperty("stock.batch.post-close.retry-base-seconds"))
                .isEqualTo("${STOCK_BATCH_POST_CLOSE_RETRY_BASE_SECONDS:30}");
        assertThat(applicationProperties.getProperty("stock.batch.post-close.retry-max-seconds"))
                .isEqualTo("${STOCK_BATCH_POST_CLOSE_RETRY_MAX_SECONDS:900}");
        assertThat(applicationProperties.getProperty("stock.batch.post-close.deferred-retry-seconds"))
                .isEqualTo("${STOCK_BATCH_POST_CLOSE_DEFERRED_RETRY_SECONDS:60}");
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.post-close.retry-base-seconds")))
                .isGreaterThanOrEqualTo(30);
        assertThat(defaultNumber(applicationProperties.getProperty("stock.batch.post-close.retry-max-seconds")))
                .isGreaterThanOrEqualTo(300);
    }

    @Test
    void batchJobSignalDefaultPollInterval_isNotOneSecondDatabaseScan() throws IOException {
        PropertySource<?> applicationProperties = loadApplicationProperties();
        String schedulerSource = Files.readString(
                Path.of("src/main/java/stock/batch/service/scheduler/BatchJobSignalScheduler.java"),
                StandardCharsets.UTF_8
        );
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);

        Object propertyValue = applicationProperties.getProperty("stock.batch.signal.fixed-delay-ms");

        assertThat(propertyValue).isEqualTo("${STOCK_BATCH_SIGNAL_FIXED_DELAY_MS:5000}");
        assertThat(defaultNumber(propertyValue)).isGreaterThanOrEqualTo(5_000);
        assertThat(schedulerSource).contains("stock.batch.signal.fixed-delay-ms:5000");
        assertThat(readme).contains("기본값은 5000ms");
        assertThat(applicationProperties.getProperty("stock.batch.signal.active-job-deferred-retry-delay-ms"))
                .isNull();
        assertThat(readme).doesNotContain("active-job-deferred-retry-delay-ms");
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
                .isEqualTo("${STOCK_BATCH_AUTO_MARKET_SCHEDULER_POOL_SIZE:1}");
        assertThat(applicationProperties.getProperty("stock.batch.scheduler-pools.maintenance.pool-size"))
                .isEqualTo("${STOCK_BATCH_MAINTENANCE_SCHEDULER_POOL_SIZE:1}");
        assertThat(applicationProperties.getProperty("stock.batch.scheduler-pools.post-close.pool-size"))
                .isEqualTo("${STOCK_BATCH_POST_CLOSE_SCHEDULER_POOL_SIZE:1}");
        assertThat(applicationProperties.getProperty("stock.batch.scheduler-pools.simulation-clock.pool-size"))
                .isEqualTo("${STOCK_BATCH_SIMULATION_CLOCK_SCHEDULER_POOL_SIZE:1}");

        int defaultTotalSchedulerThreads = defaultNumber(applicationProperties.getProperty("stock.batch.scheduler-pools.execution.pool-size"))
                + defaultNumber(applicationProperties.getProperty("stock.batch.scheduler-pools.auto-market.pool-size"))
                + defaultNumber(applicationProperties.getProperty("stock.batch.scheduler-pools.maintenance.pool-size"))
                + defaultNumber(applicationProperties.getProperty("stock.batch.scheduler-pools.post-close.pool-size"))
                + defaultNumber(applicationProperties.getProperty("stock.batch.scheduler-pools.simulation-clock.pool-size"));

        assertThat(defaultTotalSchedulerThreads).isLessThanOrEqualTo(7);
    }

    @Test
    void dedicatedSchedulerShutdown_cancelsPendingTriggersButWaitsForRunningWork() {
        var scheduler = new StockBatchSchedulerConfig()
                .stockBatchMaintenanceTaskScheduler(1, 1);
        scheduler.initialize();

        try {
            var executor = scheduler.getScheduledThreadPoolExecutor();

            assertThat(executor.getExecuteExistingDelayedTasksAfterShutdownPolicy()).isFalse();
            assertThat(executor.getContinueExistingPeriodicTasksAfterShutdownPolicy()).isFalse();
        } finally {
            scheduler.destroy();
        }
    }

    @Test
    void postCloseScheduler_rejectsParallelPoolConfiguration() {
        StockBatchSchedulerConfig config = new StockBatchSchedulerConfig();

        assertThatThrownBy(() -> config.stockBatchPostCloseTaskScheduler(2, 120))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly 1");
    }

    @Test
    void autoMarketScheduler_rejectsParallelPoolConfiguration() {
        StockBatchSchedulerConfig config = new StockBatchSchedulerConfig();

        assertThatThrownBy(() -> config.stockBatchAutoMarketTaskScheduler(2, 120))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly 1");
    }

    @Test
    void maintenanceScheduler_rejectsParallelPoolConfiguration() {
        StockBatchSchedulerConfig config = new StockBatchSchedulerConfig();

        assertThatThrownBy(() -> config.stockBatchMaintenanceTaskScheduler(2, 120))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly 1");
    }

    @Test
    void executionScheduler_rejectsPoolAboveSafeMaximum() {
        StockBatchSchedulerConfig config = new StockBatchSchedulerConfig();

        assertThatThrownBy(() -> config.stockBatchExecutionTaskScheduler(5, 120))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 4");
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
