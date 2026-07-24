package stock.batch.service.batch.automarket.reader;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.automarket.model.AutoParticipantRecentCashFlow;
import stock.batch.service.batch.automarket.model.AutoParticipantRecurringCashTarget;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

@Component
public class AutoParticipantCashFlowReader {

    private final JdbcClient jdbcClient;

    public AutoParticipantCashFlowReader(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate));
    }

    public List<AutoParticipantRecurringCashTarget> findRecurringCashTargetChunk(
            long afterAccountId,
            int limit
    ) {
        return jdbcClient.sql(
                """
                select a.id as account_id,
                       p.profile_type,
                       p.recurring_cash_amount,
                       p.recurring_cash_interval_value,
                       p.recurring_cash_interval_unit
                  from stock_auto_participant p
                  join stock_account a on a.user_key = p.user_key and a.status = 'ACTIVE'
                 where p.enabled = true
                   and p.withdrawn_at is null
                   and a.id > :afterAccountId
                 order by a.id asc
                 limit :limit
                """
        )
                .param("afterAccountId", afterAccountId)
                .param("limit", limit)
                .query((rs, rowNum) -> AutoMarketReaderMapper.toRecurringCashTarget(rs))
                .list();
    }

    public Set<Long> findExecutableV2FundingAccountIds(
            List<Long> accountIds,
            AutoParticipantProfileType profileType
    ) {
        if (accountIds == null || accountIds.isEmpty() || profileType == null) {
            return Set.of();
        }
        return Set.copyOf(jdbcClient.sql(
                """
                select a.id
                  from stock_account a
                  join stock_auto_participant p on p.user_key = a.user_key
                  left join stock_auto_participant_profile_config pc
                    on pc.profile_type = p.profile_type
                 where a.id in (:accountIds)
                   and a.status = 'ACTIVE'
                   and p.enabled = true
                   and p.withdrawn_at is null
                   and p.profile_type = :profileType
                   and coalesce(pc.behavior_model_version, 'V2') = 'V2'
                 order by a.id asc
                """
        )
                .param("accountIds", accountIds.stream().distinct().sorted().toList())
                .param("profileType", profileType.name())
                .query(Long.class)
                .list());
    }

    public List<AutoParticipantRecentCashFlow> findRecentCashFlows(
            List<Long> accountIds,
            Set<String> reasons,
            Set<String> createdByValues,
            LocalDateTime since
    ) {
        if (accountIds.isEmpty() || reasons.isEmpty() || createdByValues.isEmpty()) {
            return List.of();
        }
        List<String> sortedReasons = reasons.stream().sorted().toList();
        List<String> sortedCreatedByValues = createdByValues.stream().sorted().toList();
        return jdbcClient.sql(
                """
                select account_id, reason, max(created_at) as created_at
                  from stock_account_cash_flow
                 where account_id in (:accountIds)
                   and reason in (:reasons)
                   and created_by in (:createdByValues)
                   and created_at >= :since
                 group by account_id, reason
                 order by account_id asc, reason asc
                """
        )
                .param("accountIds", accountIds)
                .param("reasons", sortedReasons)
                .param("createdByValues", sortedCreatedByValues)
                .param("since", since)
                .query((rs, rowNum) -> AutoMarketReaderMapper.toRecentCashFlow(rs))
                .list();
    }
}
