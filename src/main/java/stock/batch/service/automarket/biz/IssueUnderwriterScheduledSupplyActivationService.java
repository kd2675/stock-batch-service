package stock.batch.service.automarket.biz;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.batch.config.BatchRepositoryDataSourceConfig;

@Service
@Slf4j
public class IssueUnderwriterScheduledSupplyActivationService {

    private static final int MAX_POLICIES_PER_ACTIVATION = 100;

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public IssueUnderwriterScheduledSupplyActivationService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            @Qualifier(BatchRepositoryDataSourceConfig.BUSINESS_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public int activateDuePolicies(LocalDate businessDate, LocalDateTime activatedAt) {
        if (businessDate == null || activatedAt == null) {
            throw new IllegalArgumentException(
                    "Issue-underwriter activation requires businessDate and activatedAt"
            );
        }
        List<ScheduledPolicy> policies = jdbcClient.sql(
                        """
                        select id, scope_key, version_no, config_json
                          from stock_market_policy_version
                         where policy_scope = 'UNDERWRITING_CONTRACT'
                           and status = 'SCHEDULED'
                           and effective_business_date <= :businessDate
                         order by effective_business_date, scope_key, version_no
                        """
                )
                .param("businessDate", businessDate)
                .query((rs, rowNum) -> new ScheduledPolicy(
                        rs.getLong("id"),
                        rs.getString("scope_key"),
                        rs.getLong("version_no"),
                        rs.getString("config_json")
                ))
                .list();
        if (policies.size() > MAX_POLICIES_PER_ACTIVATION) {
            throw new IllegalStateException(
                    "Too many scheduled issue-underwriter policies are due: " + policies.size()
            );
        }
        requireUniqueContracts(policies);
        int activatedCount = 0;
        for (ScheduledPolicy policy : policies) {
            try {
                transactionTemplate.executeWithoutResult(ignored ->
                        activatePolicy(policy, businessDate, activatedAt)
                );
                activatedCount = Math.addExact(activatedCount, 1);
            } catch (RuntimeException ex) {
                log.error(
                        "Scheduled issue-underwriter policy activation failed and was rolled back: "
                                + "businessDate={}, contractCode={}, policyId={}",
                        businessDate,
                        policy.contractCode(),
                        policy.id(),
                        ex
                );
            }
        }
        if (activatedCount > 0) {
            log.info(
                    "Scheduled issue-underwriter supply policies activated: businessDate={}, count={}",
                    businessDate,
                    activatedCount
            );
        }
        return activatedCount;
    }

    private void activatePolicy(
            ScheduledPolicy policy,
            LocalDate businessDate,
            LocalDateTime activatedAt
    ) {
        PolicyConfig config = parsePolicy(policy);
        Contract contract = lockContract(policy.contractCode());
        if (config.contractId() != contract.id()
                || !policy.contractCode().equals(config.contractCode())
                || !contract.symbol().equals(config.symbol())
                || !"ACTIVATE_SUPPLY".equals(config.activationAction())
                || !"STABILIZING".equals(config.targetStatus())) {
            throw new IllegalStateException(
                    "Scheduled issue-underwriter policy identity is inconsistent: "
                            + policy.contractCode()
            );
        }
        if (!"ALLOCATED".equals(contract.status())) {
            throw new IllegalStateException(
                    "Scheduled issue-underwriter contract is not allocated: "
                            + policy.contractCode()
            );
        }
        if (policy.version() != Math.addExact(contract.policyVersion(), 1L)) {
            throw new IllegalStateException(
                    "Scheduled issue-underwriter policy version is not sequential: "
                            + policy.contractCode()
            );
        }
        RoleInventory inventory = lockRoleInventory(contract, businessDate);
        validateRoleInventory(inventory, policy.contractCode());
        validateReconciliation(contract);
        requireMarketReady(contract.symbol());

        long availableQuantity = inventory.holdingQuantity() - inventory.reservedQuantity();
        long requestedQuantity = BigDecimal.valueOf(availableQuantity)
                .multiply(config.supplyRate())
                .setScale(0, RoundingMode.DOWN)
                .longValueExact();
        requestedQuantity = Math.min(Math.max(1L, requestedQuantity), availableQuantity);
        SupplyUsage usage = findSupplyUsage(contract.id());
        long quantityLimit = Math.addExact(usage.submittedQuantity(), requestedQuantity);
        BigDecimal amountLimit = usage.submittedAmount()
                .add(contract.issuePrice().multiply(BigDecimal.valueOf(requestedQuantity)))
                .setScale(2, RoundingMode.HALF_UP);
        LocalDate endDate = businessDate.plusDays(config.durationDays() - 1L);

        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        update stock_underwriting_contract
                           set stabilization_start_date = ?,
                               stabilization_end_date = ?,
                               stabilization_quantity_limit = ?,
                               stabilization_amount_limit = ?,
                               status = 'STABILIZING',
                               policy_version = ?,
                               updated_at = ?
                         where id = ?
                           and status = 'ALLOCATED'
                           and policy_version = ?
                        """,
                        businessDate,
                        endDate,
                        quantityLimit,
                        amountLimit,
                        policy.version(),
                        activatedAt,
                        contract.id(),
                        contract.policyVersion()
                ),
                "Scheduled issue-underwriter contract activation"
        );
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        update stock_market_policy_version
                           set status = 'RETIRED',
                               updated_at = ?
                         where policy_scope = 'UNDERWRITING_CONTRACT'
                           and scope_key = ?
                           and status = 'ACTIVE'
                        """,
                        activatedAt,
                        policy.contractCode()
                ),
                "Current issue-underwriter policy retirement"
        );
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        update stock_market_policy_version
                           set status = 'ACTIVE',
                               config_json = ?,
                               updated_at = ?
                         where id = ?
                           and status = 'SCHEDULED'
                        """,
                        activePolicyJson(config, quantityLimit, amountLimit),
                        activatedAt,
                        policy.id()
                ),
                "Scheduled issue-underwriter policy activation"
        );
    }

    private Contract lockContract(String contractCode) {
        return jdbcClient.sql(
                        """
                        select id, contract_code, symbol, participant_id, account_id,
                               total_issue_quantity, tradable_allocation_quantity,
                               locked_allocation_quantity, external_allocation_quantity,
                               underwritten_quantity, issue_price, status, policy_version
                          from stock_underwriting_contract
                         where contract_code = :contractCode
                         for update
                        """
                )
                .param("contractCode", contractCode)
                .query((rs, rowNum) -> new Contract(
                        rs.getLong("id"),
                        rs.getString("contract_code"),
                        rs.getString("symbol"),
                        rs.getLong("participant_id"),
                        rs.getLong("account_id"),
                        rs.getLong("total_issue_quantity"),
                        rs.getLong("tradable_allocation_quantity"),
                        rs.getLong("locked_allocation_quantity"),
                        rs.getLong("external_allocation_quantity"),
                        rs.getLong("underwritten_quantity"),
                        rs.getBigDecimal("issue_price"),
                        rs.getString("status"),
                        rs.getLong("policy_version")
                ))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Scheduled issue-underwriter contract is missing: " + contractCode
                ));
    }

    private RoleInventory lockRoleInventory(Contract contract, LocalDate businessDate) {
        return jdbcClient.sql(
                        """
                        select account.status as account_status,
                               account.participant_category,
                               account.self_trade_group_id as account_group,
                               participant.participant_type,
                               participant.status as participant_status,
                               participant.self_trade_group_id as participant_group,
                               mapping.account_role,
                               mapping.status as mapping_status,
                               mapping.effective_from,
                               mapping.effective_to,
                               coalesce(holding.quantity, 0) as holding_quantity,
                               coalesce(holding.reserved_quantity, 0) as reserved_quantity,
                               (
                                   select count(*)
                                     from stock_order open_order
                                    where open_order.account_id = :accountId
                                      and open_order.status in ('PENDING', 'PARTIALLY_FILLED')
                                      and open_order.quantity > open_order.filled_quantity
                               ) as open_order_count
                          from stock_account account
                          join stock_market_participant participant
                            on participant.id = :participantId
                          left join stock_market_participant_account mapping
                            on mapping.participant_id = participant.id
                           and mapping.account_id = account.id
                          left join stock_holding holding
                            on holding.account_id = account.id
                           and holding.symbol = :symbol
                         where account.id = :accountId
                         for update
                        """
                )
                .param("participantId", contract.participantId())
                .param("accountId", contract.accountId())
                .param("symbol", contract.symbol())
                .query((rs, rowNum) -> new RoleInventory(
                        rs.getString("account_status"),
                        rs.getString("participant_category"),
                        rs.getString("account_group"),
                        rs.getString("participant_type"),
                        rs.getString("participant_status"),
                        rs.getString("participant_group"),
                        rs.getString("account_role"),
                        rs.getString("mapping_status"),
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getObject("effective_to", LocalDate.class),
                        rs.getLong("holding_quantity"),
                        rs.getLong("reserved_quantity"),
                        rs.getLong("open_order_count"),
                        businessDate
                ))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Scheduled issue-underwriter role is missing: " + contract.contractCode()
                ));
    }

    private void validateRoleInventory(RoleInventory role, String contractCode) {
        boolean datesActive = role.effectiveFrom() != null
                && !role.businessDate().isBefore(role.effectiveFrom())
                && (role.effectiveTo() == null
                || !role.businessDate().isAfter(role.effectiveTo()));
        if (!"ACTIVE".equals(role.accountStatus())
                || !"ISSUE_UNDERWRITER".equals(role.participantCategory())
                || !"ISSUE_UNDERWRITER".equals(role.participantType())
                || !"ACTIVE".equals(role.participantStatus())
                || !"ISSUE_UNDERWRITER".equals(role.accountRole())
                || !"ACTIVE".equals(role.mappingStatus())
                || !datesActive
                || role.accountGroup() == null
                || !role.accountGroup().equals(role.participantGroup())
                || role.holdingQuantity() <= role.reservedQuantity()
                || role.openOrderCount() > 0L) {
            throw new IllegalStateException(
                    "Scheduled issue-underwriter role is not activation-ready: " + contractCode
            );
        }
    }

    private void requireMarketReady(String symbol) {
        Boolean ready = jdbcClient.sql(
                        """
                        select exists(
                            select 1
                              from stock_order_book_instrument instrument
                              join stock_order_book_market_config market
                                on market.symbol = instrument.symbol
                             where instrument.symbol = :symbol
                               and instrument.enabled = true
                               and instrument.tradable_shares > 0
                               and market.enabled = true
                               and market.market_status = 'CLOSED'
                        )
                        """
                )
                .param("symbol", symbol)
                .query(Boolean.class)
                .single();
        if (!Boolean.TRUE.equals(ready)) {
            throw new IllegalStateException(
                    "Scheduled issue-underwriter market is not activation-ready: " + symbol
            );
        }
    }

    private void validateReconciliation(Contract contract) {
        Reconciliation reconciliation = jdbcClient.sql(
                        """
                        select instrument.issued_shares,
                               instrument.tradable_shares,
                               (
                                   select coalesce(sum(holding.quantity), 0)
                                     from stock_holding holding
                                    where holding.symbol = :symbol
                               ) as total_holding_quantity,
                               (
                                   select count(*)
                                     from stock_holding holding
                                    where holding.symbol = :symbol
                                      and (
                                          holding.quantity < 0
                                          or holding.reserved_quantity < 0
                                          or holding.reserved_quantity > holding.quantity
                                      )
                               ) as invalid_holding_count,
                               (
                                   select coalesce(sum(allocation.quantity), 0)
                                     from stock_security_allocation_ledger allocation
                                    where allocation.underwriting_contract_id = :contractId
                                      and allocation.event_type = 'INITIAL_ISSUE'
                                      and allocation.source_account_id is null
                               ) as initial_ledger_quantity,
                               (
                                   select coalesce(sum(allocation.quantity), 0)
                                     from stock_security_allocation_ledger allocation
                                    where allocation.underwriting_contract_id = :contractId
                                      and allocation.event_type = 'INITIAL_ISSUE'
                                      and allocation.source_account_id is null
                                      and allocation.tradability_status = 'TRADABLE'
                               ) as initial_tradable_ledger_quantity,
                               (
                                   select coalesce(sum(allocation.quantity), 0)
                                     from stock_security_allocation_ledger allocation
                                    where allocation.underwriting_contract_id = :contractId
                                      and allocation.event_type = 'INITIAL_ISSUE'
                                      and allocation.source_account_id is null
                                      and allocation.tradability_status = 'LOCKED'
                               ) as initial_locked_ledger_quantity
                          from stock_order_book_instrument instrument
                         where instrument.symbol = :symbol
                        """
                )
                .param("symbol", contract.symbol())
                .param("contractId", contract.id())
                .query((rs, rowNum) -> new Reconciliation(
                        rs.getLong("issued_shares"),
                        rs.getLong("tradable_shares"),
                        rs.getLong("total_holding_quantity"),
                        rs.getLong("invalid_holding_count"),
                        rs.getLong("initial_ledger_quantity"),
                        rs.getLong("initial_tradable_ledger_quantity"),
                        rs.getLong("initial_locked_ledger_quantity")
                ))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Scheduled issue-underwriter reconciliation is missing: "
                                + contract.contractCode()
                ));
        if (!reconciliation.matches(contract)) {
            throw new IllegalStateException(
                    "Scheduled issue-underwriter reconciliation failed: "
                            + contract.contractCode()
            );
        }
    }

    private SupplyUsage findSupplyUsage(long contractId) {
        return jdbcClient.sql(
                        """
                        select coalesce(sum(submitted_quantity), 0) as submitted_quantity,
                               coalesce(sum(submitted_amount), 0) as submitted_amount
                          from stock_underwriting_daily_supply_state
                         where underwriting_contract_id = :contractId
                        """
                )
                .param("contractId", contractId)
                .query((rs, rowNum) -> new SupplyUsage(
                        rs.getLong("submitted_quantity"),
                        rs.getBigDecimal("submitted_amount")
                ))
                .single();
    }

    private PolicyConfig parsePolicy(ScheduledPolicy policy) {
        JsonNode root;
        try {
            root = objectMapper.readTree(policy.configJson());
            if (root != null && root.isTextual()) {
                root = objectMapper.readTree(root.textValue());
            }
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Scheduled issue-underwriter policy JSON is invalid: "
                            + policy.contractCode(),
                    ex
            );
        }
        if (root == null || !root.isObject()) {
            throw new IllegalStateException(
                    "Scheduled issue-underwriter policy JSON must be an object: "
                            + policy.contractCode()
            );
        }
        BigDecimal supplyRate = required(root, "supplyRate").decimalValue();
        int durationDays = required(root, "durationDays").intValue();
        if (supplyRate.compareTo(new BigDecimal("0.010000")) < 0
                || supplyRate.compareTo(new BigDecimal("0.250000")) > 0
                || durationDays < 1
                || durationDays > 60) {
            throw new IllegalStateException(
                    "Scheduled issue-underwriter policy limits are invalid: "
                            + policy.contractCode()
            );
        }
        return new PolicyConfig(
                required(root, "activationAction").asText(),
                required(root, "targetStatus").asText(),
                required(root, "contractId").longValue(),
                required(root, "contractCode").asText(),
                required(root, "symbol").asText(),
                supplyRate,
                durationDays
        );
    }

    private JsonNode required(JsonNode root, String fieldName) {
        JsonNode value = root.get(fieldName);
        if (value == null || value.isNull()) {
            throw new IllegalStateException(
                    "Scheduled issue-underwriter policy field is missing: " + fieldName
            );
        }
        return value;
    }

    private String activePolicyJson(
            PolicyConfig config,
            long quantityLimit,
            BigDecimal amountLimit
    ) {
        try {
            JsonNode root = objectMapper.createObjectNode()
                    .put("preset", "INDEPENDENT_PASSIVE_UNDERWRITER_SUPPLY_V1")
                    .put("activationAction", config.activationAction())
                    .put("targetStatus", config.targetStatus())
                    .put("contractId", config.contractId())
                    .put("contractCode", config.contractCode())
                    .put("symbol", config.symbol())
                    .put("supplyRate", config.supplyRate())
                    .put("durationDays", config.durationDays())
                    .put("quantityLimit", quantityLimit)
                    .put("amountLimit", amountLimit)
                    .put("sellOnly", true)
                    .put("passiveOnly", true)
                    .put("cancellationRefundsSubmissionBudget", false);
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Active issue-underwriter policy JSON serialization failed",
                    ex
            );
        }
    }

    private void requireUniqueContracts(List<ScheduledPolicy> policies) {
        Set<String> contractCodes = new HashSet<>();
        for (ScheduledPolicy policy : policies) {
            if (!contractCodes.add(policy.contractCode())) {
                throw new IllegalStateException(
                        "Multiple scheduled issue-underwriter policies target one contract: "
                                + policy.contractCode()
                );
            }
        }
    }

    private void requireSingleUpdate(int count, String operation) {
        if (count != 1) {
            throw new IllegalStateException(
                    operation + " count mismatch: expected=1, actual=" + count
            );
        }
    }

    private record ScheduledPolicy(
            long id,
            String contractCode,
            long version,
            String configJson
    ) {
    }

    private record PolicyConfig(
            String activationAction,
            String targetStatus,
            long contractId,
            String contractCode,
            String symbol,
            BigDecimal supplyRate,
            int durationDays
    ) {
    }

    private record Contract(
            long id,
            String contractCode,
            String symbol,
            long participantId,
            long accountId,
            long totalIssueQuantity,
            long tradableAllocationQuantity,
            long lockedAllocationQuantity,
            long externalAllocationQuantity,
            long underwrittenQuantity,
            BigDecimal issuePrice,
            String status,
            long policyVersion
    ) {
    }

    private record RoleInventory(
            String accountStatus,
            String participantCategory,
            String accountGroup,
            String participantType,
            String participantStatus,
            String participantGroup,
            String accountRole,
            String mappingStatus,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            long holdingQuantity,
            long reservedQuantity,
            long openOrderCount,
            LocalDate businessDate
    ) {
    }

    private record SupplyUsage(long submittedQuantity, BigDecimal submittedAmount) {
    }

    private record Reconciliation(
            long issuedShares,
            long tradableShares,
            long totalHoldingQuantity,
            long invalidHoldingCount,
            long initialLedgerQuantity,
            long initialTradableLedgerQuantity,
            long initialLockedLedgerQuantity
    ) {
        boolean matches(Contract contract) {
            try {
                return Math.addExact(
                        contract.tradableAllocationQuantity(),
                        contract.lockedAllocationQuantity()
                ) == contract.totalIssueQuantity()
                        && Math.addExact(
                                contract.externalAllocationQuantity(),
                                contract.underwrittenQuantity()
                        ) == contract.tradableAllocationQuantity()
                        && issuedShares >= contract.totalIssueQuantity()
                        && tradableShares >= contract.tradableAllocationQuantity()
                        && totalHoldingQuantity == issuedShares
                        && invalidHoldingCount == 0L
                        && initialLedgerQuantity == contract.totalIssueQuantity()
                        && initialTradableLedgerQuantity
                        == contract.tradableAllocationQuantity()
                        && initialLockedLedgerQuantity
                        == contract.lockedAllocationQuantity();
            } catch (ArithmeticException ignored) {
                return false;
            }
        }
    }
}
