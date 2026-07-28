package stock.batch.service.automarket.biz;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.automarket.v3.AutoParticipantV3Policy;

@Service
class AutoParticipantPolicyActivationService {

    private static final int MAX_DUE_POLICY_COUNT = 20;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    AutoParticipantPolicyActivationService(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    int activateDuePolicies(LocalDate tradeDate, LocalDateTime activatedAt) {
        Integer result = transactionTemplate.execute(status ->
                activateDuePoliciesInTransaction(tradeDate, activatedAt)
        );
        return result == null ? 0 : result;
    }

    private int activateDuePoliciesInTransaction(
            LocalDate tradeDate,
            LocalDateTime activatedAt
    ) {
        Integer existingDailyState = jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_auto_participant_daily_behavior_state
                 where simulation_trade_date = ?
                """,
                Integer.class,
                tradeDate
        );
        List<PolicyRow> due = jdbcTemplate.query(
                """
                select policy_version, policy_json
                  from stock_auto_participant_policy_revision
                 where status = 'SCHEDULED'
                   and effective_trade_date <= ?
                 order by effective_trade_date asc, policy_version asc
                 for update
                """,
                (rs, rowNum) -> new PolicyRow(
                        rs.getLong("policy_version"),
                        rs.getString("policy_json")
                ),
                tradeDate
        );
        if (due.isEmpty()) {
            requireExactlyOneActivePolicy(tradeDate);
            return 0;
        }
        if (existingDailyState != null && existingDailyState > 0) {
            throw new IllegalStateException(
                    "Scheduled V3 policy cannot activate after daily behavior state creation"
            );
        }
        if (due.size() > MAX_DUE_POLICY_COUNT) {
            throw new IllegalStateException("Too many scheduled V3 policies are due: " + due.size());
        }
        int activated = 0;
        for (PolicyRow policy : due) {
            AutoParticipantV3Policy.fromJson(
                    policy.policyVersion(),
                    policy.policyJson(),
                    objectMapper
            );
            jdbcTemplate.update(
                    """
                    update stock_auto_participant_policy_revision
                       set status = 'RETIRED',
                           retired_at = ?
                     where status = 'ACTIVE'
                    """,
                    activatedAt
            );
            int updated = jdbcTemplate.update(
                    """
                    update stock_auto_participant_policy_revision
                       set status = 'ACTIVE',
                           activated_at = ?,
                           retired_at = null
                     where policy_version = ?
                       and status = 'SCHEDULED'
                    """,
                    activatedAt,
                    policy.policyVersion()
            );
            if (updated != 1) {
                throw new IllegalStateException(
                        "Scheduled V3 policy activation count mismatch: version="
                                + policy.policyVersion()
                );
            }
            activated++;
        }
        requireExactlyOneActivePolicy(tradeDate);
        return activated;
    }

    private void requireExactlyOneActivePolicy(LocalDate tradeDate) {
        Integer activeCount = jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_auto_participant_policy_revision
                 where status = 'ACTIVE'
                   and effective_trade_date <= ?
                """,
                Integer.class,
                tradeDate
        );
        if (activeCount == null || activeCount != 1) {
            throw new IllegalStateException(
                    "Pre-open requires exactly one effective ACTIVE V3 policy: count="
                            + activeCount
            );
        }
    }

    private record PolicyRow(long policyVersion, String policyJson) {
    }
}
