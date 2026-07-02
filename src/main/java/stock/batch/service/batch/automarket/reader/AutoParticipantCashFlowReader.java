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

@Component
public class AutoParticipantCashFlowReader {

    private final JdbcClient jdbcClient;

    public AutoParticipantCashFlowReader(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate));
    }

    public List<AutoParticipantRecurringCashTarget> findRecurringCashTargets() {
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
                 order by p.user_key asc
                """
        )
                .query((rs, rowNum) -> AutoMarketReaderMapper.toRecurringCashTarget(rs))
                .list();
    }

    public List<AutoParticipantRecentCashFlow> findRecentCashFlows(
            List<Long> accountIds,
            Set<String> reasons,
            String createdBy,
            LocalDateTime since
    ) {
        if (accountIds.isEmpty() || reasons.isEmpty()) {
            return List.of();
        }
        List<String> sortedReasons = reasons.stream().sorted().toList();
        return jdbcClient.sql(
                """
                select account_id, reason, created_at
                from stock_account_cash_flow
                where account_id in (:accountIds)
                  and reason in (:reasons)
                  and created_by = :createdBy
                  and created_at >= :since
                order by account_id asc, created_at desc, id desc
                """
        )
                .param("accountIds", accountIds)
                .param("reasons", sortedReasons)
                .param("createdBy", createdBy)
                .param("since", since)
                .query((rs, rowNum) -> AutoMarketReaderMapper.toRecentCashFlow(rs))
                .list();
    }
}
