package stock.batch.service.automarket.biz;

import java.time.LocalDateTime;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class AutoParticipantCashFlowRunLedger {

    static final int MAX_RUN_KEY_LENGTH = 160;

    private final JdbcTemplate jdbcTemplate;

    RunProgress initializeAndRead(String runKey, String operation) {
        String normalizedRunKey = requireRunKey(runKey);
        String normalizedOperation = requireOperation(operation);
        jdbcTemplate.update(
                """
                insert ignore into stock_auto_participant_cash_flow_run(
                    run_key, operation, last_account_id, processed_count,
                    completed_at, created_at, updated_at
                ) values (?, ?, 0, 0, null, current_timestamp, current_timestamp)
                """,
                normalizedRunKey,
                normalizedOperation
        );
        RunProgress progress = find(normalizedRunKey, false);
        if (!normalizedOperation.equals(progress.operation())) {
            throw new IllegalStateException(
                    "Recurring cash run key is already bound to another operation: runKey=%s, expected=%s, actual=%s"
                            .formatted(normalizedRunKey, normalizedOperation, progress.operation())
            );
        }
        return progress;
    }

    RunProgress lock(String runKey) {
        return find(requireRunKey(runKey), true);
    }

    void advance(
            String runKey,
            long expectedLastAccountId,
            long nextLastAccountId,
            int processedCount
    ) {
        if (nextLastAccountId <= expectedLastAccountId) {
            throw new IllegalArgumentException("Recurring cash cursor must advance");
        }
        if (processedCount < 0) {
            throw new IllegalArgumentException("Recurring cash processedCount must not be negative");
        }
        int updated = jdbcTemplate.update(
                """
                update stock_auto_participant_cash_flow_run
                   set last_account_id = ?,
                       processed_count = processed_count + ?,
                       updated_at = current_timestamp
                 where run_key = ?
                   and last_account_id = ?
                   and completed_at is null
                """,
                nextLastAccountId,
                processedCount,
                requireRunKey(runKey),
                expectedLastAccountId
        );
        requireSingleRunUpdate("advance", runKey, updated);
    }

    void complete(String runKey, long expectedLastAccountId) {
        int updated = jdbcTemplate.update(
                """
                update stock_auto_participant_cash_flow_run
                   set completed_at = current_timestamp,
                       updated_at = current_timestamp
                 where run_key = ?
                   and last_account_id = ?
                   and completed_at is null
                """,
                requireRunKey(runKey),
                expectedLastAccountId
        );
        requireSingleRunUpdate("complete", runKey, updated);
    }

    private RunProgress find(String runKey, boolean forUpdate) {
        String lockingClause = forUpdate ? " for update" : "";
        List<RunProgress> rows = jdbcTemplate.query(
                """
                select run_key, operation, last_account_id, processed_count, completed_at
                  from stock_auto_participant_cash_flow_run
                 where run_key = ?
                """ + lockingClause,
                (resultSet, rowNum) -> new RunProgress(
                        resultSet.getString("run_key"),
                        resultSet.getString("operation"),
                        resultSet.getLong("last_account_id"),
                        resultSet.getLong("processed_count"),
                        resultSet.getObject("completed_at", LocalDateTime.class)
                ),
                runKey
        );
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Recurring cash run ledger row is missing or duplicated: runKey=%s, count=%d"
                            .formatted(runKey, rows.size())
            );
        }
        return rows.getFirst();
    }

    private void requireSingleRunUpdate(String operation, String runKey, int updated) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "Recurring cash run cursor changed while attempting to %s: runKey=%s"
                            .formatted(operation, runKey)
            );
        }
    }

    private String requireRunKey(String runKey) {
        if (runKey == null || runKey.isBlank()) {
            throw new IllegalArgumentException("Recurring cash runKey is required");
        }
        String normalized = runKey.trim();
        if (normalized.length() > MAX_RUN_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Recurring cash runKey must not exceed %d characters"
                            .formatted(MAX_RUN_KEY_LENGTH)
            );
        }
        return normalized;
    }

    private String requireOperation(String operation) {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("Recurring cash operation is required");
        }
        String normalized = operation.trim();
        if (normalized.length() > 20) {
            throw new IllegalArgumentException("Recurring cash operation must not exceed 20 characters");
        }
        return normalized;
    }

    record RunProgress(
            String runKey,
            String operation,
            long lastAccountId,
            long processedCount,
            LocalDateTime completedAt
    ) {

        boolean completed() {
            return completedAt != null;
        }
    }
}
