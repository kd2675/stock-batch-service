package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

@Component
class InstitutionPortfolioRepository {

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;

    InstitutionPortfolioRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate));
    }

    List<Long> findDuePortfolioIds(LocalDateTime simulationDateTime, int limit) {
        return jdbcClient.sql(
                        """
                        select id
                         from stock_institution_portfolio
                         where status = 'ACTIVE'
                           and execution_mode = 'LIVE'
                           and (next_decision_at is null or next_decision_at <= :simulationDateTime)
                           and not exists (
                               select 1
                                 from stock_institution_order_intent intent
                                where intent.portfolio_id = stock_institution_portfolio.id
                                  and intent.status = 'PENDING'
                           )
                         order by coalesce(next_decision_at, created_at) asc, id asc
                         limit :limit
                        """
                )
                .param("simulationDateTime", simulationDateTime)
                .param("limit", Math.clamp(limit, 1, 100))
                .query(Long.class)
                .list();
    }

    Optional<InstitutionPortfolioPolicy> lockDuePortfolio(
            long portfolioId,
            LocalDateTime simulationDateTime
    ) {
        return jdbcClient.sql(
                        """
                        select id,
                               participant_id,
                               account_id,
                               portfolio_code,
                               display_name,
                               investment_style,
                               execution_mode,
                               base_stock_allocation_rate,
                               min_stock_allocation_rate,
                               max_stock_allocation_rate,
                               primary_regime_weight,
                               asset_preference_sensitivity,
                               volatility_sensitivity,
                               entry_threshold_rate,
                               exit_threshold_rate,
                               daily_turnover_limit_rate,
                               max_decision_turnover_rate,
                               decision_interval_minutes,
                               policy_version
                          from stock_institution_portfolio
                         where id = :portfolioId
                           and status = 'ACTIVE'
                           and execution_mode = 'LIVE'
                           and (next_decision_at is null or next_decision_at <= :simulationDateTime)
                           and not exists (
                               select 1
                                 from stock_institution_order_intent intent
                                where intent.portfolio_id = stock_institution_portfolio.id
                                  and intent.status = 'PENDING'
                           )
                         for update
                        """
                )
                .param("portfolioId", portfolioId)
                .param("simulationDateTime", simulationDateTime)
                .query((rs, rowNum) -> new InstitutionPortfolioPolicy(
                        rs.getLong("id"),
                        rs.getLong("participant_id"),
                        rs.getLong("account_id"),
                        rs.getString("portfolio_code"),
                        rs.getString("display_name"),
                        rs.getString("investment_style"),
                        rs.getString("execution_mode"),
                        rs.getBigDecimal("base_stock_allocation_rate"),
                        rs.getBigDecimal("min_stock_allocation_rate"),
                        rs.getBigDecimal("max_stock_allocation_rate"),
                        rs.getBigDecimal("primary_regime_weight"),
                        rs.getBigDecimal("asset_preference_sensitivity"),
                        rs.getBigDecimal("volatility_sensitivity"),
                        rs.getBigDecimal("entry_threshold_rate"),
                        rs.getBigDecimal("exit_threshold_rate"),
                        rs.getBigDecimal("daily_turnover_limit_rate"),
                        rs.getBigDecimal("max_decision_turnover_rate"),
                        rs.getInt("decision_interval_minutes"),
                        rs.getLong("policy_version")
                ))
                .optional();
    }

    void activateEffectivePolicyVersion(
            InstitutionPortfolioPolicy policy,
            LocalDate simulationTradeDate,
            LocalDateTime updatedAt
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
                updatedAt,
                policy.portfolioCode(),
                policy.policyVersion()
        );
        jdbcTemplate.update(
                """
                update stock_market_policy_version
                   set status = 'ACTIVE',
                       updated_at = ?
                 where policy_scope = 'INSTITUTIONAL_PORTFOLIO'
                   and scope_key = ?
                   and version_no = ?
                   and effective_business_date <= ?
                   and status = 'SCHEDULED'
                """,
                updatedAt,
                policy.portfolioCode(),
                policy.policyVersion(),
                simulationTradeDate
        );
        Integer activePolicyCount = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_market_policy_version
                         where policy_scope = 'INSTITUTIONAL_PORTFOLIO'
                           and scope_key = :scopeKey
                           and version_no = :policyVersion
                           and effective_business_date <= :simulationTradeDate
                           and status = 'ACTIVE'
                        """
                )
                .param("scopeKey", policy.portfolioCode())
                .param("policyVersion", policy.policyVersion())
                .param("simulationTradeDate", simulationTradeDate)
                .query(Integer.class)
                .single();
        if (activePolicyCount == null || activePolicyCount != 1) {
            throw new IllegalStateException(
                    "Institution portfolio requires exactly one effective active policy version: "
                            + policy.portfolioCode() + ":" + policy.policyVersion()
            );
        }
    }

    List<InstitutionSymbolMandate> findEnabledMandates(long portfolioId) {
        return jdbcClient.sql(
                        """
                        select symbol,
                               base_symbol_weight,
                               min_portfolio_allocation_rate,
                               max_portfolio_allocation_rate,
                               price_pressure_sensitivity,
                               momentum_sensitivity,
                               value_sensitivity,
                               report_sensitivity,
                               reference_daily_volume,
                               daily_participation_rate
                          from stock_institution_symbol_mandate
                         where portfolio_id = :portfolioId
                           and enabled = true
                         order by symbol asc
                        """
                )
                .param("portfolioId", portfolioId)
                .query((rs, rowNum) -> new InstitutionSymbolMandate(
                        rs.getString("symbol"),
                        rs.getBigDecimal("base_symbol_weight"),
                        rs.getBigDecimal("min_portfolio_allocation_rate"),
                        rs.getBigDecimal("max_portfolio_allocation_rate"),
                        rs.getBigDecimal("price_pressure_sensitivity"),
                        rs.getBigDecimal("momentum_sensitivity"),
                        rs.getBigDecimal("value_sensitivity"),
                        rs.getBigDecimal("report_sensitivity"),
                        rs.getLong("reference_daily_volume"),
                        rs.getBigDecimal("daily_participation_rate")
                ))
                .list();
    }

    InstitutionAccountSnapshot lockAndLoadAccountSnapshot(
            InstitutionPortfolioPolicy policy,
            List<String> symbols,
            LocalDate simulationTradeDate
    ) {
        AccountRow account = jdbcClient.sql(
                        """
                        select id, status, participant_category, self_trade_group_id, cash_balance
                          from stock_account
                         where id = :accountId
                         for update
                        """
                )
                .param("accountId", policy.accountId())
                .query((rs, rowNum) -> new AccountRow(
                        rs.getLong("id"),
                        rs.getString("status"),
                        rs.getString("participant_category"),
                        rs.getString("self_trade_group_id"),
                        rs.getBigDecimal("cash_balance")
                ))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Institution portfolio account does not exist: " + policy.accountId()
                ));
        validateAccount(account, policy, simulationTradeDate);

        HoldingValueRow holdingValue = jdbcClient.sql(
                        """
                        select coalesce(sum(
                                   case
                                     when price.current_price > 0
                                     then holding.quantity * price.current_price
                                     else 0
                                   end
                               ), 0) as total_holding_value,
                               coalesce(sum(
                                   case
                                     when holding.quantity > 0
                                      and (price.current_price is null or price.current_price <= 0)
                                     then 1
                                     else 0
                                   end
                               ), 0) as missing_price_count
                          from stock_holding holding
                          left join stock_price price on price.symbol = holding.symbol
                         where holding.account_id = :accountId
                        """
                )
                .param("accountId", policy.accountId())
                .query((rs, rowNum) -> new HoldingValueRow(
                        rs.getBigDecimal("total_holding_value"),
                        rs.getLong("missing_price_count")
                ))
                .single();
        if (holdingValue.missingPriceCount() > 0L) {
            throw new IllegalStateException(
                    "Institution account contains a positive holding without a current price"
            );
        }
        BigDecimal openBuyReservedCash = jdbcClient.sql(
                        """
                        select coalesce(sum(reserved_cash), 0)
                          from stock_order
                         where account_id = :accountId
                           and market_type = 'ORDER_BOOK'
                           and side = 'BUY'
                           and status in ('PENDING', 'PARTIALLY_FILLED')
                        """
                )
                .param("accountId", policy.accountId())
                .query(BigDecimal.class)
                .single();
        Set<String> mandatedSymbols = Set.copyOf(symbols);
        List<String> unmanagedSymbols = jdbcClient.sql(
                        """
                        select distinct symbol
                          from (
                              select symbol
                                from stock_holding
                               where account_id = :accountId
                                 and quantity > 0
                              union
                              select symbol
                                from stock_order
                               where account_id = :accountId
                                 and market_type = 'ORDER_BOOK'
                                 and status in ('PENDING', 'PARTIALLY_FILLED')
                          ) managed_asset
                         order by symbol asc
                        """
                )
                .param("accountId", policy.accountId())
                .query(String.class)
                .list()
                .stream()
                .filter(symbol -> !mandatedSymbols.contains(symbol))
                .toList();
        if (!unmanagedSymbols.isEmpty()) {
            throw new IllegalStateException(
                    "Institution account has holdings or open orders outside enabled mandates: "
                            + String.join(",", unmanagedSymbols)
            );
        }
        Map<String, MutablePosition> mutablePositions = new LinkedHashMap<>();
        for (String symbol : symbols) {
            mutablePositions.put(symbol, new MutablePosition());
        }
        if (!symbols.isEmpty()) {
            jdbcClient.sql(
                            """
                            select symbol, quantity, reserved_quantity
                              from stock_holding
                             where account_id = :accountId
                               and symbol in (:symbols)
                             order by symbol asc
                            """
                    )
                    .param("accountId", policy.accountId())
                    .param("symbols", symbols)
                    .query((rs, rowNum) -> {
                        MutablePosition position = mutablePositions.get(rs.getString("symbol"));
                        position.actualQuantity = rs.getLong("quantity");
                        position.reservedQuantity = rs.getLong("reserved_quantity");
                        return position;
                    })
                    .list();
            jdbcClient.sql(
                            """
                            select symbol,
                                   coalesce(sum(
                                       case when side = 'BUY' then quantity - filled_quantity else 0 end
                                   ), 0) as open_buy_quantity,
                                   coalesce(sum(
                                       case when side = 'SELL' then quantity - filled_quantity else 0 end
                                   ), 0) as open_sell_quantity
                              from stock_order
                             where account_id = :accountId
                               and market_type = 'ORDER_BOOK'
                               and status in ('PENDING', 'PARTIALLY_FILLED')
                               and symbol in (:symbols)
                             group by symbol
                             order by symbol asc
                            """
                    )
                    .param("accountId", policy.accountId())
                    .param("symbols", symbols)
                    .query((rs, rowNum) -> {
                        MutablePosition position = mutablePositions.get(rs.getString("symbol"));
                        position.openBuyQuantity = rs.getLong("open_buy_quantity");
                        position.openSellQuantity = rs.getLong("open_sell_quantity");
                        return position;
                    })
                    .list();
        }
        Map<String, InstitutionPositionSnapshot> positions = new LinkedHashMap<>();
        mutablePositions.forEach((symbol, position) -> positions.put(
                symbol,
                validatedPosition(symbol, position)
        ));
        return new InstitutionAccountSnapshot(
                account.cashBalance(),
                openBuyReservedCash,
                holdingValue.totalHoldingValue(),
                positions
        );
    }

    private void validateAccount(
            AccountRow account,
            InstitutionPortfolioPolicy policy,
            LocalDate simulationTradeDate
    ) {
        if (!"ACTIVE".equals(account.status())
                || !"INSTITUTIONAL_INVESTOR".equals(account.participantCategory())
                || account.selfTradeGroupId() == null
                || account.selfTradeGroupId().isBlank()
                || account.cashBalance() == null
                || account.cashBalance().signum() < 0) {
            throw new IllegalStateException(
                    "Institution portfolio account must be active, institutional, "
                            + "self-trade grouped, and non-negative in cash"
            );
        }
        List<ParticipantMappingRow> mappings = jdbcClient.sql(
                        """
                        select participant.id as participant_id,
                               participant.participant_type,
                               participant.status as participant_status,
                               participant.self_trade_group_id as participant_self_trade_group_id,
                               participant_account.account_role,
                               participant_account.desk_code,
                               participant_account.status as account_mapping_status
                          from stock_market_participant participant
                          join stock_market_participant_account participant_account
                            on participant_account.participant_id = participant.id
                         where participant.id = :participantId
                           and participant_account.account_id = :accountId
                           and participant_account.effective_from <= :simulationTradeDate
                           and (
                               participant_account.effective_to is null
                               or participant_account.effective_to >= :simulationTradeDate
                           )
                        """
                )
                .param("participantId", policy.participantId())
                .param("accountId", policy.accountId())
                .param("simulationTradeDate", simulationTradeDate)
                .query((rs, rowNum) -> new ParticipantMappingRow(
                        rs.getLong("participant_id"),
                        rs.getString("participant_type"),
                        rs.getString("participant_status"),
                        rs.getString("participant_self_trade_group_id"),
                        rs.getString("account_role"),
                        rs.getString("desk_code"),
                        rs.getString("account_mapping_status")
                ))
                .list();
        if (mappings.size() != 1) {
            throw new IllegalStateException(
                    "Institution portfolio requires exactly one effective participant-account mapping"
            );
        }
        ParticipantMappingRow mapping = mappings.getFirst();
        if (!"INSTITUTIONAL_INVESTOR".equals(mapping.participantType())
                || !"ACTIVE".equals(mapping.participantStatus())
                || !"INSTITUTIONAL_INVESTOR".equals(mapping.accountRole())
                || mapping.deskCode() == null
                || mapping.deskCode().isBlank()
                || !"ACTIVE".equals(mapping.accountMappingStatus())
                || !account.selfTradeGroupId().equals(mapping.participantSelfTradeGroupId())) {
            throw new IllegalStateException(
                    "Institution participant, account role, and self-trade group are inconsistent"
            );
        }
        int invalidOpenOrderCount = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_order open_order
                          left join stock_order_strategy_origin strategy_origin
                            on strategy_origin.order_id = open_order.id
                          left join stock_institution_decision_run decision_run
                            on decision_run.id = strategy_origin.decision_run_id
                         where open_order.account_id = :accountId
                           and open_order.status in ('PENDING', 'PARTIALLY_FILLED')
                           and open_order.quantity > open_order.filled_quantity
                           and (
                               open_order.market_type <> 'ORDER_BOOK'
                               or open_order.origin_type is null
                               or open_order.origin_type <> 'INSTITUTIONAL_INVESTOR'
                               or open_order.self_trade_group_id is null
                               or open_order.self_trade_group_id <> :selfTradeGroupId
                               or strategy_origin.order_id is null
                               or strategy_origin.origin_type <> 'INSTITUTIONAL_INVESTOR'
                               or strategy_origin.portfolio_id <> :portfolioId
                               or strategy_origin.participant_id <> :participantId
                               or strategy_origin.policy_version <> :policyVersion
                               or decision_run.id is null
                               or decision_run.portfolio_id <> :portfolioId
                               or decision_run.execution_mode <> :executionMode
                               or decision_run.policy_version <> :policyVersion
                               or decision_run.simulation_trade_date <> :simulationTradeDate
                               or decision_run.status <> 'COMPLETED'
                           )
                        """
                )
                .param("accountId", policy.accountId())
                .param("selfTradeGroupId", account.selfTradeGroupId())
                .param("portfolioId", policy.portfolioId())
                .param("participantId", policy.participantId())
                .param("executionMode", policy.executionMode())
                .param("policyVersion", policy.policyVersion())
                .param("simulationTradeDate", simulationTradeDate)
                .query(Integer.class)
                .single();
        if (invalidOpenOrderCount > 0) {
            throw new IllegalStateException(
                    "Institution account contains an open order outside its portfolio origin"
            );
        }
    }

    private InstitutionPositionSnapshot validatedPosition(
            String symbol,
            MutablePosition position
    ) {
        if (position.reservedQuantity != position.openSellQuantity) {
            throw new IllegalStateException(
                    "Institution sell reservation does not reconcile with open orders: " + symbol
            );
        }
        return new InstitutionPositionSnapshot(
                position.actualQuantity,
                position.reservedQuantity,
                position.openBuyQuantity,
                position.openSellQuantity
        );
    }

    Map<String, InstitutionDailyBudgetSnapshot> lockDailyBudgets(
            long portfolioId,
            LocalDate simulationTradeDate
    ) {
        List<Map.Entry<String, InstitutionDailyBudgetSnapshot>> rows = jdbcClient.sql(
                        """
                        select symbol,
                               reference_daily_volume,
                               gross_quantity_limit,
                               gross_notional_limit,
                               planned_buy_quantity,
                               planned_sell_quantity,
                               planned_buy_amount,
                               planned_sell_amount,
                               policy_version,
                               version
                          from stock_institution_daily_budget
                         where portfolio_id = :portfolioId
                           and simulation_trade_date = :simulationTradeDate
                         order by symbol asc
                         for update
                        """
                )
                .param("portfolioId", portfolioId)
                .param("simulationTradeDate", simulationTradeDate)
                .query((rs, rowNum) -> Map.entry(
                        rs.getString("symbol"),
                        new InstitutionDailyBudgetSnapshot(
                                rs.getLong("reference_daily_volume"),
                                rs.getLong("gross_quantity_limit"),
                                rs.getBigDecimal("gross_notional_limit"),
                                rs.getLong("planned_buy_quantity"),
                                rs.getLong("planned_sell_quantity"),
                                rs.getBigDecimal("planned_buy_amount"),
                                rs.getBigDecimal("planned_sell_amount"),
                                rs.getLong("policy_version"),
                                rs.getLong("version")
                        )
                ))
                .list();
        Map<String, InstitutionDailyBudgetSnapshot> result = new LinkedHashMap<>();
        rows.forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(result);
    }

    Map<String, InstitutionDecisionAction> findPreviousActions(long portfolioId) {
        Optional<Long> latestRunId = jdbcClient.sql(
                        """
                        select max(id)
                          from stock_institution_decision_run
                         where portfolio_id = :portfolioId
                           and status = 'COMPLETED'
                        """
                )
                .param("portfolioId", portfolioId)
                .query(Long.class)
                .optional();
        if (latestRunId.isEmpty()) {
            return Map.of();
        }
        List<Map.Entry<String, InstitutionDecisionAction>> rows = jdbcClient.sql(
                        """
                        select symbol, action
                          from stock_institution_decision_item
                         where decision_run_id = :decisionRunId
                         order by symbol asc
                        """
                )
                .param("decisionRunId", latestRunId.get())
                .query((rs, rowNum) -> Map.entry(
                        rs.getString("symbol"),
                        InstitutionDecisionAction.valueOf(rs.getString("action"))
                ))
                .list();
        Map<String, InstitutionDecisionAction> result = new LinkedHashMap<>();
        rows.forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(result);
    }

    boolean decisionRunExists(long portfolioId, LocalDateTime decisionSlot) {
        Integer count = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_institution_decision_run
                         where portfolio_id = :portfolioId
                           and decision_slot = :decisionSlot
                        """
                )
                .param("portfolioId", portfolioId)
                .param("decisionSlot", decisionSlot)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    long insertDecisionRun(
            InstitutionPortfolioPolicy policy,
            LocalDateTime decisionSlot,
            LocalDate simulationTradeDate,
            long deterministicSeed,
            LocalDateTime createdAt
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        int inserted = jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    """
                    insert into stock_institution_decision_run(
                        decision_slot, simulation_trade_date, portfolio_id,
                        execution_mode, policy_version, deterministic_seed,
                        status, error_message, created_at, completed_at
                    ) values (?, ?, ?, ?, ?, ?, 'CLAIMED', null, ?, null)
                    """,
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setObject(1, decisionSlot);
            statement.setObject(2, simulationTradeDate);
            statement.setLong(3, policy.portfolioId());
            statement.setString(4, policy.executionMode());
            statement.setLong(5, policy.policyVersion());
            statement.setLong(6, deterministicSeed);
            statement.setObject(7, createdAt);
            return statement;
        }, keyHolder);
        if (inserted != 1 || keyHolder.getKey() == null) {
            throw new IllegalStateException("Institution decision run insert did not return one generated id");
        }
        return keyHolder.getKey().longValue();
    }

    void insertDecisionItems(long decisionRunId, List<InstitutionDecisionItem> items, LocalDateTime createdAt) {
        if (items.isEmpty()) {
            return;
        }
        int[][] updateBatches = jdbcTemplate.batchUpdate(
                """
                insert into stock_institution_decision_item(
                    decision_run_id, symbol,
                    primary_price_pressure, primary_asset_preference_pressure,
                    primary_volatility_pressure, primary_liquidity_pressure,
                    primary_execution_aggression_pressure,
                    secondary_price_pressure, secondary_asset_preference_pressure,
                    secondary_volatility_pressure, secondary_liquidity_pressure,
                    secondary_execution_aggression_pressure,
                    blended_price_pressure, blended_asset_preference_pressure,
                    blended_volatility_pressure, blended_liquidity_pressure,
                    blended_execution_aggression_pressure,
                    return_5_day, return_20_day, report_pressure,
                    current_price, liquid_asset_amount,
                    actual_quantity, open_buy_quantity, open_sell_quantity, projected_quantity,
                    actual_allocation_rate, projected_allocation_rate, base_allocation_rate,
                    target_stock_allocation_rate, target_allocation_rate,
                    target_amount, raw_trade_amount, gated_trade_amount, gated_quantity,
                    action, decision_reason, gate_reason,
                    reference_daily_volume, remaining_daily_quantity_budget,
                    remaining_daily_notional_budget, created_at
                ) values (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?
                )
                """,
                items,
                items.size(),
                (statement, item) -> bindDecisionItem(statement, decisionRunId, item, createdAt)
        );
        for (int[] updates : updateBatches) {
            for (int update : updates) {
                if (update != 1 && update != Statement.SUCCESS_NO_INFO) {
                    throw new IllegalStateException("Institution decision item batch insert was incomplete");
                }
            }
        }
    }

    private void bindDecisionItem(
            PreparedStatement statement,
            long decisionRunId,
            InstitutionDecisionItem item,
            LocalDateTime createdAt
    ) throws java.sql.SQLException {
        int index = 1;
        statement.setLong(index++, decisionRunId);
        statement.setString(index++, item.symbol());
        statement.setInt(index++, item.primaryPressure().price());
        statement.setInt(index++, item.primaryPressure().assetPreference());
        statement.setInt(index++, item.primaryPressure().volatility());
        statement.setInt(index++, item.primaryPressure().liquidity());
        statement.setInt(index++, item.primaryPressure().executionAggression());
        statement.setInt(index++, item.secondaryPressure().price());
        statement.setInt(index++, item.secondaryPressure().assetPreference());
        statement.setInt(index++, item.secondaryPressure().volatility());
        statement.setInt(index++, item.secondaryPressure().liquidity());
        statement.setInt(index++, item.secondaryPressure().executionAggression());
        statement.setBigDecimal(index++, item.blendedPricePressure());
        statement.setBigDecimal(index++, item.blendedAssetPreferencePressure());
        statement.setBigDecimal(index++, item.blendedVolatilityPressure());
        statement.setBigDecimal(index++, item.blendedLiquidityPressure());
        statement.setBigDecimal(index++, item.blendedExecutionAggressionPressure());
        statement.setBigDecimal(index++, item.return5Day());
        statement.setBigDecimal(index++, item.return20Day());
        statement.setBigDecimal(index++, item.reportPressure());
        statement.setBigDecimal(index++, item.currentPrice());
        statement.setBigDecimal(index++, item.liquidAssetAmount());
        statement.setLong(index++, item.actualQuantity());
        statement.setLong(index++, item.openBuyQuantity());
        statement.setLong(index++, item.openSellQuantity());
        statement.setLong(index++, item.projectedQuantity());
        statement.setBigDecimal(index++, item.actualAllocationRate());
        statement.setBigDecimal(index++, item.projectedAllocationRate());
        statement.setBigDecimal(index++, item.baseAllocationRate());
        statement.setBigDecimal(index++, item.targetStockAllocationRate());
        statement.setBigDecimal(index++, item.targetAllocationRate());
        statement.setBigDecimal(index++, item.targetAmount());
        statement.setBigDecimal(index++, item.rawTradeAmount());
        statement.setBigDecimal(index++, item.gatedTradeAmount());
        statement.setLong(index++, item.gatedQuantity());
        statement.setString(index++, item.action().name());
        statement.setString(index++, item.decisionReason());
        statement.setString(index++, item.gateReason());
        statement.setLong(index++, item.referenceDailyVolume());
        statement.setLong(index++, item.remainingDailyQuantityBudget());
        statement.setBigDecimal(index++, item.remainingDailyNotionalBudget());
        statement.setObject(index, createdAt);
    }

    void claimDailyBudgets(
            LocalDate simulationTradeDate,
            InstitutionPortfolioPolicy policy,
            List<InstitutionDecisionItem> items,
            Map<String, InstitutionDailyBudgetSnapshot> existingBudgets,
            LocalDateTime now
    ) {
        for (InstitutionDecisionItem item : items) {
            if (item.dailyGrossNotionalLimit().signum() <= 0) {
                continue;
            }
            InstitutionDailyBudgetSnapshot existing = existingBudgets.get(item.symbol());
            BigDecimal buyAmount = item.action() == InstitutionDecisionAction.BUY
                    ? item.gatedTradeAmount()
                    : BigDecimal.ZERO;
            BigDecimal sellAmount = item.action() == InstitutionDecisionAction.SELL
                    ? item.gatedTradeAmount()
                    : BigDecimal.ZERO;
            long buyQuantity = item.action() == InstitutionDecisionAction.BUY
                    ? item.gatedQuantity()
                    : 0L;
            long sellQuantity = item.action() == InstitutionDecisionAction.SELL
                    ? item.gatedQuantity()
                    : 0L;
            if (existing == null) {
                int inserted = jdbcTemplate.update(
                        """
                        insert into stock_institution_daily_budget(
                            simulation_trade_date, portfolio_id, symbol,
                            reference_daily_volume, gross_quantity_limit, gross_notional_limit,
                            planned_buy_quantity, planned_sell_quantity,
                            planned_buy_amount, planned_sell_amount,
                            submitted_buy_amount, submitted_sell_amount,
                            executed_buy_amount, executed_sell_amount,
                            policy_version, version, created_at, updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 0, ?, 0, ?, ?)
                        """,
                        simulationTradeDate,
                        policy.portfolioId(),
                        item.symbol(),
                        item.referenceDailyVolume(),
                        item.dailyGrossQuantityLimit(),
                        item.dailyGrossNotionalLimit(),
                        buyQuantity,
                        sellQuantity,
                        buyAmount,
                        sellAmount,
                        policy.policyVersion(),
                        now,
                        now
                );
                requireSingleBudgetWrite(inserted, item.symbol());
                continue;
            }
            if (item.gatedQuantity() <= 0L) {
                continue;
            }
            int updated = jdbcTemplate.update(
                    """
                    update stock_institution_daily_budget
                       set planned_buy_quantity = planned_buy_quantity + ?,
                           planned_sell_quantity = planned_sell_quantity + ?,
                           planned_buy_amount = planned_buy_amount + ?,
                           planned_sell_amount = planned_sell_amount + ?,
                           version = version + 1,
                           updated_at = ?
                     where simulation_trade_date = ?
                       and portfolio_id = ?
                       and symbol = ?
                       and version = ?
                    """,
                    buyQuantity,
                    sellQuantity,
                    buyAmount,
                    sellAmount,
                    now,
                    simulationTradeDate,
                    policy.portfolioId(),
                    item.symbol(),
                    existing.version()
            );
            requireSingleBudgetWrite(updated, item.symbol());
        }
    }

    void insertOrderIntents(
            long decisionRunId,
            InstitutionPortfolioPolicy policy,
            List<InstitutionDecisionItem> items,
            LocalDateTime now
    ) {
        List<InstitutionDecisionItem> actionableItems = items.stream()
                .filter(item -> item.action() != InstitutionDecisionAction.HOLD)
                .filter(item -> item.gatedQuantity() > 0L)
                .toList();
        if (actionableItems.isEmpty()) {
            return;
        }
        int[][] insertedChunks = jdbcTemplate.batchUpdate(
                """
                insert into stock_institution_order_intent(
                    decision_run_id, symbol, portfolio_id, participant_id,
                    account_id, side, requested_quantity, reference_daily_volume,
                    planned_amount,
                    execution_aggression_pressure, policy_version, status,
                    submitted_order_id, submitted_price, submitted_quantity,
                    submission_reason, created_at, updated_at, submitted_at
                ) values (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING',
                    null, null, 0, null, ?, ?, null
                )
                """,
                actionableItems,
                actionableItems.size(),
                (statement, item) -> {
                    statement.setLong(1, decisionRunId);
                    statement.setString(2, item.symbol());
                    statement.setLong(3, policy.portfolioId());
                    statement.setLong(4, policy.participantId());
                    statement.setLong(5, policy.accountId());
                    statement.setString(6, item.action().name());
                    statement.setLong(7, item.gatedQuantity());
                    statement.setLong(8, item.referenceDailyVolume());
                    statement.setBigDecimal(9, item.gatedTradeAmount());
                    statement.setBigDecimal(10, item.blendedExecutionAggressionPressure());
                    statement.setLong(11, policy.policyVersion());
                    statement.setObject(12, now);
                    statement.setObject(13, now);
                }
        );
        long insertedCount = java.util.Arrays.stream(insertedChunks)
                .flatMapToInt(java.util.Arrays::stream)
                .filter(count -> count == 1 || count == Statement.SUCCESS_NO_INFO)
                .count();
        if (insertedCount != actionableItems.size()) {
            throw new IllegalStateException(
                    "Institution LIVE order intent insert count mismatch"
            );
        }
    }

    private void requireSingleBudgetWrite(int updated, String symbol) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "Institution daily budget optimistic write failed for symbol " + symbol
            );
        }
    }

    void markDecisionRunCompleted(long decisionRunId, LocalDateTime completedAt) {
        int updated = jdbcTemplate.update(
                """
                update stock_institution_decision_run
                   set status = 'COMPLETED',
                       completed_at = ?
                 where id = ?
                   and status = 'CLAIMED'
                """,
                completedAt,
                decisionRunId
        );
        if (updated != 1) {
            throw new IllegalStateException("Institution decision run completion failed");
        }
    }

    void markDecisionRunFailed(long decisionRunId, String errorMessage, LocalDateTime completedAt) {
        int updated = jdbcTemplate.update(
                """
                update stock_institution_decision_run
                   set status = 'FAILED',
                       error_message = ?,
                       completed_at = ?
                 where id = ?
                   and status = 'CLAIMED'
                """,
                truncate(errorMessage, 1000),
                completedAt,
                decisionRunId
        );
        if (updated != 1) {
            throw new IllegalStateException("Institution decision run failure audit update failed");
        }
    }

    void updateNextDecisionAt(
            long portfolioId,
            LocalDateTime nextDecisionAt,
            LocalDateTime updatedAt
    ) {
        int updated = jdbcTemplate.update(
                """
                update stock_institution_portfolio
                   set next_decision_at = ?,
                       updated_at = ?
                 where id = ?
                   and status = 'ACTIVE'
                """,
                nextDecisionAt,
                updatedAt,
                portfolioId
        );
        if (updated != 1) {
            throw new IllegalStateException("Institution portfolio next decision update failed");
        }
    }

    private String truncate(String value, int maximumLength) {
        if (value == null || value.isBlank()) {
            return "Unknown institution LIVE planning failure";
        }
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    private record AccountRow(
            long id,
            String status,
            String participantCategory,
            String selfTradeGroupId,
            BigDecimal cashBalance
    ) {
    }

    private record ParticipantMappingRow(
            long participantId,
            String participantType,
            String participantStatus,
            String participantSelfTradeGroupId,
            String accountRole,
            String deskCode,
            String accountMappingStatus
    ) {
    }

    private record HoldingValueRow(BigDecimal totalHoldingValue, long missingPriceCount) {
    }

    private static final class MutablePosition {
        private long actualQuantity;
        private long reservedQuantity;
        private long openBuyQuantity;
        private long openSellQuantity;
    }
}
