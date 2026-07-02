package stock.batch.service.batch.common.policy;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

@Component
public class BatchJobRuntimeControl {

    private static final String SYSTEM_UPDATED_BY = "SYSTEM";
    private static final boolean DEFAULT_CONTROL_ROW_ENABLED = true;

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;

    public BatchJobRuntimeControl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    @Transactional
    public boolean shouldRunScheduledJob(String jobName, boolean schedulerConfigured) {
        return status(jobName, schedulerConfigured).effectiveEnabled();
    }

    @Transactional
    public BatchJobRuntimeStatus status(String jobName, boolean schedulerConfigured) {
        ControlRow controlRow = findOrCreateControlRow(jobName);
        return toStatus(controlRow, schedulerConfigured);
    }

    @Transactional
    public BatchJobRuntimeStatus update(
            String jobName,
            boolean schedulerConfigured,
            boolean nextRuntimeEnabled,
            String updatedBy
    ) {
        findOrCreateControlRow(jobName);
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                """
                update stock_batch_job_control
                   set runtime_enabled = ?,
                       updated_by = ?,
                       updated_at = ?
                 where job_name = ?
                """,
                nextRuntimeEnabled,
                normalizeUpdatedBy(updatedBy),
                now,
                BatchJobNames.normalize(jobName)
        );
        return toStatus(requireControlRow(jobName), schedulerConfigured);
    }

    private ControlRow findOrCreateControlRow(String jobName) {
        return findControlRow(jobName)
                .orElseGet(() -> {
                    insertInitialControlRow(jobName);
                    return requireControlRow(jobName);
                });
    }

    private ControlRow requireControlRow(String jobName) {
        return findControlRow(jobName)
                .orElseThrow(() -> new IllegalStateException(
                        "Batch job runtime control row not found: " + BatchJobNames.normalize(jobName)
                ));
    }

    private Optional<ControlRow> findControlRow(String jobName) {
        return jdbcClient.sql(
                """
                select job_name, runtime_enabled, updated_by, updated_at
                  from stock_batch_job_control
                 where job_name = ?
                """
        )
                .params(BatchJobNames.normalize(jobName))
                .query((rs, rowNum) -> mapControlRow(rs))
                .optional();
    }

    private void insertInitialControlRow(String jobName) {
        LocalDateTime now = LocalDateTime.now();
        try {
            jdbcTemplate.update(
                    """
                    insert into stock_batch_job_control(job_name, runtime_enabled, updated_by, created_at, updated_at)
                    values (?, ?, ?, ?, ?)
                    """,
                    BatchJobNames.normalize(jobName),
                    DEFAULT_CONTROL_ROW_ENABLED,
                    SYSTEM_UPDATED_BY,
                    now,
                    now
            );
        } catch (DuplicateKeyException ignored) {
            // Another batch instance initialized the shared control row first.
        }
    }

    private BatchJobRuntimeStatus toStatus(ControlRow controlRow, boolean schedulerConfigured) {
        return new BatchJobRuntimeStatus(
                controlRow.jobName(),
                schedulerConfigured,
                controlRow.runtimeEnabled(),
                schedulerConfigured && controlRow.runtimeEnabled(),
                controlRow.updatedBy(),
                controlRow.updatedAt()
        );
    }

    private ControlRow mapControlRow(ResultSet rs) throws SQLException {
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new ControlRow(
                rs.getString("job_name"),
                rs.getBoolean("runtime_enabled"),
                rs.getString("updated_by"),
                updatedAt == null ? null : updatedAt.toLocalDateTime()
        );
    }

    private String normalizeUpdatedBy(String updatedBy) {
        if (!StringUtils.hasText(updatedBy)) {
            return SYSTEM_UPDATED_BY;
        }
        String normalized = updatedBy.trim();
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }

    private record ControlRow(
            String jobName,
            boolean runtimeEnabled,
            String updatedBy,
            LocalDateTime updatedAt
    ) {
    }
}
