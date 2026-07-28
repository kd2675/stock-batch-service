package stock.batch.service.automarket.biz;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
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
import stock.batch.service.simulation.SimulationMarketSessionService;
import web.common.core.simulation.SimulationMarketSession;

@Service
@Slf4j
public class LiquidityProviderScheduledPolicyActivationService {

    private static final int MAX_POLICIES_PER_ACTIVATION = 100;

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final SimulationMarketSessionService marketSessionService;
    private final TransactionTemplate transactionTemplate;

    public LiquidityProviderScheduledPolicyActivationService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            SimulationMarketSessionService marketSessionService,
            @Qualifier(BatchRepositoryDataSourceConfig.BUSINESS_TRANSACTION_MANAGER)
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.objectMapper = objectMapper;
        this.marketSessionService = marketSessionService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public int activateDuePolicies(LocalDate businessDate, LocalDateTime activatedAt) {
        return activateDuePolicies(
                businessDate,
                activatedAt,
                marketSessionService.currentSession() == SimulationMarketSession.PRE_OPEN
        );
    }

    public int activateDuePoliciesForPreOpen(
            LocalDate businessDate,
            LocalDateTime activatedAt
    ) {
        return activateDuePolicies(businessDate, activatedAt, true);
    }

    private int activateDuePolicies(
            LocalDate businessDate,
            LocalDateTime activatedAt,
            boolean lifecycleActivationAllowed
    ) {
        if (businessDate == null || activatedAt == null) {
            throw new IllegalArgumentException(
                    "LP policy activation requires businessDate and activatedAt"
            );
        }
        List<DuePolicyReference> duePolicies = jdbcClient.sql(
                        """
                        select id, scope_key
                          from stock_market_policy_version
                         where policy_scope = 'LIQUIDITY_MANDATE'
                           and status = 'SCHEDULED'
                           and effective_business_date <= :businessDate
                         order by effective_business_date asc, scope_key asc, version_no asc
                        """
                )
                .param("businessDate", businessDate)
                .query((rs, rowNum) -> new DuePolicyReference(
                        rs.getLong("id"),
                        rs.getString("scope_key")
                ))
                .list();
        if (duePolicies.size() > MAX_POLICIES_PER_ACTIVATION) {
            throw new IllegalStateException(
                    "Too many scheduled LP policies are due: " + duePolicies.size()
            );
        }
        requireUniqueDueSymbols(duePolicies);
        int activatedCount = 0;
        for (DuePolicyReference duePolicy : duePolicies) {
            try {
                Boolean activated = transactionTemplate.execute(ignored ->
                        activatePolicy(
                                duePolicy,
                                businessDate,
                                activatedAt,
                                lifecycleActivationAllowed
                        )
                );
                if (Boolean.TRUE.equals(activated)) {
                    activatedCount = Math.addExact(activatedCount, 1);
                }
            } catch (RuntimeException ex) {
                log.error(
                        "Scheduled LP policy activation failed and was rolled back: "
                                + "businessDate={}, symbol={}, policyId={}",
                        businessDate,
                        duePolicy.symbol(),
                        duePolicy.id(),
                        ex
                );
            }
        }
        if (activatedCount > 0) {
            log.info(
                    "Scheduled liquidity-provider policies activated: businessDate={}, count={}",
                    businessDate,
                    activatedCount
            );
        }
        return activatedCount;
    }

