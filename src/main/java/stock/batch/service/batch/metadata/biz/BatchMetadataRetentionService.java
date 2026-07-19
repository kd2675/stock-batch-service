package stock.batch.service.batch.metadata.biz;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameter;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import stock.batch.service.batch.config.BatchRepositoryDataSourceConfig;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;

@Component
@Slf4j
public class BatchMetadataRetentionService {

    static final int MAX_INSTANCE_LIMIT = 100;
    static final int MAX_EXECUTIONS_PER_INSTANCE = 50;
    static final int MAX_STEPS_PER_INSTANCE = 500;
    private static final int METADATA_SQL_QUERY_TIMEOUT_SECONDS = 10;
    private static final int MAX_PARAMETER_SUMMARY_LENGTH = 4_000;
    private static final int MAX_EXIT_TEXT_LENGTH = 2_500;

    private final JobRepository jobRepository;
    private final MarketSessionFenceService marketSessionFenceService;
    private final JdbcTemplate metadataJdbcTemplate;
    private final NamedParameterJdbcTemplate metadataNamedJdbcTemplate;
    private final TransactionTemplate metadataTransactionTemplate;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final int retentionRealDays;
    private final int instanceLimit;
    private final int maxExecutionsPerInstance;
    private final int maxStepsPerInstance;
    private final boolean purgeEnabled;
    private final Set<String> purgeJobNames;

    @Autowired
    public BatchMetadataRetentionService(
            JobRepository jobRepository,
            MarketSessionFenceService marketSessionFenceService,
            @Qualifier(BatchRepositoryDataSourceConfig.BATCH_METADATA_DATA_SOURCE) DataSource metadataDataSource,
            @Qualifier(BatchRepositoryDataSourceConfig.BATCH_METADATA_TRANSACTION_MANAGER)
            PlatformTransactionManager metadataTransactionManager,
            MeterRegistry meterRegistry,
            @Value("${stock.batch.metadata-retention.retention-real-days:30}") int retentionRealDays,
            @Value("${stock.batch.metadata-retention.instance-limit:25}") int instanceLimit,
            @Value("${stock.batch.metadata-retention.max-executions-per-instance:20}")
            int maxExecutionsPerInstance,
            @Value("${stock.batch.metadata-retention.max-steps-per-instance:200}") int maxStepsPerInstance,
            @Value("${stock.batch.metadata-retention.purge-enabled:false}") boolean purgeEnabled,
            @Value("${stock.batch.metadata-retention.purge-job-names:}") String purgeJobNames
    ) {
        this(
                jobRepository,
                marketSessionFenceService,
                metadataDataSource,
                metadataTransactionManager,
                meterRegistry,
                Clock.systemDefaultZone(),
                retentionRealDays,
                instanceLimit,
                maxExecutionsPerInstance,
                maxStepsPerInstance,
                purgeEnabled,
                purgeJobNames
        );
    }

    public BatchMetadataRetentionService(
            JobRepository jobRepository,
            MarketSessionFenceService marketSessionFenceService,
            DataSource metadataDataSource,
            PlatformTransactionManager metadataTransactionManager,
            MeterRegistry meterRegistry,
            Clock clock,
            int retentionRealDays,
            int instanceLimit,
            int maxExecutionsPerInstance,
            int maxStepsPerInstance,
            boolean purgeEnabled,
            String purgeJobNames
    ) {
        requireRange("retentionRealDays", retentionRealDays, 1, 3_650);
        requireRange("instanceLimit", instanceLimit, 1, MAX_INSTANCE_LIMIT);
        requireRange(
                "maxExecutionsPerInstance",
                maxExecutionsPerInstance,
                1,
                MAX_EXECUTIONS_PER_INSTANCE
        );
        requireRange("maxStepsPerInstance", maxStepsPerInstance, 1, MAX_STEPS_PER_INSTANCE);
        this.purgeJobNames = parseJobNames(purgeJobNames);
        if (purgeEnabled && this.purgeJobNames.isEmpty()) {
            throw new IllegalArgumentException(
                    "stock.batch.metadata-retention.purge-job-names is required when purge-enabled=true"
            );
        }
        this.jobRepository = jobRepository;
        this.marketSessionFenceService = marketSessionFenceService;
        this.metadataJdbcTemplate = new JdbcTemplate(metadataDataSource);
        this.metadataJdbcTemplate.setQueryTimeout(METADATA_SQL_QUERY_TIMEOUT_SECONDS);
        this.metadataNamedJdbcTemplate = new NamedParameterJdbcTemplate(metadataJdbcTemplate);
        this.metadataTransactionTemplate = new TransactionTemplate(metadataTransactionManager);
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.retentionRealDays = retentionRealDays;
        this.instanceLimit = instanceLimit;
        this.maxExecutionsPerInstance = maxExecutionsPerInstance;
        this.maxStepsPerInstance = maxStepsPerInstance;
        this.purgeEnabled = purgeEnabled;
    }

