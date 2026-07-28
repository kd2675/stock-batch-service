package stock.batch.service.automarket.biz;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
public class InstitutionScheduledPolicyActivationService {

    private static final int MAX_POLICIES_PER_ACTIVATION = 100;
    private static final int MAX_MANDATES_PER_PORTFOLIO = 50;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal MAX_DAILY_PARTICIPATION_RATE =
            new BigDecimal("0.200000");
    private static final BigDecimal WEIGHT_SUM_TOLERANCE = new BigDecimal("0.000100");
    private static final BigDecimal LIQUIDATION_BASE_WEIGHT = new BigDecimal("0.000001");
    private static final Set<String> INVESTMENT_STYLES = Set.of(
            "BALANCED_LONG_TERM",
            "VALUE_CONTRARIAN",
            "MOMENTUM",
            "ACTIVE_SHORT_TERM"
    );

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final SimulationMarketSessionService marketSessionService;
    private final TransactionTemplate transactionTemplate;

    public InstitutionScheduledPolicyActivationService(
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
                    "Institution policy activation requires businessDate and activatedAt"
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
                         where policy_scope = 'INSTITUTIONAL_PORTFOLIO'
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
                    "Too many scheduled institution policies are due: " + duePolicies.size()
            );
        }
        requireUniquePortfolios(duePolicies);
        int activatedCount = 0;
        for (DuePolicyReference duePolicy : duePolicies) {
            if (activatePolicy(duePolicy, businessDate, activatedAt)) {
                activatedCount = Math.addExact(activatedCount, 1);
            }
        }
        if (activatedCount > 0) {
            log.info(
                    "Scheduled institution policies activated: businessDate={}, count={}",
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
        PortfolioRow portfolio = lockPortfolio(duePolicy.portfolioCode());
        ScheduledPolicyRow scheduled = lockScheduledPolicy(duePolicy.id(), businessDate);
        if (scheduled == null) {
            return false;
        }
        if (!"LIVE".equals(portfolio.executionMode())
                || (!"ACTIVE".equals(portfolio.status())
                && !"SUSPENDED".equals(portfolio.status()))) {
            throw new IllegalStateException(
                    "Scheduled institution policy target is not LIVE: "
                            + scheduled.portfolioCode()
            );
        }
        if (scheduled.version() == portfolio.policyVersion()) {
            activateInitialPolicy(portfolio, scheduled, activatedAt);
            return true;
        }
        long expectedVersion = Math.addExact(portfolio.policyVersion(), 1L);
        if (scheduled.version() != expectedVersion) {
            throw new IllegalStateException(
                    (
                            "Scheduled institution policy version is not sequential: "
                                    + "portfolio=%s, current=%d, scheduled=%d"
                    )
                            .formatted(
                                    scheduled.portfolioCode(),
                                    portfolio.policyVersion(),
                                    scheduled.version()
                            )
            );
        }

        PolicyConfig policy = parsePolicy(scheduled);
        validatePolicyIdentity(portfolio, policy);
        validatePolicyValues(portfolio, policy);
        requireNoPendingOrderIntents(portfolio);
        requireNoOpenOrders(portfolio);
        requireUnusedDailyState(portfolio, businessDate);
        requireActiveMarketSymbols(policy);
        deleteEmptyDailyState(portfolio.portfolioId(), businessDate);

        LocalDateTime nextDecisionAt = "ACTIVE".equals(portfolio.status())
                ? businessDate.atTime(marketSessionService.openTime())
                : null;
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        update stock_institution_portfolio
                           set display_name = ?,
                               investment_style = ?,
                               base_stock_allocation_rate = ?,
                               min_stock_allocation_rate = ?,
                               max_stock_allocation_rate = ?,
                               primary_regime_weight = ?,
                               asset_preference_sensitivity = ?,
                               volatility_sensitivity = ?,
                               entry_threshold_rate = ?,
                               exit_threshold_rate = ?,
                               daily_turnover_limit_rate = ?,
                               max_decision_turnover_rate = ?,
                               decision_interval_minutes = ?,
                               next_decision_at = ?,
                               policy_version = ?,
                               updated_at = ?
                         where id = ?
                           and policy_version = ?
                        """,
                        policy.displayName(),
                        policy.investmentStyle(),
                        policy.baseStockAllocationRate(),
                        policy.minStockAllocationRate(),
                        policy.maxStockAllocationRate(),
                        policy.primaryRegimeWeight(),
                        policy.assetPreferenceSensitivity(),
                        policy.volatilitySensitivity(),
                        policy.entryThresholdRate(),
                        policy.exitThresholdRate(),
                        policy.dailyTurnoverLimitRate(),
                        policy.maxDecisionTurnoverRate(),
                        policy.decisionIntervalMinutes(),
                        nextDecisionAt,
                        scheduled.version(),
                        activatedAt,
                        portfolio.portfolioId(),
                        portfolio.policyVersion()
                ),
                "Scheduled institution portfolio policy activation"
        );
        requireSingleUpdate(
                jdbcTemplate.update(
                        """
                        update stock_market_participant
                           set display_name = ?,
                               updated_at = ?
                         where id = ?
                        """,
                        policy.displayName(),
                        activatedAt,
                        portfolio.participantId()
                ),
                "Scheduled institution participant display-name activation"
        );
        applyMandates(portfolio, policy, activatedAt);
        retireActivePolicy(scheduled.portfolioCode(), scheduled.version(), activatedAt);
        activateScheduledPolicy(scheduled, activatedAt);
        return true;
    }

    private void activateInitialPolicy(
            PortfolioRow portfolio,
            ScheduledPolicyRow scheduled,
            LocalDateTime activatedAt
    ) {
        retireActivePolicy(scheduled.portfolioCode(), scheduled.version(), activatedAt);
        activateScheduledPolicy(scheduled, activatedAt);
        jdbcTemplate.update(
                """
                update stock_institution_portfolio
                   set updated_at = ?
                 where id = ?
                   and policy_version = ?
                """,
                activatedAt,
                portfolio.portfolioId(),
                portfolio.policyVersion()
        );
    }

    private void applyMandates(
            PortfolioRow portfolio,
            PolicyConfig policy,
            LocalDateTime activatedAt
    ) {
        Set<String> desiredSymbols = new HashSet<>();
        for (SymbolPolicyConfig mandate : policy.mandates()) {
            if (!desiredSymbols.add(mandate.symbol())) {
                throw new IllegalStateException(
                        "Scheduled institution policy contains a duplicate symbol: "
                                + mandate.symbol()
                );
            }
            jdbcTemplate.update(
                    """
                    insert into stock_institution_symbol_mandate(
                        portfolio_id, symbol, base_symbol_weight,
                        min_portfolio_allocation_rate, max_portfolio_allocation_rate,
                        price_pressure_sensitivity, momentum_sensitivity,
                        value_sensitivity, report_sensitivity,
                        reference_daily_volume, daily_participation_rate,
                        enabled, created_at, updated_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, ?, ?)
                    on duplicate key update
                        base_symbol_weight = values(base_symbol_weight),
                        min_portfolio_allocation_rate =
                            values(min_portfolio_allocation_rate),
                        max_portfolio_allocation_rate =
                            values(max_portfolio_allocation_rate),
                        price_pressure_sensitivity =
                            values(price_pressure_sensitivity),
                        momentum_sensitivity = values(momentum_sensitivity),
                        value_sensitivity = values(value_sensitivity),
                        report_sensitivity = values(report_sensitivity),
                        reference_daily_volume = values(reference_daily_volume),
                        daily_participation_rate = values(daily_participation_rate),
                        enabled = true,
                        updated_at = values(updated_at)
                    """,
                    portfolio.portfolioId(),
                    mandate.symbol(),
                    mandate.baseSymbolWeight(),
                    mandate.minPortfolioAllocationRate(),
                    mandate.maxPortfolioAllocationRate(),
                    mandate.pricePressureSensitivity(),
                    mandate.momentumSensitivity(),
                    mandate.valueSensitivity(),
                    mandate.reportSensitivity(),
                    mandate.referenceDailyVolume(),
                    mandate.dailyParticipationRate(),
                    activatedAt,
                    activatedAt
            );
        }

        List<ExistingMandate> removed = jdbcClient.sql(
                        """
                        select mandate.id, mandate.symbol,
                               coalesce(holding.quantity, 0) as holding_quantity
                          from stock_institution_symbol_mandate mandate
                          left join stock_holding holding
                            on holding.account_id = :accountId
                           and holding.symbol = mandate.symbol
                         where mandate.portfolio_id = :portfolioId
                         order by mandate.symbol asc
                        """
                )
                .param("accountId", portfolio.accountId())
                .param("portfolioId", portfolio.portfolioId())
                .query((rs, rowNum) -> new ExistingMandate(
                        rs.getLong("id"),
                        rs.getString("symbol"),
                        rs.getLong("holding_quantity")
                ))
                .list()
                .stream()
                .filter(mandate -> !desiredSymbols.contains(mandate.symbol()))
                .toList();
        for (ExistingMandate mandate : removed) {
            if (mandate.holdingQuantity() > 0L) {
                requireSingleUpdate(
                        jdbcTemplate.update(
                                """
                                update stock_institution_symbol_mandate
                                   set base_symbol_weight = ?,
                                       min_portfolio_allocation_rate = 0,
                                       max_portfolio_allocation_rate = 0,
                                       price_pressure_sensitivity = 0,
                                       momentum_sensitivity = 0,
                                       value_sensitivity = 0,
                                       report_sensitivity = 0,
                                       enabled = true,
                                       updated_at = ?
                                 where id = ?
                                """,
                                LIQUIDATION_BASE_WEIGHT,
                                activatedAt,
                                mandate.mandateId()
                        ),
                        "Institution liquidation-only mandate activation"
                );
            } else {
                requireSingleUpdate(
                        jdbcTemplate.update(
                                """
                                update stock_institution_symbol_mandate
                                   set enabled = false,
                                       updated_at = ?
                                 where id = ?
                                """,
                                activatedAt,
                                mandate.mandateId()
                        ),
                        "Institution unused mandate retirement"
                );
            }
        }
    }

    private PortfolioRow lockPortfolio(String portfolioCode) {
        return jdbcClient.sql(
                        """
                        select id, participant_id, account_id, portfolio_code,
                               execution_mode, status, policy_version
                          from stock_institution_portfolio
                         where portfolio_code = :portfolioCode
                         for update
                        """
                )
                .param("portfolioCode", portfolioCode)
                .query((rs, rowNum) -> new PortfolioRow(
                        rs.getLong("id"),
                        rs.getLong("participant_id"),
                        rs.getLong("account_id"),
                        rs.getString("portfolio_code"),
                        rs.getString("execution_mode"),
                        rs.getString("status"),
                        rs.getLong("policy_version")
                ))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Scheduled institution portfolio is missing: " + portfolioCode
                ));
    }

    private ScheduledPolicyRow lockScheduledPolicy(long policyId, LocalDate businessDate) {
        return jdbcClient.sql(
                        """
                        select id, scope_key, version_no, config_json
                          from stock_market_policy_version
                         where id = :policyId
                           and policy_scope = 'INSTITUTIONAL_PORTFOLIO'
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

    private void requireNoPendingOrderIntents(PortfolioRow portfolio) {
        Long count = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_institution_order_intent
                         where portfolio_id = :portfolioId
                           and status = 'PENDING'
                        """
                )
                .param("portfolioId", portfolio.portfolioId())
                .query(Long.class)
                .single();
        if (count != null && count > 0L) {
            throw new IllegalStateException(
                    "Scheduled institution policy cannot activate while pending intents remain: "
                            + portfolio.portfolioCode()
            );
        }
    }

    private void requireNoOpenOrders(PortfolioRow portfolio) {
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
                .param("accountId", portfolio.accountId())
                .query(Long.class)
                .single();
        if (count != null && count > 0L) {
            throw new IllegalStateException(
                    "Scheduled institution policy cannot activate while open orders remain: "
                            + portfolio.portfolioCode()
            );
        }
    }

    private void requireUnusedDailyState(PortfolioRow portfolio, LocalDate businessDate) {
        Boolean usedBudget = jdbcClient.sql(
                        """
                        select exists(
                            select 1
                              from stock_institution_daily_budget
                             where portfolio_id = :portfolioId
                               and simulation_trade_date = :businessDate
                               and (
                                   planned_buy_quantity > 0
                                   or planned_sell_quantity > 0
                                   or planned_buy_amount > 0
                                   or planned_sell_amount > 0
                                   or submitted_buy_amount > 0
                                   or submitted_sell_amount > 0
                               )
                        )
                        """
                )
                .param("portfolioId", portfolio.portfolioId())
                .param("businessDate", businessDate)
                .query(Boolean.class)
                .single();
        Boolean decisionStarted = jdbcClient.sql(
                        """
                        select exists(
                            select 1
                              from stock_institution_decision_run
                             where portfolio_id = :portfolioId
                               and simulation_trade_date = :businessDate
                        )
                        """
                )
                .param("portfolioId", portfolio.portfolioId())
                .param("businessDate", businessDate)
                .query(Boolean.class)
                .single();
        if (Boolean.TRUE.equals(usedBudget) || Boolean.TRUE.equals(decisionStarted)) {
            throw new IllegalStateException(
                    "Scheduled institution policy cannot activate after daily activity started: "
                            + portfolio.portfolioCode()
            );
        }
    }

    private void deleteEmptyDailyState(long portfolioId, LocalDate businessDate) {
        jdbcTemplate.update(
                """
                delete from stock_institution_daily_budget
                 where portfolio_id = ?
                   and simulation_trade_date = ?
                   and planned_buy_quantity = 0
                   and planned_sell_quantity = 0
                   and planned_buy_amount = 0
                   and planned_sell_amount = 0
                   and submitted_buy_amount = 0
                   and submitted_sell_amount = 0
                """,
                portfolioId,
                businessDate
        );
    }

    private void requireActiveMarketSymbols(PolicyConfig policy) {
        List<String> activeSymbols = jdbcClient.sql(
                        """
                        select instrument.symbol
                          from stock_order_book_instrument instrument
                          join stock_order_book_market_config market
                            on market.symbol = instrument.symbol
                           and market.enabled = true
                           and market.market_status in ('OPEN', 'CLOSED')
                          join stock_price price
                            on price.symbol = instrument.symbol
                           and price.current_price > 0
                         where instrument.enabled = true
                           and instrument.tradable_shares > 0
                           and instrument.symbol in (:symbols)
                         order by instrument.symbol asc
                        """
                )
                .param("symbols", policy.mandates().stream()
                        .map(SymbolPolicyConfig::symbol)
                        .toList())
                .query(String.class)
                .list();
        Set<String> active = Set.copyOf(activeSymbols);
        List<String> missing = policy.mandates().stream()
                .map(SymbolPolicyConfig::symbol)
                .filter(symbol -> !active.contains(symbol))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Scheduled institution policy contains inactive market symbols: "
                            + String.join(",", missing)
            );
        }
    }

    private void validatePolicyIdentity(PortfolioRow portfolio, PolicyConfig policy) {
        if (!portfolio.portfolioCode().equals(policy.portfolioCode())
                || !"LIVE".equals(policy.executionMode())
                || policy.mandates().isEmpty()) {
            throw new IllegalStateException(
                    "Scheduled institution policy identity or mandate set is invalid: "
                            + portfolio.portfolioCode()
            );
        }
    }

    private void validatePolicyValues(PortfolioRow portfolio, PolicyConfig policy) {
        boolean validPortfolio = policy.displayName().length() <= 120
                && INVESTMENT_STYLES.contains(policy.investmentStyle())
                && rate(policy.baseStockAllocationRate(), true)
                && rate(policy.minStockAllocationRate(), false)
                && rate(policy.maxStockAllocationRate(), true)
                && policy.minStockAllocationRate()
                .compareTo(policy.baseStockAllocationRate()) <= 0
                && policy.baseStockAllocationRate()
                .compareTo(policy.maxStockAllocationRate()) <= 0
                && rate(policy.primaryRegimeWeight(), false)
                && rate(policy.assetPreferenceSensitivity(), false)
                && rate(policy.volatilitySensitivity(), false)
                && rate(policy.entryThresholdRate(), false)
                && rate(policy.exitThresholdRate(), false)
                && policy.exitThresholdRate().compareTo(policy.entryThresholdRate()) <= 0
                && rate(policy.dailyTurnoverLimitRate(), true)
                && rate(policy.maxDecisionTurnoverRate(), true)
                && policy.maxDecisionTurnoverRate()
                .compareTo(policy.dailyTurnoverLimitRate()) <= 0
                && policy.decisionIntervalMinutes() >= 5
                && policy.decisionIntervalMinutes() <= 1_440
                && policy.mandates().size() <= MAX_MANDATES_PER_PORTFOLIO;
        if (!validPortfolio) {
            throw new IllegalStateException(
                    "Scheduled institution portfolio values are outside the policy contract: "
                            + portfolio.portfolioCode()
            );
        }

        BigDecimal baseWeightSum = ZERO;
        BigDecimal minimumAllocationSum = ZERO;
        BigDecimal maximumAllocationSum = ZERO;
        Set<String> symbols = new HashSet<>();
        for (SymbolPolicyConfig mandate : policy.mandates()) {
            boolean validMandate = !mandate.symbol().isBlank()
                    && symbols.add(mandate.symbol())
                    && rate(mandate.baseSymbolWeight(), true)
                    && rate(mandate.minPortfolioAllocationRate(), false)
                    && rate(mandate.maxPortfolioAllocationRate(), true)
                    && mandate.minPortfolioAllocationRate()
                    .compareTo(mandate.maxPortfolioAllocationRate()) <= 0
                    && signedRate(mandate.pricePressureSensitivity())
                    && signedRate(mandate.momentumSensitivity())
                    && signedRate(mandate.valueSensitivity())
                    && signedRate(mandate.reportSensitivity())
                    && mandate.referenceDailyVolume() > 0L
                    && mandate.dailyParticipationRate().signum() > 0
                    && mandate.dailyParticipationRate()
                    .compareTo(MAX_DAILY_PARTICIPATION_RATE) <= 0;
            if (!validMandate) {
                throw new IllegalStateException(
                        "Scheduled institution symbol values are outside the policy contract: "
                                + portfolio.portfolioCode() + ":" + mandate.symbol()
                );
            }
            baseWeightSum = baseWeightSum.add(mandate.baseSymbolWeight());
            minimumAllocationSum =
                    minimumAllocationSum.add(mandate.minPortfolioAllocationRate());
            maximumAllocationSum =
                    maximumAllocationSum.add(mandate.maxPortfolioAllocationRate());
        }
        if (baseWeightSum.subtract(ONE).abs().compareTo(WEIGHT_SUM_TOLERANCE) > 0
                || minimumAllocationSum.compareTo(policy.minStockAllocationRate()) > 0
                || maximumAllocationSum.compareTo(policy.maxStockAllocationRate()) < 0) {
            throw new IllegalStateException(
                    "Scheduled institution symbol weights cannot satisfy the portfolio band: "
                            + portfolio.portfolioCode()
            );
        }
    }

    private boolean rate(BigDecimal value, boolean positive) {
        return value != null
                && (positive ? value.signum() > 0 : value.signum() >= 0)
                && value.compareTo(ONE) <= 0;
    }

    private boolean signedRate(BigDecimal value) {
        return value != null
                && value.compareTo(ONE.negate()) >= 0
                && value.compareTo(ONE) <= 0;
    }

    private PolicyConfig parsePolicy(ScheduledPolicyRow scheduled) {
        JsonNode root;
        try {
            root = objectMapper.readTree(scheduled.configJson());
            if (root != null && root.isTextual()) {
                root = objectMapper.readTree(root.textValue());
            }
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Scheduled institution policy JSON is invalid: "
                            + scheduled.portfolioCode(),
                    ex
            );
        }
        if (root == null || !root.isObject()
                || !"INDEPENDENT_INSTITUTION_PORTFOLIO_V2".equals(text(root, "preset"))) {
            throw new IllegalStateException(
                    "Scheduled institution update policy must use V2: "
                            + scheduled.portfolioCode()
            );
        }
        JsonNode mandateNodes = requiredNode(root, "mandates");
        if (!mandateNodes.isArray() || mandateNodes.isEmpty()) {
            throw new IllegalStateException(
                    "Scheduled institution policy mandates must be a non-empty array: "
                            + scheduled.portfolioCode()
            );
        }
        List<SymbolPolicyConfig> mandates = new ArrayList<>();
        for (JsonNode mandate : mandateNodes) {
            mandates.add(new SymbolPolicyConfig(
                    text(mandate, "symbol"),
                    decimal(mandate, "baseSymbolWeight"),
                    decimal(mandate, "minPortfolioAllocationRate"),
                    decimal(mandate, "maxPortfolioAllocationRate"),
                    decimal(mandate, "pricePressureSensitivity"),
                    decimal(mandate, "momentumSensitivity"),
                    decimal(mandate, "valueSensitivity"),
                    decimal(mandate, "reportSensitivity"),
                    longValue(mandate, "referenceDailyVolume"),
                    decimal(mandate, "dailyParticipationRate")
            ));
        }
        return new PolicyConfig(
                text(root, "portfolioCode"),
                text(root, "executionMode"),
                text(root, "displayName"),
                text(root, "investmentStyle"),
                decimal(root, "baseStockAllocationRate"),
                decimal(root, "minStockAllocationRate"),
                decimal(root, "maxStockAllocationRate"),
                decimal(root, "primaryRegimeWeight"),
                decimal(root, "assetPreferenceSensitivity"),
                decimal(root, "volatilitySensitivity"),
                decimal(root, "entryThresholdRate"),
                decimal(root, "exitThresholdRate"),
                decimal(root, "dailyTurnoverLimitRate"),
                decimal(root, "maxDecisionTurnoverRate"),
                integer(root, "decisionIntervalMinutes"),
                List.copyOf(mandates)
        );
    }

    private JsonNode requiredNode(JsonNode root, String fieldName) {
        JsonNode value = root.get(fieldName);
        if (value == null || value.isNull()) {
            throw new IllegalStateException(
                    "Scheduled institution policy field is missing: " + fieldName
            );
        }
        return value;
    }

    private String text(JsonNode root, String fieldName) {
        JsonNode value = requiredNode(root, fieldName);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException(
                    "Scheduled institution policy field must be non-blank text: " + fieldName
            );
        }
        return value.textValue();
    }

    private BigDecimal decimal(JsonNode root, String fieldName) {
        JsonNode value = requiredNode(root, fieldName);
        if (!value.isNumber()) {
            throw new IllegalStateException(
                    "Scheduled institution policy field must be numeric: " + fieldName
            );
        }
        return value.decimalValue();
    }

    private long longValue(JsonNode root, String fieldName) {
        JsonNode value = requiredNode(root, fieldName);
        if (!value.canConvertToLong()) {
            throw new IllegalStateException(
                    "Scheduled institution policy field must be a long: " + fieldName
            );
        }
        return value.longValue();
    }

    private int integer(JsonNode root, String fieldName) {
        JsonNode value = requiredNode(root, fieldName);
        if (!value.canConvertToInt()) {
            throw new IllegalStateException(
                    "Scheduled institution policy field must be an integer: " + fieldName
            );
        }
        return value.intValue();
    }

    private void retireActivePolicy(
            String portfolioCode,
            long activatedVersion,
            LocalDateTime activatedAt
    ) {
        jdbcTemplate.update(
                """
                update stock_market_policy_version
                   set status = 'RETIRED',
                       updated_at = ?
                 where policy_scope = 'INSTITUTIONAL_PORTFOLIO'
                   and scope_key = ?
                   and status = 'ACTIVE'
                   and version_no < ?
                """,
                activatedAt,
                portfolioCode,
                activatedVersion
        );
    }

    private void activateScheduledPolicy(
            ScheduledPolicyRow scheduled,
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
                        scheduled.id(),
                        scheduled.version()
                ),
                "Scheduled institution policy activation"
        );
    }

    private void requireUniquePortfolios(List<DuePolicyReference> policies) {
        Set<String> portfolioCodes = new HashSet<>();
        for (DuePolicyReference policy : policies) {
            if (!portfolioCodes.add(policy.portfolioCode())) {
                throw new IllegalStateException(
                        "Multiple scheduled institution policies are due for one portfolio: "
                                + policy.portfolioCode()
                );
            }
        }
    }

    private void requireSingleUpdate(int updated, String operation) {
        if (updated != 1) {
            throw new IllegalStateException(operation + " must update exactly one row");
        }
    }

    private record DuePolicyReference(long id, String portfolioCode) {
    }

    private record PortfolioRow(
            long portfolioId,
            long participantId,
            long accountId,
            String portfolioCode,
            String executionMode,
            String status,
            long policyVersion
    ) {
    }

    private record ScheduledPolicyRow(
            long id,
            String portfolioCode,
            long version,
            String configJson
    ) {
    }

    private record ExistingMandate(
            long mandateId,
            String symbol,
            long holdingQuantity
    ) {
    }

    private record PolicyConfig(
            String portfolioCode,
            String executionMode,
            String displayName,
            String investmentStyle,
            BigDecimal baseStockAllocationRate,
            BigDecimal minStockAllocationRate,
            BigDecimal maxStockAllocationRate,
            BigDecimal primaryRegimeWeight,
            BigDecimal assetPreferenceSensitivity,
            BigDecimal volatilitySensitivity,
            BigDecimal entryThresholdRate,
            BigDecimal exitThresholdRate,
            BigDecimal dailyTurnoverLimitRate,
            BigDecimal maxDecisionTurnoverRate,
            int decisionIntervalMinutes,
            List<SymbolPolicyConfig> mandates
    ) {
    }

    private record SymbolPolicyConfig(
            String symbol,
            BigDecimal baseSymbolWeight,
            BigDecimal minPortfolioAllocationRate,
            BigDecimal maxPortfolioAllocationRate,
            BigDecimal pricePressureSensitivity,
            BigDecimal momentumSensitivity,
            BigDecimal valueSensitivity,
            BigDecimal reportSensitivity,
            long referenceDailyVolume,
            BigDecimal dailyParticipationRate
    ) {
    }
}