    private boolean activatePolicy(
            DuePolicyReference duePolicy,
            LocalDate businessDate,
            LocalDateTime activatedAt,
            boolean lifecycleActivationAllowed
    ) {
        MandateRow mandate = lockMandate(duePolicy.symbol());
        ScheduledPolicyRow scheduledPolicy = lockScheduledPolicy(
                duePolicy.id(),
                businessDate
        );
        if (scheduledPolicy == null) {
            return false;
        }
        String activationAction = activationAction(scheduledPolicy);
        if (isLifecycleActivation(activationAction) && !lifecycleActivationAllowed) {
            return false;
        }
        if ("PROVISION".equals(activationAction)) {
            return activateProvision(
                    mandate,
                    scheduledPolicy,
                    businessDate,
                    activatedAt
            );
        }
        if (!"LIVE".equals(mandate.executionMode())
                || (!"ACTIVE".equals(mandate.status())
                && !"SUSPENDED".equals(mandate.status()))) {
            throw new IllegalStateException(
                    "Scheduled LP policy target is not LIVE: " + scheduledPolicy.symbol()
            );
        }
        long expectedVersion = Math.addExact(mandate.policyVersion(), 1L);
        if (scheduledPolicy.version() != expectedVersion) {
            throw new IllegalStateException(
                    "Scheduled LP policy version is not sequential: symbol=%s, current=%d, scheduled=%d"
                            .formatted(
                                    scheduledPolicy.symbol(),
                                    mandate.policyVersion(),
                                    scheduledPolicy.version()
                            )
            );
        }
        PolicyConfig policy = parsePolicy(scheduledPolicy);
        if (!scheduledPolicy.symbol().equals(policy.symbol())
                || !"LIVE".equals(policy.executionMode())
                || !policy.passiveOnly()) {
            throw new IllegalStateException(
                    "Scheduled LP policy identity or execution mode is invalid: "
                            + scheduledPolicy.symbol()
            );
        }
        requireNoOpenOrders(mandate);
        requireUnusedDailyState(mandate.id(), businessDate);
        deleteEmptyDailyState(mandate.id(), businessDate);

        String targetStatus = "RESUME".equals(activationAction)
                ? "ACTIVE"
                : mandate.status();
        if ("RESUME".equals(activationAction) && !"SUSPENDED".equals(mandate.status())) {
            throw new IllegalStateException(
                    "Scheduled LP resume target is not suspended: " + scheduledPolicy.symbol()
            );
        }
        LocalDateTime nextQuoteAt = "ACTIVE".equals(targetStatus)
                ? businessDate.atTime(marketSessionService.openTime())
                : null;
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        update stock_liquidity_mandate
                           set target_spread_ticks = ?,
                               max_spread_ticks = ?,
                               max_order_quantity = ?,
                               reference_daily_volume = ?,
                               target_open_participation_rate = ?,
                               max_open_participation_rate = ?,
                               max_single_order_participation_rate = ?,
                               external_depth_levels = ?,
                               max_external_depth_participation_rate = ?,
                               daily_execution_participation_rate = ?,
                               daily_submission_multiplier = ?,
                               target_inventory_quantity = ?,
                               inventory_band_quantity = ?,
                               inventory_skew_ticks = ?,
                               primary_regime_weight = ?,
                               liquidity_size_sensitivity = ?,
                               volatility_spread_max_ticks = ?,
                               price_regime_max_skew_ticks = ?,
                               passive_only = ?,
                               minimum_quote_lifetime_seconds = ?,
                               reprice_threshold_ticks = ?,
                               order_ttl_seconds = ?,
                               quote_interval_seconds = ?,
                               daily_loss_limit_amount = ?,
                               status = ?,
                               next_quote_at = ?,
                               policy_version = ?,
                               updated_at = ?
                         where id = ?
                           and policy_version = ?
                        """,
                        policy.targetSpreadTicks(),
                        policy.maxSpreadTicks(),
                        policy.maxOrderQuantity(),
                        policy.referenceDailyVolume(),
                        policy.targetOpenParticipationRate(),
                        policy.maxOpenParticipationRate(),
                        policy.maxSingleOrderParticipationRate(),
                        policy.externalDepthLevels(),
                        policy.maxExternalDepthParticipationRate(),
                        policy.dailyExecutionParticipationRate(),
                        policy.dailySubmissionMultiplier(),
                        policy.targetInventoryQuantity(),
                        policy.inventoryBandQuantity(),
                        policy.inventorySkewTicks(),
                        policy.primaryRegimeWeight(),
                        policy.liquiditySizeSensitivity(),
                        policy.volatilitySpreadMaxTicks(),
                        policy.priceRegimeMaxSkewTicks(),
                        policy.passiveOnly(),
                        policy.minimumQuoteLifetimeSeconds(),
                        policy.repriceThresholdTicks(),
                        policy.orderTtlSeconds(),
                        policy.quoteIntervalSeconds(),
                        policy.dailyLossLimitAmount(),
                        targetStatus,
                        nextQuoteAt,
                        scheduledPolicy.version(),
                        activatedAt,
                        mandate.id(),
                        mandate.policyVersion()
                ),
                "Scheduled LP mandate policy activation"
        );
        updateTransition(
                mandate.id(),
                scheduledPolicy.version(),
                "RESUME".equals(activationAction),
                activatedAt
        );
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        update stock_market_policy_version
                           set status = 'RETIRED',
                               updated_at = ?
                         where policy_scope = 'LIQUIDITY_MANDATE'
                           and scope_key = ?
                           and status = 'ACTIVE'
                        """,
                        activatedAt,
                        scheduledPolicy.symbol()
                ),
                "Current LP policy retirement"
        );
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        update stock_market_policy_version
                           set status = 'ACTIVE',
                               updated_at = ?
                         where id = ?
                           and status = 'SCHEDULED'
                           and version_no = ?
                        """,
                        activatedAt,
                        scheduledPolicy.id(),
                        scheduledPolicy.version()
                ),
                "Scheduled LP policy activation"
        );
        return true;
    }

    private boolean activateProvision(
            MandateRow mandate,
            ScheduledPolicyRow scheduledPolicy,
            LocalDate businessDate,
            LocalDateTime activatedAt
    ) {
        if (!"LIVE".equals(mandate.executionMode()) || !"PENDING".equals(mandate.status())) {
            throw new IllegalStateException(
                    "Scheduled LP provisioning target is not pending: " + scheduledPolicy.symbol()
            );
        }
        if (scheduledPolicy.version() != mandate.policyVersion()) {
            throw new IllegalStateException(
                    "Scheduled LP provisioning policy version is inconsistent: "
                            + scheduledPolicy.symbol()
            );
        }
        validatePendingProvisionReadiness(
                mandate,
                scheduledPolicy.symbol(),
                businessDate
        );
        requireNoOpenOrders(mandate);
        requireUnusedDailyState(mandate.id(), businessDate);
        activatePendingMarket(scheduledPolicy.symbol(), activatedAt);
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        update stock_liquidity_mandate
                           set status = 'ACTIVE',
                               next_quote_at = ?,
                               updated_at = ?
                         where id = ?
                           and status = 'PENDING'
                           and policy_version = ?
                        """,
                        businessDate.atTime(marketSessionService.openTime()),
                        activatedAt,
                        mandate.id(),
                        mandate.policyVersion()
                ),
                "Pending LP mandate activation"
        );
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        update stock_liquidity_transition
                           set stage = 'LIVE_ACTIVE',
                               activated_at = ?,
                               policy_version = ?,
                               updated_at = ?
                         where mandate_id = ?
                           and stage = 'PENDING_ACTIVATION'
                           and effective_business_date <= ?
                        """,
                        activatedAt,
                        scheduledPolicy.version(),
                        activatedAt,
                        mandate.id(),
                        businessDate
                ),
                "Pending LP transition activation"
        );
        activateScheduledPolicy(scheduledPolicy, activatedAt);
        return true;
    }

    private void activatePendingMarket(String symbol, LocalDateTime activatedAt) {
        MarketConfig market = jdbcClient.sql(
                        """
                        select enabled, market_status
                          from stock_order_book_market_config
                         where symbol = :symbol
                         for update
                        """
                )
                .param("symbol", symbol)
                .query((rs, rowNum) -> new MarketConfig(
                        rs.getBoolean("enabled"),
                        rs.getString("market_status")
                ))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Pending LP market configuration is missing: " + symbol
                ));
        if (market.enabled()) {
            if (!"CLOSED".equals(market.status())) {
                throw new IllegalStateException(
                        "Pending LP market must remain closed before activation: " + symbol
                );
            }
            return;
        }
        if (!"CLOSED".equals(market.status())) {
            throw new IllegalStateException(
                    "Pending LP market is not safely closed: " + symbol
            );
        }
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        update stock_order_book_market_config
                           set enabled = true,
                               updated_at = ?
                         where symbol = ?
                           and enabled = false
                           and market_status = 'CLOSED'
                        """,
                        activatedAt,
                        symbol
                ),
                "Pending LP market activation"
        );
    }

    private void updateTransition(
            long mandateId,
            long policyVersion,
            boolean resumed,
            LocalDateTime activatedAt
    ) {
        String sql = resumed
                ? """
                  update stock_liquidity_transition
                     set stage = 'LIVE_ACTIVE',
                         policy_version = ?,
                         updated_at = ?
                   where mandate_id = ?
                     and stage = 'SUSPENDED'
                  """
                : """
                  update stock_liquidity_transition
                     set policy_version = ?,
                         updated_at = ?
                   where mandate_id = ?
                  """;
        requireSingleUpdate(
                jdbcTemplate.update(
                        sql,
                        policyVersion,
                        activatedAt,
                        mandateId
                ),
                "Scheduled LP transition policy activation"
        );
    }

    private void activateScheduledPolicy(
            ScheduledPolicyRow scheduledPolicy,
            LocalDateTime activatedAt
    ) {
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        update stock_market_policy_version
                           set status = 'ACTIVE',
                               updated_at = ?
                         where id = ?
                           and status = 'SCHEDULED'
                           and version_no = ?
                        """,
                        activatedAt,
                        scheduledPolicy.id(),
                        scheduledPolicy.version()
                ),
                "Scheduled LP policy activation"
        );
    }

    private void requireUniqueDueSymbols(List<DuePolicyReference> duePolicies) {
        Set<String> symbols = new HashSet<>();
        for (DuePolicyReference duePolicy : duePolicies) {
            if (!symbols.add(duePolicy.symbol())) {
                throw new IllegalStateException(
                        "Multiple scheduled LP policies are due for one symbol: "
                                + duePolicy.symbol()
                );
            }
        }
    }

    private MandateRow lockMandate(String symbol) {
        return jdbcClient.sql(
                        """
                        select id, account_id, execution_mode, status,
                               target_inventory_quantity, policy_version
                          from stock_liquidity_mandate
                         where symbol = :symbol
                         for update
                        """
                )
                .param("symbol", symbol)
                .query((rs, rowNum) -> new MandateRow(
                        rs.getLong("id"),
                        rs.getLong("account_id"),
                        rs.getString("execution_mode"),
                        rs.getString("status"),
                        rs.getLong("target_inventory_quantity"),
                        rs.getLong("policy_version")
                ))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Scheduled LP policy mandate is missing: " + symbol
                ));
    }

    private void validatePendingProvisionReadiness(
            MandateRow mandate,
            String symbol,
            LocalDate businessDate
    ) {
        ProvisionReadiness readiness = jdbcClient.sql(
                        """
                        select participant.participant_type,
                               participant.status as participant_status,
                               participant.self_trade_group_id as participant_group,
                               account.status as account_status,
                               account.participant_category,
                               account.self_trade_group_id as account_group,
                               account.cash_balance,
                               mapping.account_role,
                               mapping.status as mapping_status,
                               mapping.effective_from,
                               mapping.effective_to,
                               holding.quantity as holding_quantity,
                               holding.reserved_quantity,
                               instrument.enabled as instrument_enabled,
                               instrument.issued_shares,
                               instrument.tradable_shares,
                               auto_config.enabled as auto_market_enabled,
                               price.current_price,
                               (
                                   select count(*)
                                     from stock_holding unmanaged
                                    where unmanaged.account_id = mandate.account_id
                                      and unmanaged.symbol <> mandate.symbol
                                      and (
                                          unmanaged.quantity > 0
                                          or unmanaged.reserved_quantity > 0
                                      )
                               ) as unmanaged_holding_count,
                               (
                                   select coalesce(sum(symbol_holding.quantity), 0)
                                     from stock_holding symbol_holding
                                    where symbol_holding.symbol = mandate.symbol
                               ) as total_holding_quantity,
                               (
                                   select count(*)
                                     from stock_holding invalid_holding
                                    where invalid_holding.symbol = mandate.symbol
                                      and (
                                          invalid_holding.quantity < 0
                                          or invalid_holding.reserved_quantity < 0
                                          or invalid_holding.reserved_quantity
                                             > invalid_holding.quantity
                                      )
                               ) as invalid_holding_count
                          from stock_liquidity_mandate mandate
                          join stock_market_participant participant
                            on participant.id = mandate.participant_id
                          join stock_account account
                            on account.id = mandate.account_id
                          join stock_market_participant_account mapping
                            on mapping.participant_id = mandate.participant_id
                           and mapping.account_id = mandate.account_id
                          join stock_holding holding
                            on holding.account_id = mandate.account_id
                           and holding.symbol = mandate.symbol
                          join stock_order_book_instrument instrument
                            on instrument.symbol = mandate.symbol
                          join stock_auto_market_config auto_config
                            on auto_config.symbol = mandate.symbol
                          join stock_price price
                            on price.symbol = mandate.symbol
                         where mandate.id = :mandateId
                           and mandate.symbol = :symbol
                         for update
                        """
                )
                .param("mandateId", mandate.id())
                .param("symbol", symbol)
                .query((rs, rowNum) -> new ProvisionReadiness(
                        rs.getString("participant_type"),
                        rs.getString("participant_status"),
                        rs.getString("participant_group"),
                        rs.getString("account_status"),
                        rs.getString("participant_category"),
                        rs.getString("account_group"),
                        rs.getBigDecimal("cash_balance"),
                        rs.getString("account_role"),
                        rs.getString("mapping_status"),
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getObject("effective_to", LocalDate.class),
                        rs.getLong("holding_quantity"),
                        rs.getLong("reserved_quantity"),
                        rs.getBoolean("instrument_enabled"),
                        rs.getLong("issued_shares"),
                        rs.getLong("tradable_shares"),
                        rs.getBoolean("auto_market_enabled"),
                        rs.getBigDecimal("current_price"),
                        rs.getLong("unmanaged_holding_count"),
                        rs.getLong("total_holding_quantity"),
                        rs.getLong("invalid_holding_count")
                ))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Pending LP role or market readiness is missing: " + symbol
                ));
        boolean effectiveRole = readiness.effectiveFrom() != null
                && !businessDate.isBefore(readiness.effectiveFrom())
                && (readiness.effectiveTo() == null
                || !businessDate.isAfter(readiness.effectiveTo()));
        if (!"LIQUIDITY_PROVIDER".equals(readiness.participantType())
                || !"ACTIVE".equals(readiness.participantStatus())
                || !"ACTIVE".equals(readiness.accountStatus())
                || !"LIQUIDITY_PROVIDER".equals(readiness.participantCategory())
                || readiness.participantGroup() == null
                || !readiness.participantGroup().equals(readiness.accountGroup())
                || !"LIQUIDITY_PROVIDER".equals(readiness.accountRole())
                || !"ACTIVE".equals(readiness.mappingStatus())
                || !effectiveRole
                || readiness.cashBalance() == null
                || readiness.cashBalance().signum() <= 0
                || readiness.holdingQuantity() < mandate.targetInventoryQuantity()
                || readiness.reservedQuantity() != 0L
                || readiness.unmanagedHoldingCount() != 0L
                || !readiness.instrumentEnabled()
                || readiness.issuedShares() <= 0L
                || readiness.tradableShares() <= 0L
                || !readiness.autoMarketEnabled()
                || readiness.currentPrice() == null
                || readiness.currentPrice().signum() <= 0
                || readiness.totalHoldingQuantity() != readiness.issuedShares()
                || readiness.invalidHoldingCount() != 0L) {
            throw new IllegalStateException(
                    "Pending LP role, inventory, or market readiness is invalid: " + symbol
            );
        }
    }

    private ScheduledPolicyRow lockScheduledPolicy(long policyId, LocalDate businessDate) {
        return jdbcClient.sql(
                        """
                        select id, scope_key, version_no, config_json
                          from stock_market_policy_version
                         where id = :policyId
                           and policy_scope = 'LIQUIDITY_MANDATE'
                           and status = 'SCHEDULED'
                           and effective_business_date <= :businessDate
                         for update
                        """
                )
                .param("policyId", policyId)
                .param("businessDate", businessDate)
                .query((rs, rowNum) -> new ScheduledPolicyRow(
                        rs.getLong("id"),
                        rs.getString("scope_key"),
                        rs.getLong("version_no"),
                        rs.getString("config_json")
                ))
                .optional()
                .orElse(null);
    }

    private void requireNoOpenOrders(MandateRow mandate) {
        Long count = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_order
                         where account_id = :accountId
                           and market_type = 'ORDER_BOOK'
                           and status in ('PENDING', 'PARTIALLY_FILLED')
                           and quantity > filled_quantity
                        """
                )
                .param("accountId", mandate.accountId())
                .query(Long.class)
                .single();
        if (count != null && count > 0L) {
            throw new IllegalStateException(
                    "Scheduled LP policy cannot activate while open orders remain: "
                            + mandate.id()
            );
        }
    }

    private void requireUnusedDailyState(long mandateId, LocalDate businessDate) {
        Boolean used = jdbcClient.sql(
                        """
                        select exists(
                            select 1
                              from stock_liquidity_daily_state
                             where mandate_id = :mandateId
                               and simulation_trade_date = :businessDate
                               and (
                                   submitted_buy_quantity > 0
                                   or submitted_sell_quantity > 0
                                   or cancelled_buy_quantity > 0
                                   or cancelled_sell_quantity > 0
                                   or executed_buy_quantity > 0
                                   or executed_sell_quantity > 0
                                   or quote_run_count > 0
                               )
                        )
                        """
                )
                .param("mandateId", mandateId)
                .param("businessDate", businessDate)
                .query(Boolean.class)
                .single();
        if (Boolean.TRUE.equals(used)) {
            throw new IllegalStateException(
                    "Scheduled LP policy cannot activate after daily usage started: "
                            + mandateId
            );
        }
    }

    private void deleteEmptyDailyState(long mandateId, LocalDate businessDate) {
        jdbcTemplate.update(
                """
                delete from stock_liquidity_daily_state
                 where mandate_id = ?
                   and simulation_trade_date = ?
                   and submitted_buy_quantity = 0
                   and submitted_sell_quantity = 0
                   and cancelled_buy_quantity = 0
                   and cancelled_sell_quantity = 0
                   and executed_buy_quantity = 0
                   and executed_sell_quantity = 0
                   and quote_run_count = 0
                """,
                mandateId,
                businessDate
        );
    }

    private PolicyConfig parsePolicy(ScheduledPolicyRow scheduledPolicy) {
        JsonNode root = parsePolicyRoot(scheduledPolicy);
        return new PolicyConfig(
                text(root, "symbol"),
                text(root, "executionMode"),
                integer(root, "targetSpreadTicks"),
                integer(root, "maxSpreadTicks"),
                longValue(root, "maxOrderQuantity"),
                longValue(root, "referenceDailyVolume"),
                decimal(root, "targetOpenParticipationRate"),
                decimal(root, "maxOpenParticipationRate"),
                decimal(root, "maxSingleOrderParticipationRate"),
                integer(root, "externalDepthLevels"),
                decimal(root, "maxExternalDepthParticipationRate"),
                decimal(root, "dailyExecutionParticipationRate"),
                decimal(root, "dailySubmissionMultiplier"),
                longValue(root, "targetInventoryQuantity"),
                longValue(root, "inventoryBandQuantity"),
                integer(root, "inventorySkewTicks"),
                decimal(root, "primaryRegimeWeight"),
                decimal(root, "liquiditySizeSensitivity"),
                integer(root, "volatilitySpreadMaxTicks"),
                integer(root, "priceRegimeMaxSkewTicks"),
                bool(root, "passiveOnly"),
                integer(root, "minimumQuoteLifetimeSeconds"),
                integer(root, "repriceThresholdTicks"),
                integer(root, "orderTtlSeconds"),
                integer(root, "quoteIntervalSeconds"),
                decimal(root, "dailyLossLimitAmount")
        );
    }

    private String activationAction(ScheduledPolicyRow scheduledPolicy) {
        JsonNode value = parsePolicyRoot(scheduledPolicy).get("activationAction");
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return "POLICY_UPDATE";
        }
        String action = value.asText();
        if (!Set.of("POLICY_UPDATE", "PROVISION", "RESUME").contains(action)) {
            throw new IllegalStateException(
                    "Scheduled LP activation action is invalid: " + action
            );
        }
        return action;
    }

    private boolean isLifecycleActivation(String activationAction) {
        return "PROVISION".equals(activationAction) || "RESUME".equals(activationAction);
    }

    private JsonNode parsePolicyRoot(ScheduledPolicyRow scheduledPolicy) {
        JsonNode root;
        try {
            root = objectMapper.readTree(scheduledPolicy.configJson());
            if (root != null && root.isTextual()) {
                root = objectMapper.readTree(root.textValue());
            }
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Scheduled LP policy JSON is invalid: " + scheduledPolicy.symbol(),
                    ex
            );
        }
        if (root == null || !root.isObject()) {
            throw new IllegalStateException(
                    "Scheduled LP policy JSON must be an object: " + scheduledPolicy.symbol()
            );
        }
        return root;
    }

    private JsonNode requiredNode(JsonNode root, String fieldName) {
        JsonNode value = root.get(fieldName);
        if (value == null || value.isNull()) {
            throw new IllegalStateException(
                    "Scheduled LP policy field is missing: " + fieldName
            );
        }
        return value;
    }

    private String text(JsonNode root, String fieldName) {
        return requiredNode(root, fieldName).textValue();
    }

    private int integer(JsonNode root, String fieldName) {
        return requiredNode(root, fieldName).intValue();
    }

    private long longValue(JsonNode root, String fieldName) {
        return requiredNode(root, fieldName).longValue();
    }

    private BigDecimal decimal(JsonNode root, String fieldName) {
        return requiredNode(root, fieldName).decimalValue();
    }

    private boolean bool(JsonNode root, String fieldName) {
        return requiredNode(root, fieldName).booleanValue();
    }

    private void requireSingleUpdate(int count, String operation) {
        if (count != 1) {
            throw new IllegalStateException(
                    operation + " count mismatch: expected=1, actual=" + count
            );
        }
    }

    private record ScheduledPolicyRow(
            long id,
            String symbol,
            long version,
            String configJson
    ) {
    }

    private record DuePolicyReference(
            long id,
            String symbol
    ) {
    }

    private record MandateRow(
            long id,
            long accountId,
            String executionMode,
            String status,
            long targetInventoryQuantity,
            long policyVersion
    ) {
    }

    private record MarketConfig(boolean enabled, String status) {
    }

    private record ProvisionReadiness(
            String participantType,
            String participantStatus,
            String participantGroup,
            String accountStatus,
            String participantCategory,
            String accountGroup,
            BigDecimal cashBalance,
            String accountRole,
            String mappingStatus,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            long holdingQuantity,
            long reservedQuantity,
            boolean instrumentEnabled,
            long issuedShares,
            long tradableShares,
            boolean autoMarketEnabled,
            BigDecimal currentPrice,
            long unmanagedHoldingCount,
            long totalHoldingQuantity,
            long invalidHoldingCount
    ) {
    }

    private record PolicyConfig(
            String symbol,
            String executionMode,
            int targetSpreadTicks,
            int maxSpreadTicks,
            long maxOrderQuantity,
            long referenceDailyVolume,
            BigDecimal targetOpenParticipationRate,
            BigDecimal maxOpenParticipationRate,
            BigDecimal maxSingleOrderParticipationRate,
            int externalDepthLevels,
            BigDecimal maxExternalDepthParticipationRate,
            BigDecimal dailyExecutionParticipationRate,
            BigDecimal dailySubmissionMultiplier,
            long targetInventoryQuantity,
            long inventoryBandQuantity,
            int inventorySkewTicks,
            BigDecimal primaryRegimeWeight,
            BigDecimal liquiditySizeSensitivity,
            int volatilitySpreadMaxTicks,
            int priceRegimeMaxSkewTicks,
            boolean passiveOnly,
            int minimumQuoteLifetimeSeconds,
            int repriceThresholdTicks,
            int orderTtlSeconds,
            int quoteIntervalSeconds,
            BigDecimal dailyLossLimitAmount
    ) {
    }
}
