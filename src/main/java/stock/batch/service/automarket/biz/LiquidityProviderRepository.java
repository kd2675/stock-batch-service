package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.automarket.model.AutoOrder;
import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoMarketDistributionBias;

@Component
class LiquidityProviderRepository {

    static final int MAX_OPEN_ORDER_ROWS = 50;
    private static final String BUY = "BUY";
    private static final String SELL = "SELL";

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final boolean mysql;

    LiquidityProviderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate));
        String productName = jdbcTemplate.execute(
                (ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName()
        );
        this.mysql = productName != null && productName.toLowerCase(Locale.ROOT).contains("mysql");
    }

    List<MandateReference> findDueMandates(
            LocalDate simulationTradeDate,
            LocalDateTime now,
            int limit
    ) {
        return jdbcClient.sql(
                """
                select m.id, m.symbol
                  from stock_liquidity_mandate m
                 where (m.next_quote_at is null or m.next_quote_at <= :now)
                   and (
                       (
                           m.status = 'ACTIVE'
                           and m.execution_mode = 'LIVE'
                           and m.contract_start_date <= :simulationTradeDate
                           and (
                               m.contract_end_date is null
                               or m.contract_end_date >= :simulationTradeDate
                           )
                       )
                       or exists (
                           select 1
                             from stock_order o
                            where o.account_id = m.account_id
                              and o.symbol = m.symbol
                              and o.origin_type = 'LIQUIDITY_PROVIDER'
                              and o.market_type = 'ORDER_BOOK'
                              and o.status in ('PENDING', 'PARTIALLY_FILLED')
                              and o.quantity > o.filled_quantity
                       )
                   )
                 order by case when m.next_quote_at is null then 0 else 1 end asc,
                          m.next_quote_at asc,
                          m.id asc
                 limit :limit
                """
        )
                .param("simulationTradeDate", simulationTradeDate)
                .param("now", now)
                .param("limit", Math.max(1, limit))
                .query((rs, rowNum) -> new MandateReference(
                        rs.getLong("id"),
                        rs.getString("symbol")
                ))
                .list();
    }

    Optional<LiquidityProviderMandate> lockMandate(long mandateId) {
        return jdbcClient.sql(
                """
                select id, participant_id, account_id, symbol, mandate_code,
                       execution_mode, status, contract_start_date, contract_end_date,
                       target_spread_ticks, max_spread_ticks, max_order_quantity,
                       reference_daily_volume, target_open_participation_rate,
                       max_open_participation_rate, max_single_order_participation_rate,
                       external_depth_levels, max_external_depth_participation_rate,
                       daily_execution_participation_rate, daily_submission_multiplier,
                       target_inventory_quantity, inventory_band_quantity,
                       inventory_skew_ticks, primary_regime_weight,
                       liquidity_size_sensitivity, volatility_spread_max_ticks,
                       price_regime_max_skew_ticks, passive_only,
                       minimum_quote_lifetime_seconds, reprice_threshold_ticks,
                       order_ttl_seconds, quote_interval_seconds,
                       daily_loss_limit_amount, next_quote_at, policy_version
                  from stock_liquidity_mandate
                 where id = :mandateId
                 for update
                """
        )
                .param("mandateId", mandateId)
                .query((rs, rowNum) -> mapMandate(rs))
                .optional();
    }

    Optional<AutoMarketConfig> findSafetyMarketConfig(String symbol) {
        return jdbcClient.sql(
                """
                select i.symbol, i.market, i.tradable_shares, i.tick_size, i.price_limit_rate,
                       p.current_price, p.previous_close
                  from stock_order_book_instrument i
                  join stock_price p on p.symbol = i.symbol
                 where i.symbol = :symbol
                """
        )
                .param("symbol", symbol)
                .query((rs, rowNum) -> new AutoMarketConfig(
                        rs.getString("symbol"),
                        rs.getString("market"),
                        1,
                        300,
                        rs.getLong("tradable_shares"),
                        rs.getBigDecimal("tick_size"),
                        rs.getBigDecimal("current_price"),
                        rs.getBigDecimal("previous_close"),
                        rs.getBigDecimal("price_limit_rate"),
                        null,
                        AutoMarketDistributionBias.NEUTRAL,
                        AutoMarketDistributionBias.NEUTRAL
                ))
                .optional();
    }

    LiquidityProviderAccountSnapshot lockAccountSnapshot(
            LiquidityProviderMandate mandate,
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
                .param("accountId", mandate.accountId())
                .query((rs, rowNum) -> new AccountRow(
                        rs.getLong("id"),
                        rs.getString("status"),
                        rs.getString("participant_category"),
                        rs.getString("self_trade_group_id"),
                        rs.getBigDecimal("cash_balance")
                ))
                .optional()
                .orElse(AccountRow.missing(mandate.accountId()));

        HoldingRow holding = jdbcClient.sql(
                """
                select quantity, reserved_quantity, average_price
                  from stock_holding
                 where account_id = :accountId
                   and symbol = :symbol
                 for update
                """
        )
                .param("accountId", mandate.accountId())
                .param("symbol", mandate.symbol())
                .query((rs, rowNum) -> new HoldingRow(
                        rs.getLong("quantity"),
                        rs.getLong("reserved_quantity"),
                        rs.getBigDecimal("average_price")
                ))
                .optional()
                .orElse(HoldingRow.EMPTY);

        RoleRow role = jdbcClient.sql(
                """
                select p.id as participant_id,
                       p.participant_type,
                       p.status as participant_status,
                       p.self_trade_group_id as participant_self_trade_group_id,
                       pa.account_role,
                       pa.desk_code,
                       pa.status as mapping_status,
                       pa.effective_from,
                       pa.effective_to
                  from stock_market_participant p
                  left join stock_market_participant_account pa
                    on pa.participant_id = p.id
                   and pa.account_id = :accountId
                 where p.id = :participantId
                """
        )
                .param("participantId", mandate.participantId())
                .param("accountId", mandate.accountId())
                .query((rs, rowNum) -> new RoleRow(
                        rs.getLong("participant_id"),
                        rs.getString("participant_type"),
                        rs.getString("participant_status"),
                        rs.getString("participant_self_trade_group_id"),
                        rs.getString("account_role"),
                        rs.getString("desk_code"),
                        rs.getString("mapping_status"),
                        toLocalDate(rs, "effective_from"),
                        toLocalDate(rs, "effective_to")
                ))
                .optional()
                .orElse(RoleRow.MISSING);

        int nonLiquidityOpenOrderCount = jdbcClient.sql(
                """
                select count(*)
                 from stock_order
                 where account_id = :accountId
                   and status in ('PENDING', 'PARTIALLY_FILLED')
                   and quantity > filled_quantity
                   and (
                       origin_type is null
                       or origin_type <> 'LIQUIDITY_PROVIDER'
                       or symbol <> :symbol
                       or market_type <> 'ORDER_BOOK'
                       or order_type <> 'LIMIT'
                       or not exists (
                           select 1
                             from stock_order_strategy_origin strategy_origin
                            where strategy_origin.order_id = stock_order.id
                              and strategy_origin.origin_type = 'LIQUIDITY_PROVIDER'
                              and strategy_origin.participant_id = :participantId
                              and strategy_origin.liquidity_mandate_id = :mandateId
                              and strategy_origin.policy_version = :policyVersion
                       )
                   )
                """
                )
                .param("accountId", mandate.accountId())
                .param("symbol", mandate.symbol())
                .param("participantId", mandate.participantId())
                .param("mandateId", mandate.id())
                .param("policyVersion", mandate.policyVersion())
                .query(Integer.class)
                .single();

        int unmanagedHoldingCount = jdbcClient.sql(
                """
                select count(*)
                  from stock_holding
                 where account_id = :accountId
                   and symbol <> :symbol
                   and (quantity > 0 or reserved_quantity > 0)
                """
        )
                .param("accountId", mandate.accountId())
                .param("symbol", mandate.symbol())
                .query(Integer.class)
                .single();

        return new LiquidityProviderAccountSnapshot(
                account.id(),
                account.status(),
                account.participantCategory(),
                account.selfTradeGroupId(),
                account.cashBalance(),
                holding.quantity(),
                holding.reservedQuantity(),
                holding.averagePrice(),
                role.participantId(),
                role.participantType(),
                role.participantStatus(),
                role.participantSelfTradeGroupId(),
                role.accountRole(),
                role.deskCode(),
                role.mappingStatus(),
                role.effectiveFrom(),
                role.effectiveTo(),
                nonLiquidityOpenOrderCount,
                unmanagedHoldingCount
        );
    }

    OpenOrderLoad findOpenOrders(LiquidityProviderMandate mandate) {
        List<AutoOrder> rows = jdbcClient.sql(
                """
                select id, account_id, symbol, side, quantity, filled_quantity,
                       reserved_cash, limit_price, expires_at, created_at
                  from stock_order
                 where account_id = :accountId
                   and symbol = :symbol
                   and origin_type = 'LIQUIDITY_PROVIDER'
                   and market_type = 'ORDER_BOOK'
                   and order_type = 'LIMIT'
                   and status in ('PENDING', 'PARTIALLY_FILLED')
                   and quantity > filled_quantity
                 order by created_at asc, id asc
                 limit :limit
                """
        )
                .param("accountId", mandate.accountId())
                .param("symbol", mandate.symbol())
                .param("limit", MAX_OPEN_ORDER_ROWS + 1)
                .query((rs, rowNum) -> new AutoOrder(
                        rs.getLong("id"),
                        rs.getLong("account_id"),
                        rs.getString("symbol"),
                        rs.getString("side"),
                        rs.getLong("quantity"),
                        rs.getLong("filled_quantity"),
                        rs.getBigDecimal("reserved_cash"),
                        rs.getBigDecimal("limit_price"),
                        null,
                        null,
                        toLocalDateTime(rs, "expires_at"),
                        toLocalDateTime(rs, "created_at")
                ))
                .list();
        return new OpenOrderLoad(rows, rows.size() > MAX_OPEN_ORDER_ROWS);
    }

    LiquidityProviderExternalBook findExternalBook(
            LiquidityProviderMandate mandate,
            String selfTradeGroupId
    ) {
        String resolvedSelfTradeGroupId = selfTradeGroupId == null || selfTradeGroupId.isBlank()
                ? "ACCOUNT:" + mandate.accountId()
                : selfTradeGroupId;
        List<DepthLevel> buyLevels = findExternalDepthLevels(
                mandate,
                BUY,
                resolvedSelfTradeGroupId
        );
        List<DepthLevel> sellLevels = findExternalDepthLevels(
                mandate,
                SELL,
                resolvedSelfTradeGroupId
        );
        return new LiquidityProviderExternalBook(
                buyLevels.isEmpty() ? null : buyLevels.getFirst().price(),
                sellLevels.isEmpty() ? null : sellLevels.getFirst().price(),
                depthQuantity(buyLevels),
                depthQuantity(sellLevels)
        );
    }

    LiquidityProviderExecutionSnapshot findExecutionSnapshot(
            LiquidityProviderMandate mandate,
            LocalDate simulationTradeDate
    ) {
        String executionTable = mysql
                ? "stock_execution e force index (idx_stock_execution_source_account_symbol_time)"
                : "stock_execution e";
        String orderTable = mysql ? "stock_order o force index (primary)" : "stock_order o";
        LocalDateTime start = simulationTradeDate.atStartOfDay();
        LocalDateTime end = simulationTradeDate.plusDays(1).atStartOfDay();
        return jdbcClient.sql(
                """
                select coalesce(sum(case when e.side = 'BUY' then e.quantity else 0 end), 0)
                           as buy_quantity,
                       coalesce(sum(case when e.side = 'SELL' then e.quantity else 0 end), 0)
                           as sell_quantity,
                       coalesce(sum(case when e.side = 'BUY' then e.gross_amount else 0 end), 0)
                           as buy_amount,
                       coalesce(sum(case when e.side = 'SELL' then e.gross_amount else 0 end), 0)
                           as sell_amount,
                       coalesce(sum(e.realized_profit), 0) as realized_profit
                  from %s
                  join %s on o.id = e.order_id
                 where e.source = 'INTERNAL_ORDER_BOOK'
                   and e.account_id = :accountId
                   and e.symbol = :symbol
                   and e.executed_at >= :start
                   and e.executed_at < :end
                   and o.origin_type = 'LIQUIDITY_PROVIDER'
                """.formatted(executionTable, orderTable)
        )
                .param("accountId", mandate.accountId())
                .param("symbol", mandate.symbol())
                .param("start", start)
                .param("end", end)
                .query((rs, rowNum) -> new LiquidityProviderExecutionSnapshot(
                        rs.getLong("buy_quantity"),
                        rs.getLong("sell_quantity"),
                        money(rs.getBigDecimal("buy_amount")),
                        money(rs.getBigDecimal("sell_amount")),
                        money(rs.getBigDecimal("realized_profit"))
                ))
                .single();
    }

    LiquidityProviderDailyState lockDailyState(
            LocalDate simulationTradeDate,
            long mandateId
    ) {
        return jdbcClient.sql(
                """
                select simulation_trade_date, mandate_id,
                       reference_daily_volume,
                       execution_quantity_limit,
                       submission_quantity_limit,
                       submitted_buy_quantity, submitted_sell_quantity,
                       submitted_buy_amount, submitted_sell_amount,
                       cancelled_buy_quantity, cancelled_sell_quantity,
                       opening_net_asset_value,
                       quote_run_count, gate_reason, limit_breached,
                       policy_version, version
                  from stock_liquidity_daily_state
                 where simulation_trade_date = :simulationTradeDate
                   and mandate_id = :mandateId
                 for update
                """
        )
                .param("simulationTradeDate", simulationTradeDate)
                .param("mandateId", mandateId)
                .query((rs, rowNum) -> new LiquidityProviderDailyState(
                        true,
                        rs.getDate("simulation_trade_date").toLocalDate(),
                        rs.getLong("mandate_id"),
                        rs.getLong("reference_daily_volume"),
                        rs.getLong("execution_quantity_limit"),
                        rs.getLong("submission_quantity_limit"),
                        rs.getLong("submitted_buy_quantity"),
                        rs.getLong("submitted_sell_quantity"),
                        money(rs.getBigDecimal("submitted_buy_amount")),
                        money(rs.getBigDecimal("submitted_sell_amount")),
                        rs.getLong("cancelled_buy_quantity"),
                        rs.getLong("cancelled_sell_quantity"),
                        money(rs.getBigDecimal("opening_net_asset_value")),
                        rs.getLong("quote_run_count"),
                        rs.getString("gate_reason"),
                        rs.getBoolean("limit_breached"),
                        rs.getLong("policy_version"),
                        rs.getLong("version")
                ))
                .optional()
                .orElseGet(() -> LiquidityProviderDailyState.empty(simulationTradeDate, mandateId));
    }

    void persistDailyState(
            LiquidityProviderQuoteInput input,
            LiquidityProviderQuotePlan plan,
            LocalDateTime updatedAt
    ) {
        LiquidityProviderDailyState previous = input.dailyState();
        long submittedBuyQuantity = saturatingAdd(
                previous.submittedBuyQuantity(),
                plan.submittedQuantity(BUY)
        );
        long submittedSellQuantity = saturatingAdd(
                previous.submittedSellQuantity(),
                plan.submittedQuantity(SELL)
        );
        BigDecimal submittedBuyAmount = previous.submittedBuyAmount().add(plan.submittedAmount(BUY));
        BigDecimal submittedSellAmount = previous.submittedSellAmount().add(plan.submittedAmount(SELL));
        long cancelledBuyQuantity = saturatingAdd(
                previous.cancelledBuyQuantity(),
                plan.cancelledQuantity(BUY)
        );
        long cancelledSellQuantity = saturatingAdd(
                previous.cancelledSellQuantity(),
                plan.cancelledQuantity(SELL)
        );
        long lastOpenBuyQuantity = saturatingAdd(
                plan.retainedBuyOpenQuantity(),
                plan.submittedQuantity(BUY)
        );
        long lastOpenSellQuantity = saturatingAdd(
                plan.retainedSellOpenQuantity(),
                plan.submittedQuantity(SELL)
        );
        long quoteRunCount = saturatingAdd(previous.quoteRunCount(), 1L);
        BigDecimal pricePressure = pressure(plan.blendedPricePressure());
        BigDecimal volatilityPressure = pressure(plan.blendedVolatilityPressure());
        BigDecimal liquidityPressure = pressure(plan.blendedLiquidityPressure());

        Object[] updateParameters = stateParameters(
                input,
                plan,
                submittedBuyQuantity,
                submittedSellQuantity,
                submittedBuyAmount,
                submittedSellAmount,
                cancelledBuyQuantity,
                cancelledSellQuantity,
                lastOpenBuyQuantity,
                lastOpenSellQuantity,
                quoteRunCount,
                pricePressure,
                volatilityPressure,
                liquidityPressure,
                updatedAt
        );
        int updated = jdbcTemplate.update(
                """
                update stock_liquidity_daily_state
                   set reference_daily_volume = ?,
                       execution_quantity_limit = ?,
                       submission_quantity_limit = ?,
                       submitted_buy_quantity = ?,
                       submitted_sell_quantity = ?,
                       submitted_buy_amount = ?,
                       submitted_sell_amount = ?,
                       cancelled_buy_quantity = ?,
                       cancelled_sell_quantity = ?,
                       executed_buy_quantity = ?,
                       executed_sell_quantity = ?,
                       executed_buy_amount = ?,
                       executed_sell_amount = ?,
                       realized_profit = ?,
                       unrealized_profit = ?,
                       opening_net_asset_value = ?,
                       current_net_asset_value = ?,
                       risk_profit = ?,
                       target_buy_open_quantity = ?,
                       target_sell_open_quantity = ?,
                       last_open_buy_quantity = ?,
                       last_open_sell_quantity = ?,
                       external_buy_depth_quantity = ?,
                       external_sell_depth_quantity = ?,
                       last_bid_price = ?,
                       last_ask_price = ?,
                       last_inventory_quantity = ?,
                       last_projected_inventory_quantity = ?,
                       blended_price_pressure = ?,
                       blended_volatility_pressure = ?,
                       blended_liquidity_pressure = ?,
                       state_status = ?,
                       gate_reason = ?,
                       quote_run_count = ?,
                       limit_breached = ?,
                       policy_version = ?,
                       version = version + 1,
                       updated_at = ?
                 where simulation_trade_date = ?
                   and mandate_id = ?
                """,
                updateParameters
        );
        if (updated == 1) {
            return;
        }
        if (updated != 0) {
            throw new IllegalStateException(
                    "Liquidity daily-state update count mismatch: mandateId=%d, count=%d"
                            .formatted(input.mandate().id(), updated)
            );
        }
        int inserted = jdbcTemplate.update(
                """
                insert into stock_liquidity_daily_state(
                    simulation_trade_date, mandate_id,
                    reference_daily_volume, execution_quantity_limit, submission_quantity_limit,
                    submitted_buy_quantity, submitted_sell_quantity,
                    submitted_buy_amount, submitted_sell_amount,
                    cancelled_buy_quantity, cancelled_sell_quantity,
                    executed_buy_quantity, executed_sell_quantity,
                    executed_buy_amount, executed_sell_amount,
                    realized_profit, unrealized_profit,
                    opening_net_asset_value, current_net_asset_value, risk_profit,
                    target_buy_open_quantity, target_sell_open_quantity,
                    last_open_buy_quantity, last_open_sell_quantity,
                    external_buy_depth_quantity, external_sell_depth_quantity,
                    last_bid_price, last_ask_price,
                    last_inventory_quantity, last_projected_inventory_quantity,
                    blended_price_pressure, blended_volatility_pressure,
                    blended_liquidity_pressure, state_status, gate_reason,
                    quote_run_count, limit_breached, policy_version,
                    version, created_at, updated_at
                )
                values (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?
                )
                """,
                input.simulationTradeDate(),
                input.mandate().id(),
                plan.referenceDailyVolume(),
                plan.executionQuantityLimit(),
                plan.submissionQuantityLimit(),
                submittedBuyQuantity,
                submittedSellQuantity,
                submittedBuyAmount,
                submittedSellAmount,
                cancelledBuyQuantity,
                cancelledSellQuantity,
                input.executions().buyQuantity(),
                input.executions().sellQuantity(),
                input.executions().buyAmount(),
                input.executions().sellAmount(),
                input.executions().realizedProfit(),
                plan.unrealizedProfit(),
                plan.openingNetAssetValue(),
                plan.currentNetAssetValue(),
                plan.riskProfit(),
                plan.targetBuyOpenQuantity(),
                plan.targetSellOpenQuantity(),
                lastOpenBuyQuantity,
                lastOpenSellQuantity,
                plan.externalBuyDepthQuantity(),
                plan.externalSellDepthQuantity(),
                plan.bidPrice(),
                plan.askPrice(),
                plan.inventoryQuantity(),
                plan.projectedInventoryQuantity(),
                pricePressure,
                volatilityPressure,
                liquidityPressure,
                plan.stateStatus(),
                plan.gateReason(),
                quoteRunCount,
                plan.limitBreached(),
                input.mandate().policyVersion(),
                updatedAt,
                updatedAt
        );
        if (inserted != 1) {
            throw new IllegalStateException(
                    "Liquidity daily-state insert count mismatch: mandateId=%d, count=%d"
                            .formatted(input.mandate().id(), inserted)
            );
        }
    }

    void advanceNextQuoteAt(
            LiquidityProviderMandate mandate,
            LocalDateTime now
    ) {
        int updated = jdbcTemplate.update(
                """
                update stock_liquidity_mandate
                   set next_quote_at = ?,
                       updated_at = ?
                 where id = ?
                """,
                now.plusSeconds(mandate.quoteIntervalSeconds()),
                now,
                mandate.id()
        );
        if (updated != 1) {
            throw new IllegalStateException(
                    "Liquidity mandate next-quote update count mismatch: mandateId=%d, count=%d"
                            .formatted(mandate.id(), updated)
            );
        }
    }

    private Object[] stateParameters(
            LiquidityProviderQuoteInput input,
            LiquidityProviderQuotePlan plan,
            long submittedBuyQuantity,
            long submittedSellQuantity,
            BigDecimal submittedBuyAmount,
            BigDecimal submittedSellAmount,
            long cancelledBuyQuantity,
            long cancelledSellQuantity,
            long lastOpenBuyQuantity,
            long lastOpenSellQuantity,
            long quoteRunCount,
            BigDecimal pricePressure,
            BigDecimal volatilityPressure,
            BigDecimal liquidityPressure,
            LocalDateTime updatedAt
    ) {
        return new Object[]{
                plan.referenceDailyVolume(),
                plan.executionQuantityLimit(),
                plan.submissionQuantityLimit(),
                submittedBuyQuantity,
                submittedSellQuantity,
                submittedBuyAmount,
                submittedSellAmount,
                cancelledBuyQuantity,
                cancelledSellQuantity,
                input.executions().buyQuantity(),
                input.executions().sellQuantity(),
                input.executions().buyAmount(),
                input.executions().sellAmount(),
                input.executions().realizedProfit(),
                plan.unrealizedProfit(),
                plan.openingNetAssetValue(),
                plan.currentNetAssetValue(),
                plan.riskProfit(),
                plan.targetBuyOpenQuantity(),
                plan.targetSellOpenQuantity(),
                lastOpenBuyQuantity,
                lastOpenSellQuantity,
                plan.externalBuyDepthQuantity(),
                plan.externalSellDepthQuantity(),
                plan.bidPrice(),
                plan.askPrice(),
                plan.inventoryQuantity(),
                plan.projectedInventoryQuantity(),
                pricePressure,
                volatilityPressure,
                liquidityPressure,
                plan.stateStatus(),
                plan.gateReason(),
                quoteRunCount,
                plan.limitBreached(),
                input.mandate().policyVersion(),
                updatedAt,
                input.simulationTradeDate(),
                input.mandate().id()
        };
    }

    private List<DepthLevel> findExternalDepthLevels(
            LiquidityProviderMandate mandate,
            String side,
            String selfTradeGroupId
    ) {
        String direction = BUY.equals(side) ? "desc" : "asc";
        return jdbcClient.sql(
                """
                select limit_price,
                       sum(quantity - filled_quantity) as remaining_quantity
                  from stock_order
                 where symbol = :symbol
                   and side = :side
                   and account_id <> :accountId
                   and market_type = 'ORDER_BOOK'
                   and order_type = 'LIMIT'
                   and status in ('PENDING', 'PARTIALLY_FILLED')
                   and limit_price is not null
                   and quantity > filled_quantity
                   and (
                       self_trade_group_id is null
                       or self_trade_group_id <> :selfTradeGroupId
                   )
                 group by limit_price
                 order by limit_price %s
                 limit :levelLimit
                """.formatted(direction)
        )
                .param("symbol", mandate.symbol())
                .param("side", side)
                .param("accountId", mandate.accountId())
                .param("selfTradeGroupId", selfTradeGroupId)
                .param("levelLimit", Math.max(1, mandate.externalDepthLevels()))
                .query((rs, rowNum) -> new DepthLevel(
                        rs.getBigDecimal("limit_price"),
                        rs.getLong("remaining_quantity")
                ))
                .list();
    }

    private long depthQuantity(List<DepthLevel> levels) {
        long total = 0L;
        for (DepthLevel level : levels) {
            total = saturatingAdd(total, Math.max(0L, level.quantity()));
        }
        return total;
    }

    private LiquidityProviderMandate mapMandate(ResultSet rs) throws SQLException {
        return new LiquidityProviderMandate(
                rs.getLong("id"),
                rs.getLong("participant_id"),
                rs.getLong("account_id"),
                rs.getString("symbol"),
                rs.getString("mandate_code"),
                rs.getString("execution_mode"),
                rs.getString("status"),
                toLocalDate(rs, "contract_start_date"),
                toLocalDate(rs, "contract_end_date"),
                rs.getInt("target_spread_ticks"),
                rs.getInt("max_spread_ticks"),
                rs.getLong("max_order_quantity"),
                rs.getLong("reference_daily_volume"),
                rs.getBigDecimal("target_open_participation_rate"),
                rs.getBigDecimal("max_open_participation_rate"),
                rs.getBigDecimal("max_single_order_participation_rate"),
                rs.getInt("external_depth_levels"),
                rs.getBigDecimal("max_external_depth_participation_rate"),
                rs.getBigDecimal("daily_execution_participation_rate"),
                rs.getBigDecimal("daily_submission_multiplier"),
                rs.getLong("target_inventory_quantity"),
                rs.getLong("inventory_band_quantity"),
                rs.getInt("inventory_skew_ticks"),
                rs.getBigDecimal("primary_regime_weight"),
                rs.getBigDecimal("liquidity_size_sensitivity"),
                rs.getInt("volatility_spread_max_ticks"),
                rs.getInt("price_regime_max_skew_ticks"),
                rs.getBoolean("passive_only"),
                rs.getInt("minimum_quote_lifetime_seconds"),
                rs.getInt("reprice_threshold_ticks"),
                rs.getInt("order_ttl_seconds"),
                rs.getInt("quote_interval_seconds"),
                rs.getBigDecimal("daily_loss_limit_amount"),
                toLocalDateTime(rs, "next_quote_at"),
                rs.getLong("policy_version")
        );
    }

    private LocalDate toLocalDate(ResultSet rs, String column) throws SQLException {
        java.sql.Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private LocalDateTime toLocalDateTime(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal pressure(double value) {
        return BigDecimal.valueOf(Math.clamp(value, -1.0, 1.0))
                .setScale(6, RoundingMode.HALF_UP);
    }

    private long saturatingAdd(long left, long right) {
        if (left < 0L || right < 0L) {
            throw new IllegalArgumentException("Liquidity state quantities must not be negative");
        }
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    record MandateReference(long id, String symbol) {
    }

    record OpenOrderLoad(List<AutoOrder> orders, boolean overflow) {
        OpenOrderLoad {
            orders = List.copyOf(orders);
        }
    }

    private record AccountRow(
            long id,
            String status,
            String participantCategory,
            String selfTradeGroupId,
            BigDecimal cashBalance
    ) {
        private static AccountRow missing(long accountId) {
            return new AccountRow(
                    accountId,
                    "MISSING",
                    null,
                    null,
                    BigDecimal.valueOf(-1L)
            );
        }
    }

    private record HoldingRow(
            long quantity,
            long reservedQuantity,
            BigDecimal averagePrice
    ) {
        private static final HoldingRow EMPTY = new HoldingRow(
                0L,
                0L,
                BigDecimal.ZERO.setScale(2)
        );
    }

    private record RoleRow(
            long participantId,
            String participantType,
            String participantStatus,
            String participantSelfTradeGroupId,
            String accountRole,
            String deskCode,
            String mappingStatus,
            LocalDate effectiveFrom,
            LocalDate effectiveTo
    ) {
        private static final RoleRow MISSING = new RoleRow(
                0L,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private record DepthLevel(BigDecimal price, long quantity) {
    }
}
