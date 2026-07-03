package stock.batch.service.batch.common.support;

import java.time.LocalDateTime;
import java.util.List;

import javax.sql.DataSource;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.batch.config.BatchRepositoryDataSourceConfig;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class StockBatchJobRepositoryRecovery implements ApplicationRunner {

    static final String RECOVERY_EXIT_MESSAGE =
            "Recovered stale STARTED execution after stock-batch-service restart";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public StockBatchJobRepositoryRecovery(
            @Qualifier(BatchRepositoryDataSourceConfig.BATCH_METADATA_DATA_SOURCE) DataSource dataSource,
            @Qualifier(BatchRepositoryDataSourceConfig.BATCH_METADATA_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    StockBatchJobRepositoryRecovery(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        int recoveredCount = recoverStaleExecutions(LocalDateTime.now());
        if (recoveredCount > 0) {
            log.warn("Recovered stale stock batch job executions: count={}", recoveredCount);
        }
    }

    int recoverStaleExecutions(LocalDateTime recoveredAt) {
        return transactionTemplate.execute(status -> {
            List<Long> jobExecutionIds = findStaleJobExecutionIds(recoveredAt);
            for (Long jobExecutionId : jobExecutionIds) {
                recoverOpenStepExecutions(jobExecutionId, recoveredAt);
                recoverJobExecution(jobExecutionId, recoveredAt);
            }
            return jobExecutionIds.size();
        });
    }

    private List<Long> findStaleJobExecutionIds(LocalDateTime recoveredAt) {
        return jdbcTemplate.queryForList(
                """
                select JOB_EXECUTION_ID
                  from BATCH_JOB_EXECUTION
                 where STATUS in ('STARTING', 'STARTED')
                   and END_TIME is null
                   and coalesce(START_TIME, CREATE_TIME) < ?
                 order by JOB_EXECUTION_ID asc
                """,
                Long.class,
                recoveredAt
        );
    }

    private void recoverOpenStepExecutions(Long jobExecutionId, LocalDateTime recoveredAt) {
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
                   and STATUS in ('STARTING', 'STARTED')
                   and END_TIME is null
                """,
                RECOVERY_EXIT_MESSAGE,
                recoveredAt,
                recoveredAt,
                jobExecutionId
        );
    }

    private void recoverJobExecution(Long jobExecutionId, LocalDateTime recoveredAt) {
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
                   and STATUS in ('STARTING', 'STARTED')
                   and END_TIME is null
                """,
                RECOVERY_EXIT_MESSAGE,
                recoveredAt,
                recoveredAt,
                jobExecutionId
        );
    }
}
