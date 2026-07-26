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
        if (businessDate == null || activatedAt == null) {
            throw new IllegalArgumentException(
                    "LP policy activation requires businessDate and activatedAt"
            );
        }
        Integer activated = transactionTemplate.execute(ignored ->
                activateDuePoliciesInTransaction(businessDate, activatedAt)
        );
        return activated == null ? 0 : activated;
    }

    private int activateDuePoliciesInTransaction(
            LocalDate businessDate,
            LocalDateTime activatedAt
    ) {
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
            if (activatePolicy(duePolicy, businessDate, activatedAt)) {
                activatedCount = Math.addExact(activatedCount, 1);
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
            LocalDateTime activatedAt
    ) {
        MandateRow mandate = lockMandate(duePolicy.symbol());
        ScheduledPolicyRow scheduledPolicy = lockScheduledPolicy(
                duePolicy.id(),
                businessDate
        );
        if (scheduledPolicy == null) {
            return false;
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

        LocalDateTime nextQuoteAt = "ACTIVE".equals(mandate.status())
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
                        nextQuoteAt,
                        scheduledPolicy.version(),
                        activatedAt,
                        mandate.id(),
                        mandate.policyVersion()
                ),
                "Scheduled LP mandate policy activation"
        );
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        update stock_liquidity_transition
                           set policy_version = ?,
                               updated_at = ?
                         where mandate_id = ?
                        """,
                        scheduledPolicy.version(),
                        activatedAt,
                        mandate.id()
                ),
                "Scheduled LP transition policy activation"
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
                        select id, account_id, execution_mode, status, policy_version
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
                        rs.getLong("policy_version")
                ))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Scheduled LP policy mandate is missing: " + symbol
                ));
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
            long policyVersion
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
