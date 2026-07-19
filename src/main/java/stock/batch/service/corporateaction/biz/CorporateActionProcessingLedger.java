package stock.batch.service.corporateaction.biz;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class CorporateActionProcessingLedger {

    private static final String ALL_ACCOUNTS = "ALL";

    private final JdbcClient jdbcClient;
    private final JdbcTemplate jdbcTemplate;

    CorporateActionProcessingLedger(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public boolean isCompleted(long actionId, String actionPhase, LocalDate effectiveBusinessDate) {
        return isCompleted(actionId, ALL_ACCOUNTS, actionPhase, effectiveBusinessDate);
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public int sumCompletedAccountProcessedCount(
            long actionId,
            String actionPhase,
            LocalDate effectiveBusinessDate
    ) {
        Long processedCount = jdbcClient.sql(
                        """
                        select coalesce(sum(processed_count), 0)
                          from stock_corporate_action_processing
                         where action_id = ?
                           and account_scope_key like 'A:%'
                           and action_phase = ?
                           and effective_business_date = ?
                           and status = 'COMPLETED'
                        """
                )
                .param(actionId)
                .param(actionPhase)
                .param(effectiveBusinessDate)
                .query(Long.class)
                .single();
        return processedCount == null ? 0 : Math.toIntExact(processedCount);
    }

    private boolean isCompleted(
            long actionId,
            String accountScopeKey,
            String actionPhase,
            LocalDate effectiveBusinessDate
    ) {
        Long count = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_corporate_action_processing
                         where action_id = ?
                           and account_scope_key = ?
                           and action_phase = ?
                           and effective_business_date = ?
                           and status = 'COMPLETED'
                        """
                )
                .param(actionId)
                .param(accountScopeKey)
                .param(actionPhase)
                .param(effectiveBusinessDate)
                .query(Long.class)
                .single();
        return count != null && count == 1L;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(
            long actionId,
            String actionPhase,
            LocalDate effectiveBusinessDate,
            int processedCount,
            LocalDateTime processedAt
    ) {
        complete(
                actionId,
                ALL_ACCOUNTS,
                actionPhase,
                effectiveBusinessDate,
                processedCount,
                null,
                null,
                null,
                processedAt
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void completeAccount(
            long actionId,
            long accountId,
            String actionPhase,
            LocalDate effectiveBusinessDate,
            int processedCount,
            BigDecimal amount,
            Long quantity,
            String ledgerReferenceId,
            LocalDateTime processedAt
    ) {
        complete(
                actionId,
                accountScopeKey(accountId),
                actionPhase,
                effectiveBusinessDate,
                processedCount,
                amount,
                quantity,
                ledgerReferenceId,
                processedAt
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int completeAccounts(
            long actionId,
            String actionPhase,
            LocalDate effectiveBusinessDate,
            List<AccountCompletion> completions,
            LocalDateTime processedAt
    ) {
        if (completions.isEmpty()) {
            return 0;
        }
        String values = String.join(",", Collections.nCopies(
                completions.size(),
                "(?, ?, ?, ?, 'COMPLETED', 1, ?, ?, ?, ?, ?, null, ?, ?)"
        ));
        List<Object> parameters = new ArrayList<>(completions.size() * 11);
        for (AccountCompletion completion : completions) {
            parameters.add(actionId);
            parameters.add(accountScopeKey(completion.accountId()));
            parameters.add(actionPhase);
            parameters.add(effectiveBusinessDate);
            parameters.add(completion.processedCount());
            parameters.add(completion.amount());
            parameters.add(completion.quantity());
            parameters.add(completion.ledgerReferenceId());
            parameters.add(processedAt);
            parameters.add(processedAt);
            parameters.add(processedAt);
        }
        return jdbcTemplate.update(
                """
                insert into stock_corporate_action_processing(
                    action_id, account_scope_key, action_phase, effective_business_date,
                    status, attempt_count, processed_count, amount, quantity,
                    ledger_reference_id, processed_at, last_error, created_at, updated_at
                ) values %s
                """.formatted(values),
                parameters.toArray()
        );
    }

    private void complete(
            long actionId,
            String accountScopeKey,
            String actionPhase,
            LocalDate effectiveBusinessDate,
            int processedCount,
            BigDecimal amount,
            Long quantity,
            String ledgerReferenceId,
            LocalDateTime processedAt
    ) {
        try {
            jdbcClient.sql(
                            """
                            insert into stock_corporate_action_processing(
                                action_id, account_scope_key, action_phase, effective_business_date,
                                status, attempt_count, processed_count, amount, quantity,
                                ledger_reference_id, processed_at, last_error, created_at, updated_at
                            )
                            values (?, ?, ?, ?, 'COMPLETED', 1, ?, ?, ?, ?, ?, null, ?, ?)
                            """
                    )
                    .param(actionId)
                    .param(accountScopeKey)
                    .param(actionPhase)
                    .param(effectiveBusinessDate)
                    .param(processedCount)
                    .param(amount)
                    .param(quantity)
                    .param(ledgerReferenceId)
                    .param(processedAt)
                    .param(processedAt)
                    .param(processedAt)
                    .update();
        } catch (DuplicateKeyException duplicate) {
            if (!isCompleted(actionId, accountScopeKey, actionPhase, effectiveBusinessDate)) {
                throw duplicate;
            }
        }
    }

    static String accountScopeKey(long accountId) {
        return "A:" + accountId;
    }

    record AccountCompletion(
            long accountId,
            int processedCount,
            BigDecimal amount,
            Long quantity,
            String ledgerReferenceId
    ) {
    }
}
