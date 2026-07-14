package stock.batch.service.batch.common.support;

import java.time.LocalDateTime;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import stock.batch.service.batch.config.BatchRepositoryDataSourceConfig;

@Component
public class StockBatchStaleExecutionRecovery {

    static final String RECOVERY_EXIT_MESSAGE =
            "Recovered stale execution after acquiring the business job lock";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public StockBatchStaleExecutionRecovery(
            @Qualifier(BatchRepositoryDataSourceConfig.BATCH_METADATA_DATA_SOURCE) DataSource dataSource,
            @Qualifier(BatchRepositoryDataSourceConfig.BATCH_METADATA_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager
    ) {
        this(new JdbcTemplate(dataSource), transactionManager);
    }

    StockBatchStaleExecutionRecovery(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Recovers only executions for the native job whose business lock is already owned by the
     * caller. This must not be invoked as a global startup sweep because another batch node may
     * still own and execute a different job.
     */
    public int recover(String jobName, LocalDateTime recoveredAt) {
        if (!StringUtils.hasText(jobName)) {
            throw new IllegalArgumentException("jobName is required");
        }
        if (recoveredAt == null) {
            throw new IllegalArgumentException("recoveredAt is required");
        }
        Integer recoveredCount = transactionTemplate.execute(status -> {
            List<Long> executionIds = findStaleExecutionIds(jobName.trim(), recoveredAt);
            for (Long executionId : executionIds) {
                failOpenSteps(executionId, recoveredAt);
                failOpenJobExecution(executionId, recoveredAt);
            }
            return executionIds.size();
        });
        return recoveredCount == null ? 0 : recoveredCount;
    }

    private List<Long> findStaleExecutionIds(String jobName, LocalDateTime recoveredAt) {
        return jdbcTemplate.queryForList(
                """
                select je.JOB_EXECUTION_ID
                  from BATCH_JOB_EXECUTION je
                  join BATCH_JOB_INSTANCE ji on ji.JOB_INSTANCE_ID = je.JOB_INSTANCE_ID
                 where ji.JOB_NAME = ?
                   and je.STATUS in ('STARTING', 'STARTED', 'STOPPING')
                   and je.END_TIME is null
                   and coalesce(je.LAST_UPDATED, je.START_TIME, je.CREATE_TIME) < ?
                 order by je.JOB_EXECUTION_ID asc
                """,
                Long.class,
                jobName,
                recoveredAt
        );
    }

    private void failOpenSteps(long jobExecutionId, LocalDateTime recoveredAt) {
        jdbcTemplate.update(
                """
                update BATCH_STEP_EXECUTION
                   set VERSION = coalesce(VERSION, 0) + 1,
                       STATUS = 'FAILED',
                       EXIT_CODE = 'FAILED',
                       EXIT_MESSAGE = ?,
                       END_TIME = ?,
                       LAST_UPDATED = ?
                 where JOB_EXECUTION_ID = ?
                   and STATUS in ('STARTING', 'STARTED', 'STOPPING')
                   and END_TIME is null
                """,
                RECOVERY_EXIT_MESSAGE,
                recoveredAt,
                recoveredAt,
                jobExecutionId
        );
    }

    private void failOpenJobExecution(long jobExecutionId, LocalDateTime recoveredAt) {
        jdbcTemplate.update(
                """
                update BATCH_JOB_EXECUTION
                   set VERSION = coalesce(VERSION, 0) + 1,
                       STATUS = 'FAILED',
                       EXIT_CODE = 'FAILED',
                       EXIT_MESSAGE = ?,
                       END_TIME = ?,
                       LAST_UPDATED = ?
                 where JOB_EXECUTION_ID = ?
                   and STATUS in ('STARTING', 'STARTED', 'STOPPING')
                   and END_TIME is null
                """,
                RECOVERY_EXIT_MESSAGE,
                recoveredAt,
                recoveredAt,
                jobExecutionId
        );
    }
}