    public RetentionResult archiveAndOptionallyPurgeCompletedInstances() {
        if (marketSessionFenceService.hasOpenMarket()) {
            throw new IllegalStateException("Batch metadata retention is prohibited while a stock market is open");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime cutoff = now.minusDays(retentionRealDays);
        List<RetentionCandidate> candidates = findCandidates(cutoff);
        int processedInstances = 0;
        int archivedInstances = 0;
        int archivedExecutions = 0;
        int purgedInstances = 0;
        int skippedOversizedInstances = 0;
        int failedInstances = 0;

        for (RetentionCandidate candidate : candidates) {
            if (countStepExecutions(candidate.jobInstanceId()) > maxStepsPerInstance) {
                skippedOversizedInstances++;
                continue;
            }
            InstanceRetentionOutcome outcome;
            try {
                outcome = metadataTransactionTemplate.execute(
                        status -> retainOneInstance(candidate, cutoff, now)
                );
            } catch (RuntimeException failure) {
                failedInstances++;
                log.warn(
                        "Batch metadata retention candidate failed: jobInstanceId={}, jobName={}, reason={}",
                        candidate.jobInstanceId(),
                        candidate.jobName(),
                        failure.getMessage(),
                        failure
                );
                continue;
            }
            if (outcome == null || !outcome.processed()) {
                continue;
            }
            processedInstances++;
            archivedInstances += outcome.archived() ? 1 : 0;
            archivedExecutions += outcome.archivedExecutionCount();
            purgedInstances += outcome.purged() ? 1 : 0;
        }

        increment("stock.batch.metadata.retention.candidates", candidates.size());
        increment("stock.batch.metadata.retention.archived.instances", archivedInstances);
        increment("stock.batch.metadata.retention.archived.executions", archivedExecutions);
        increment("stock.batch.metadata.retention.purged.instances", purgedInstances);
        increment("stock.batch.metadata.retention.skipped.oversized.instances", skippedOversizedInstances);
        increment("stock.batch.metadata.retention.failed.instances", failedInstances);
        return new RetentionResult(
                candidates.size(),
                processedInstances,
                archivedInstances,
                archivedExecutions,
                purgedInstances,
                skippedOversizedInstances,
                failedInstances,
                cutoff
        );
    }

    private List<RetentionCandidate> findCandidates(LocalDateTime cutoff) {
        String archiveEligibility = purgeEnabled
                ? """
                    and (
                          not exists (
                              select 1
                                from STOCK_BATCH_JOB_METADATA_ARCHIVE archive_row
                               where archive_row.JOB_INSTANCE_ID = ji.JOB_INSTANCE_ID
                          )
                          or (
                              ji.JOB_NAME in (:purgeJobNames)
                              and exists (
                                  select 1
                                    from STOCK_BATCH_JOB_METADATA_ARCHIVE purge_row
                                   where purge_row.JOB_INSTANCE_ID = ji.JOB_INSTANCE_ID
                                     and purge_row.PURGED_AT is null
                              )
                          )
                    )
                    """
                : """
                    and not exists (
                        select 1
                          from STOCK_BATCH_JOB_METADATA_ARCHIVE archive_row
                         where archive_row.JOB_INSTANCE_ID = ji.JOB_INSTANCE_ID
                    )
                    """;
        String sql = """
                select ji.JOB_INSTANCE_ID,
                       ji.JOB_NAME,
                       ji.JOB_KEY
                  from BATCH_JOB_INSTANCE ji
                  join BATCH_JOB_EXECUTION je
                    on je.JOB_INSTANCE_ID = ji.JOB_INSTANCE_ID
                 where je.STATUS = 'COMPLETED'
                   and je.END_TIME < :cutoff
                   and not exists (
                       select 1
                         from BATCH_JOB_EXECUTION unsafe_execution
                        where unsafe_execution.JOB_INSTANCE_ID = ji.JOB_INSTANCE_ID
                          and (
                              unsafe_execution.STATUS is null
                              or unsafe_execution.STATUS <> 'COMPLETED'
                              or unsafe_execution.END_TIME is null
                              or unsafe_execution.END_TIME >= :cutoff
                          )
                   )
                """ + archiveEligibility + """
                 group by ji.JOB_INSTANCE_ID, ji.JOB_NAME, ji.JOB_KEY
                having count(*) <= :maxExecutions
                 order by max(je.END_TIME), ji.JOB_INSTANCE_ID
                 limit :instanceLimit
                """;
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("cutoff", cutoff)
                .addValue("maxExecutions", maxExecutionsPerInstance)
                .addValue("instanceLimit", instanceLimit);
        if (purgeEnabled) {
            parameters.addValue("purgeJobNames", purgeJobNames);
        }
        return metadataNamedJdbcTemplate.query(
                sql,
                parameters,
                (rs, rowNum) -> new RetentionCandidate(
                        rs.getLong("JOB_INSTANCE_ID"),
                        rs.getString("JOB_NAME"),
                        rs.getString("JOB_KEY")
                )
        );
    }

    private long countStepExecutions(long jobInstanceId) {
        Long count = metadataJdbcTemplate.queryForObject(
                """
                select count(*)
                  from BATCH_STEP_EXECUTION se
                  join BATCH_JOB_EXECUTION je
                    on je.JOB_EXECUTION_ID = se.JOB_EXECUTION_ID
                 where je.JOB_INSTANCE_ID = ?
                """,
                Long.class,
                jobInstanceId
        );
        return count == null ? 0L : count;
    }

    private InstanceRetentionOutcome retainOneInstance(
            RetentionCandidate candidate,
            LocalDateTime cutoff,
            LocalDateTime archivedAt
    ) {
        JobInstance jobInstance = jobRepository.getJobInstance(candidate.jobInstanceId());
        if (jobInstance == null || !candidate.jobName().equals(jobInstance.getJobName())) {
            return InstanceRetentionOutcome.skipped();
        }
        List<JobExecution> executions = jobRepository.getJobExecutions(jobInstance);
        if (!isSafeToArchive(executions, cutoff)) {
            return InstanceRetentionOutcome.skipped();
        }
        int totalStepCount = executions.stream().mapToInt(execution -> execution.getStepExecutions().size()).sum();
        if (totalStepCount > maxStepsPerInstance) {
            return InstanceRetentionOutcome.skipped();
        }

        boolean alreadyArchived = countArchivedExecutions(candidate.jobInstanceId()) > 0;
        for (JobExecution execution : executions) {
            archiveExecution(candidate, execution, archivedAt);
        }
        boolean purge = purgeEnabled && purgeJobNames.contains(candidate.jobName());
        if (purge) {
            jobRepository.deleteJobInstance(jobInstance);
            metadataJdbcTemplate.update(
                    """
                    update STOCK_BATCH_JOB_METADATA_ARCHIVE
                       set PURGED_AT = ?
                     where JOB_INSTANCE_ID = ?
                    """,
                    archivedAt,
                    candidate.jobInstanceId()
            );
        }
        return new InstanceRetentionOutcome(
                true,
                !alreadyArchived,
                alreadyArchived ? 0 : executions.size(),
                purge
        );
    }

    private boolean isSafeToArchive(List<JobExecution> executions, LocalDateTime cutoff) {
        return !executions.isEmpty()
                && executions.size() <= maxExecutionsPerInstance
                && executions.stream().allMatch(execution -> execution.getStatus() == BatchStatus.COMPLETED
                        && execution.getEndTime() != null
                        && execution.getEndTime().isBefore(cutoff));
    }

    private long countArchivedExecutions(long jobInstanceId) {
        Long count = metadataJdbcTemplate.queryForObject(
                """
                select count(*)
                  from STOCK_BATCH_JOB_METADATA_ARCHIVE
                 where JOB_INSTANCE_ID = ?
                """,
                Long.class,
                jobInstanceId
        );
        return count == null ? 0L : count;
    }

    private void archiveExecution(
            RetentionCandidate candidate,
            JobExecution execution,
            LocalDateTime archivedAt
    ) {
        long stepCount = execution.getStepExecutions().size();
        long readCount = execution.getStepExecutions().stream().mapToLong(step -> step.getReadCount()).sum();
        long writeCount = execution.getStepExecutions().stream().mapToLong(step -> step.getWriteCount()).sum();
        long filterCount = execution.getStepExecutions().stream().mapToLong(step -> step.getFilterCount()).sum();
        long commitCount = execution.getStepExecutions().stream().mapToLong(step -> step.getCommitCount()).sum();
        long rollbackCount = execution.getStepExecutions().stream().mapToLong(step -> step.getRollbackCount()).sum();
        long skipCount = execution.getStepExecutions().stream().mapToLong(step -> step.getSkipCount()).sum();
        Object[] values = {
                candidate.jobInstanceId(),
                candidate.jobName(),
                candidate.jobKey(),
                execution.getStatus().name(),
                execution.getCreateTime(),
                execution.getStartTime(),
                execution.getEndTime(),
                truncate(execution.getExitStatus().getExitCode(), MAX_EXIT_TEXT_LENGTH),
                truncate(execution.getExitStatus().getExitDescription(), MAX_EXIT_TEXT_LENGTH),
                identifyingParameters(execution),
                stepCount,
                readCount,
                writeCount,
                filterCount,
                commitCount,
                rollbackCount,
                skipCount,
                archivedAt,
                execution.getId()
        };
        int updated = metadataJdbcTemplate.update(
                """
                update STOCK_BATCH_JOB_METADATA_ARCHIVE
                   set JOB_INSTANCE_ID = ?,
                       JOB_NAME = ?,
                       JOB_KEY = ?,
                       STATUS = ?,
                       CREATE_TIME = ?,
                       START_TIME = ?,
                       END_TIME = ?,
                       EXIT_CODE = ?,
                       EXIT_MESSAGE = ?,
                       IDENTIFYING_PARAMETERS = ?,
                       STEP_COUNT = ?,
                       READ_COUNT = ?,
                       WRITE_COUNT = ?,
                       FILTER_COUNT = ?,
                       COMMIT_COUNT = ?,
                       ROLLBACK_COUNT = ?,
                       SKIP_COUNT = ?,
                       ARCHIVED_AT = ?
                 where JOB_EXECUTION_ID = ?
                """,
                values
        );
        if (updated == 1) {
            return;
        }
        try {
            metadataJdbcTemplate.update(
                    """
                    insert into STOCK_BATCH_JOB_METADATA_ARCHIVE(
                        JOB_EXECUTION_ID,
                        JOB_INSTANCE_ID,
                        JOB_NAME,
                        JOB_KEY,
                        STATUS,
                        CREATE_TIME,
                        START_TIME,
                        END_TIME,
                        EXIT_CODE,
                        EXIT_MESSAGE,
                        IDENTIFYING_PARAMETERS,
                        STEP_COUNT,
                        READ_COUNT,
                        WRITE_COUNT,
                        FILTER_COUNT,
                        COMMIT_COUNT,
                        ROLLBACK_COUNT,
                        SKIP_COUNT,
                        ARCHIVED_AT,
                        PURGED_AT
                    )
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, null)
                    """,
                    execution.getId(),
                    candidate.jobInstanceId(),
                    candidate.jobName(),
                    candidate.jobKey(),
                    execution.getStatus().name(),
                    execution.getCreateTime(),
                    execution.getStartTime(),
                    execution.getEndTime(),
                    truncate(execution.getExitStatus().getExitCode(), MAX_EXIT_TEXT_LENGTH),
                    truncate(execution.getExitStatus().getExitDescription(), MAX_EXIT_TEXT_LENGTH),
                    identifyingParameters(execution),
                    stepCount,
                    readCount,
                    writeCount,
                    filterCount,
                    commitCount,
                    rollbackCount,
                    skipCount,
                    archivedAt
            );
        } catch (DuplicateKeyException concurrentArchive) {
            metadataJdbcTemplate.update(
                    """
                    update STOCK_BATCH_JOB_METADATA_ARCHIVE
                       set ARCHIVED_AT = ?
                     where JOB_EXECUTION_ID = ?
                    """,
                    archivedAt,
                    execution.getId()
            );
        }
    }

    private String identifyingParameters(JobExecution execution) {
        List<JobParameter<?>> parameters = new ArrayList<>(
                execution.getJobParameters().getIdentifyingParameters()
        );
        parameters.sort(Comparator.comparing(JobParameter::name));
        String summary = parameters.stream()
                .map(parameter -> parameter.name() + "=" + String.valueOf(parameter.value()))
                .collect(Collectors.joining("&"));
        return truncate(summary, MAX_PARAMETER_SUMMARY_LENGTH);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private void increment(String metricName, long amount) {
        if (amount > 0) {
            meterRegistry.counter(metricName).increment(amount);
        }
    }

    private static void requireRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max + ": " + value);
        }
    }

    private static Set<String> parseJobNames(String value) {
        if (!StringUtils.hasText(value)) {
            return Set.of();
        }
        return Arrays.stream(value.split("[,\\r\\n]+"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private record RetentionCandidate(long jobInstanceId, String jobName, String jobKey) {
    }

    private record InstanceRetentionOutcome(
            boolean processed,
            boolean archived,
            int archivedExecutionCount,
            boolean purged
    ) {
        private static InstanceRetentionOutcome skipped() {
            return new InstanceRetentionOutcome(false, false, 0, false);
        }
    }

    public record RetentionResult(
            int candidateInstances,
            int processedInstances,
            int archivedInstances,
            int archivedExecutions,
            int purgedInstances,
            int skippedOversizedInstances,
            int failedInstances,
            LocalDateTime cutoff
    ) {
    }
}
