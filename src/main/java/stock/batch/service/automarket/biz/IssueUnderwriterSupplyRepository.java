package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoMarketDistributionBias;
import stock.batch.service.batch.automarket.model.AutoOrder;

@Component
class IssueUnderwriterSupplyRepository {

    static final int MAX_OPEN_ORDER_ROWS = 1;
    private static final BigDecimal REFERENCE_DAILY_VOLUME_FLOAT_RATE = new BigDecimal("0.030000");

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;

    IssueUnderwriterSupplyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(new NamedParameterJdbcTemplate(jdbcTemplate));
    }

    List<ContractReference> findCandidates(int limit) {
        return jdbcClient.sql(
                        """
                        select contract.id, contract.symbol
                          from stock_underwriting_contract contract
                         where contract.status = 'STABILIZING'
                            or exists (
                                select 1
                                  from stock_order_strategy_origin strategy_origin
                                  join stock_order open_order
                                    on open_order.id = strategy_origin.order_id
                                 where strategy_origin.underwriting_contract_id = contract.id
                                   and strategy_origin.origin_type = 'ISSUE_UNDERWRITER'
                                   and open_order.status in ('PENDING', 'PARTIALLY_FILLED')
                                   and open_order.quantity > open_order.filled_quantity
                            )
                         order by contract.id
                         limit :limit
                        """
                )
                .param("limit", Math.max(1, limit))
                .query((rs, rowNum) -> new ContractReference(
                        rs.getLong("id"),
                        rs.getString("symbol")
                ))
                .list();
    }

    Optional<ContractSnapshot> lockContract(long contractId) {
        return jdbcClient.sql(
                        """
                        select id, contract_code, symbol, participant_id, account_id,
                               total_issue_quantity, tradable_allocation_quantity,
                               locked_allocation_quantity, external_allocation_quantity,
                               underwritten_quantity, issue_price,
                               stabilization_start_date, stabilization_end_date,
                               stabilization_quantity_limit,
                               stabilization_amount_limit,
                               status, policy_version
                          from stock_underwriting_contract
                         where id = :contractId
                         for update
                        """
                )
                .param("contractId", contractId)
                .query((rs, rowNum) -> new ContractSnapshot(
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
                        money(rs.getBigDecimal("issue_price")),
                        toLocalDate(rs, "stabilization_start_date"),
                        toLocalDate(rs, "stabilization_end_date"),
                        rs.getLong("stabilization_quantity_limit"),
                        money(rs.getBigDecimal("stabilization_amount_limit")),
                        rs.getString("status"),
                        rs.getLong("policy_version")
                ))
                .optional();
    }

    Optional<AutoMarketConfig> findSafetyMarketConfig(String symbol) {
        return jdbcClient.sql(
                        """
                        select instrument.symbol, instrument.market,
                               instrument.tradable_shares, instrument.tick_size,
                               instrument.price_limit_rate,
                               price.current_price, price.previous_close
                          from stock_order_book_instrument instrument
                          join stock_price price on price.symbol = instrument.symbol
                         where instrument.symbol = :symbol
                        """
                )
                .param("symbol", symbol)
                .query((rs, rowNum) -> new AutoMarketConfig(
                        rs.getString("symbol"),
                        rs.getString("market"),
                        1,
                        600,
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

    AccountSnapshot lockAccountSnapshot(
            ContractSnapshot contract,
            LocalDate simulationTradeDate
    ) {
        AccountRow account = jdbcClient.sql(
                        """
                        select id, status, participant_category,
                               self_trade_group_id
                          from stock_account
                         where id = :accountId
                         for update
                        """
                )
                .param("accountId", contract.accountId())
                .query((rs, rowNum) -> new AccountRow(
                        rs.getLong("id"),
                        rs.getString("status"),
                        rs.getString("participant_category"),
                        rs.getString("self_trade_group_id")
                ))
                .optional()
                .orElse(AccountRow.missing(contract.accountId()));

        HoldingRow holding = jdbcClient.sql(
                        """
                        select quantity, reserved_quantity
                          from stock_holding
                         where account_id = :accountId
                           and symbol = :symbol
                         for update
                        """
                )
                .param("accountId", contract.accountId())
                .param("symbol", contract.symbol())
                .query((rs, rowNum) -> new HoldingRow(
                        rs.getLong("quantity"),
                        rs.getLong("reserved_quantity")
                ))
                .optional()
                .orElse(HoldingRow.EMPTY);

        RoleRow role = jdbcClient.sql(
                        """
                        select participant.id as participant_id,
                               participant.participant_type,
                               participant.status as participant_status,
                               participant.self_trade_group_id
                                   as participant_self_trade_group_id,
                               role_mapping.account_role,
                               role_mapping.status as mapping_status,
                               role_mapping.effective_from,
                               role_mapping.effective_to
                          from stock_market_participant participant
                          left join stock_market_participant_account role_mapping
                            on role_mapping.participant_id = participant.id
                           and role_mapping.account_id = :accountId
                         where participant.id = :participantId
                        """
                )
                .param("accountId", contract.accountId())
                .param("participantId", contract.participantId())
                .query((rs, rowNum) -> new RoleRow(
                        rs.getLong("participant_id"),
                        rs.getString("participant_type"),
                        rs.getString("participant_status"),
                        rs.getString("participant_self_trade_group_id"),
                        rs.getString("account_role"),
                        rs.getString("mapping_status"),
                        toLocalDate(rs, "effective_from"),
                        toLocalDate(rs, "effective_to")
                ))
                .optional()
                .orElse(RoleRow.MISSING);

        int nonContractOpenOrderCount = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_order open_order
                         where open_order.account_id = :accountId
                           and open_order.status in ('PENDING', 'PARTIALLY_FILLED')
                           and open_order.quantity > open_order.filled_quantity
                           and not exists (
                               select 1
                                 from stock_order_strategy_origin strategy_origin
                                where strategy_origin.order_id = open_order.id
                                  and strategy_origin.origin_type = 'ISSUE_UNDERWRITER'
                                  and strategy_origin.underwriting_contract_id = :contractId
                           )
                        """
                )
                .param("accountId", contract.accountId())
                .param("contractId", contract.id())
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
                .param("accountId", contract.accountId())
                .param("symbol", contract.symbol())
                .query(Integer.class)
                .single();
        SupplyReconciliation supplyReconciliation =
                findSupplyReconciliation(contract);
        return new AccountSnapshot(
                account.id(),
                account.status(),
                account.participantCategory(),
                account.selfTradeGroupId(),
                holding.quantity(),
                holding.reservedQuantity(),
                role.participantId(),
                role.participantType(),
                role.participantStatus(),
                role.participantSelfTradeGroupId(),
                role.accountRole(),
                role.mappingStatus(),
                role.effectiveFrom(),
                role.effectiveTo(),
                simulationTradeDate,
                nonContractOpenOrderCount,
                unmanagedHoldingCount,
                supplyReconciliation
        );
    }

    private SupplyReconciliation findSupplyReconciliation(
            ContractSnapshot contract
    ) {
        return jdbcClient.sql(
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
                .query((rs, rowNum) -> new SupplyReconciliation(
                        rs.getLong("issued_shares"),
                        rs.getLong("tradable_shares"),
                        rs.getLong("total_holding_quantity"),
                        rs.getLong("invalid_holding_count"),
                        rs.getLong("initial_ledger_quantity"),
                        rs.getLong("initial_tradable_ledger_quantity"),
                        rs.getLong("initial_locked_ledger_quantity")
                ))
                .optional()
                .orElse(SupplyReconciliation.MISSING);
    }

    OpenOrderLoad findOpenOrders(ContractSnapshot contract) {
        int reconciliationMismatchCount = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_order_strategy_origin strategy_origin
                          join stock_order open_order
                            on open_order.id = strategy_origin.order_id
                          join stock_account contract_account
                            on contract_account.id = :accountId
                         where strategy_origin.underwriting_contract_id = :contractId
                           and strategy_origin.origin_type = 'ISSUE_UNDERWRITER'
                           and open_order.status in ('PENDING', 'PARTIALLY_FILLED')
                           and open_order.quantity > open_order.filled_quantity
                           and (
                               open_order.account_id <> :accountId
                               or open_order.symbol <> :symbol
                               or open_order.origin_type is null
                               or open_order.origin_type <> 'ISSUE_UNDERWRITER'
                               or open_order.self_trade_group_id is null
                               or contract_account.self_trade_group_id is null
                               or open_order.self_trade_group_id
                                  <> contract_account.self_trade_group_id
                               or open_order.market_type <> 'ORDER_BOOK'
                               or open_order.order_type <> 'LIMIT'
                               or open_order.side <> 'SELL'
                               or strategy_origin.participant_id <> :participantId
                               or strategy_origin.policy_version <> :policyVersion
                           )
                        """
                )
                .param("contractId", contract.id())
                .param("accountId", contract.accountId())
                .param("symbol", contract.symbol())
                .param("participantId", contract.participantId())
                .param("policyVersion", contract.policyVersion())
                .query(Integer.class)
                .single();
        List<AutoOrder> orders = jdbcClient.sql(
                        """
                        select open_order.id, open_order.account_id, open_order.symbol,
                               open_order.side, open_order.quantity,
                               open_order.filled_quantity, open_order.reserved_cash,
                               open_order.limit_price, open_order.expires_at,
                               open_order.created_at
                          from stock_order_strategy_origin strategy_origin
                          join stock_order open_order
                            on open_order.id = strategy_origin.order_id
                         where strategy_origin.underwriting_contract_id = :contractId
                           and strategy_origin.origin_type = 'ISSUE_UNDERWRITER'
                           and open_order.account_id = :accountId
                           and open_order.symbol = :symbol
                           and open_order.origin_type = 'ISSUE_UNDERWRITER'
                           and open_order.market_type = 'ORDER_BOOK'
                           and open_order.order_type = 'LIMIT'
                           and open_order.status in ('PENDING', 'PARTIALLY_FILLED')
                           and open_order.quantity > open_order.filled_quantity
                         order by open_order.created_at, open_order.id
                         limit :limit
                        """
                )
                .param("contractId", contract.id())
                .param("accountId", contract.accountId())
                .param("symbol", contract.symbol())
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
        return new OpenOrderLoad(
                orders.size() > MAX_OPEN_ORDER_ROWS
                        ? List.copyOf(orders.subList(0, MAX_OPEN_ORDER_ROWS))
                        : List.copyOf(orders),
                orders.size() > MAX_OPEN_ORDER_ROWS,
                List.copyOf(orders),
                reconciliationMismatchCount
        );
    }

    ExternalBook findExternalBook(
            ContractSnapshot contract,
            String selfTradeGroupId
    ) {
        String resolvedGroup = selfTradeGroupId == null || selfTradeGroupId.isBlank()
                ? "ACCOUNT:" + contract.accountId()
                : selfTradeGroupId;
        BigDecimal bestBid = findBestExternalPrice(
                contract,
                "BUY",
                "desc",
                resolvedGroup
        );
        BigDecimal bestAsk = findBestExternalPrice(
                contract,
                "SELL",
                "asc",
                resolvedGroup
        );
        long topFiveBidDepth = jdbcClient.sql(
                        """
                        select coalesce(sum(level.remaining_quantity), 0)
                          from (
                              select limit_price,
                                     sum(quantity - filled_quantity)
                                         as remaining_quantity
                                from stock_order
                               where symbol = :symbol
                                 and side = 'BUY'
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
                               order by limit_price desc
                               limit 5
                          ) level
                        """
                )
                .param("symbol", contract.symbol())
                .param("accountId", contract.accountId())
                .param("selfTradeGroupId", resolvedGroup)
                .query(Long.class)
                .single();
        return new ExternalBook(bestBid, bestAsk, Math.max(0L, topFiveBidDepth));
    }

    private BigDecimal findBestExternalPrice(
            ContractSnapshot contract,
            String side,
            String direction,
            String selfTradeGroupId
    ) {
        return jdbcClient.sql(
                        """
                        select limit_price
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
                         order by limit_price %s, created_at, id
                         limit 1
                        """.formatted(direction)
                )
                .param("symbol", contract.symbol())
                .param("side", side)
                .param("accountId", contract.accountId())
                .param("selfTradeGroupId", selfTradeGroupId)
                .query(BigDecimal.class)
                .optional()
                .orElse(null);
    }

    long findReferenceDailyVolume(String symbol) {
        long tradableShares = jdbcClient.sql(
                        """
                        select tradable_shares
                          from stock_order_book_instrument
                         where symbol = :symbol
                        """
                )
                .param("symbol", symbol)
                .query(Long.class)
                .optional()
                .orElse(0L);
        if (tradableShares <= 0L) {
            return 0L;
        }
        return BigDecimal.valueOf(tradableShares)
                .multiply(REFERENCE_DAILY_VOLUME_FLOAT_RATE)
                .setScale(0, RoundingMode.DOWN)
                .max(BigDecimal.ONE)
                .longValueExact();
    }

    DailyState lockDailyState(
            long contractId,
            LocalDate simulationTradeDate
    ) {
        return jdbcClient.sql(
                        """
                        select simulation_trade_date, underwriting_contract_id,
                               reference_daily_volume, submission_quantity_limit,
                               submission_amount_limit, submitted_quantity,
                               submitted_amount, generated_order_count,
                               cancelled_order_count, last_order_price,
                               state_status, gate_reason, policy_version, version
                          from stock_underwriting_daily_supply_state
                         where simulation_trade_date = :simulationTradeDate
                           and underwriting_contract_id = :contractId
                         for update
                        """
                )
                .param("simulationTradeDate", simulationTradeDate)
                .param("contractId", contractId)
                .query((rs, rowNum) -> new DailyState(
                        true,
                        rs.getObject("simulation_trade_date", LocalDate.class),
                        rs.getLong("underwriting_contract_id"),
                        rs.getLong("reference_daily_volume"),
                        rs.getLong("submission_quantity_limit"),
                        money(rs.getBigDecimal("submission_amount_limit")),
                        rs.getLong("submitted_quantity"),
                        money(rs.getBigDecimal("submitted_amount")),
                        rs.getLong("generated_order_count"),
                        rs.getLong("cancelled_order_count"),
                        nullableMoney(rs.getBigDecimal("last_order_price")),
                        rs.getString("state_status"),
                        rs.getString("gate_reason"),
                        rs.getLong("policy_version"),
                        rs.getLong("version")
                ))
                .optional()
                .orElseGet(() -> DailyState.empty(simulationTradeDate, contractId));
    }

    SupplyUsage findSupplyUsage(long contractId) {
        return jdbcClient.sql(
                        """
                        select coalesce(sum(submitted_quantity), 0)
                                   as submitted_quantity,
                               coalesce(sum(submitted_amount), 0)
                                   as submitted_amount
                          from stock_underwriting_daily_supply_state
                         where underwriting_contract_id = :contractId
                        """
                )
                .param("contractId", contractId)
                .query((rs, rowNum) -> new SupplyUsage(
                        rs.getLong("submitted_quantity"),
                        money(rs.getBigDecimal("submitted_amount"))
                ))
                .single();
    }

    void persistDailyState(
            ContractSnapshot contract,
            DailyState previous,
            IssueUnderwriterSupplyPlanner.SupplyPlan plan,
            int generatedOrderCount,
            int cancelledOrderCount,
            LocalDateTime now
    ) {
        long submittedQuantity = Math.addExact(
                previous.submittedQuantity(),
                generatedOrderCount == 1 ? plan.submittedQuantity() : 0L
        );
        BigDecimal submittedAmount = previous.submittedAmount().add(
                generatedOrderCount == 1 ? plan.submittedAmount() : BigDecimal.ZERO
        );
        long totalGeneratedOrderCount = Math.addExact(
                previous.generatedOrderCount(),
                generatedOrderCount
        );
        long totalCancelledOrderCount = Math.addExact(
                previous.cancelledOrderCount(),
                cancelledOrderCount
        );
        BigDecimal lastOrderPrice = generatedOrderCount == 1
                ? plan.orderPrice()
                : previous.lastOrderPrice();
        int updated = jdbcTemplate.update(
                """
                update stock_underwriting_daily_supply_state
                   set reference_daily_volume = ?,
                       submission_quantity_limit = ?,
                       submission_amount_limit = ?,
                       submitted_quantity = ?,
                       submitted_amount = ?,
                       generated_order_count = ?,
                       cancelled_order_count = ?,
                       last_order_price = ?,
                       state_status = ?,
                       gate_reason = ?,
                       policy_version = ?,
                       version = version + 1,
                       updated_at = ?
                 where simulation_trade_date = ?
                   and underwriting_contract_id = ?
                """,
                plan.referenceDailyVolume(),
                plan.dailyQuantityLimit(),
                plan.dailyAmountLimit(),
                submittedQuantity,
                submittedAmount,
                totalGeneratedOrderCount,
                totalCancelledOrderCount,
                lastOrderPrice,
                plan.stateStatus(),
                plan.gateReason(),
                contract.policyVersion(),
                now,
                previous.simulationTradeDate(),
                contract.id()
        );
        if (updated == 1) {
            return;
        }
        if (updated != 0 || previous.persisted()) {
            throw new IllegalStateException(
                    "Issue-underwriter daily state update count mismatch: " + updated
            );
        }
        int inserted = jdbcTemplate.update(
                """
                insert into stock_underwriting_daily_supply_state(
                    simulation_trade_date, underwriting_contract_id,
                    reference_daily_volume, submission_quantity_limit,
                    submission_amount_limit, submitted_quantity, submitted_amount,
                    generated_order_count, cancelled_order_count, last_order_price,
                    state_status, gate_reason, policy_version, version,
                    created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """,
                previous.simulationTradeDate(),
                contract.id(),
                plan.referenceDailyVolume(),
                plan.dailyQuantityLimit(),
                plan.dailyAmountLimit(),
                submittedQuantity,
                submittedAmount,
                totalGeneratedOrderCount,
                totalCancelledOrderCount,
                lastOrderPrice,
                plan.stateStatus(),
                plan.gateReason(),
                contract.policyVersion(),
                now,
                now
        );
        if (inserted != 1) {
            throw new IllegalStateException(
                    "Issue-underwriter daily state insert count mismatch: " + inserted
            );
        }
    }

    void completeContract(ContractSnapshot contract, LocalDateTime now) {
        int updated = jdbcTemplate.update(
                """
                update stock_underwriting_contract
                   set status = 'COMPLETED',
                       updated_at = ?
                 where id = ?
                   and status = 'STABILIZING'
                """,
                now,
                contract.id()
        );
        if (updated != 1) {
            throw new IllegalStateException(
                    "Issue-underwriter contract completion count mismatch: " + updated
            );
        }
        int retiredPolicy = jdbcTemplate.update(
                """
                update stock_market_policy_version
                   set status = 'RETIRED',
                       updated_at = ?
                 where policy_scope = 'UNDERWRITING_CONTRACT'
                   and scope_key = ?
                   and version_no = ?
                   and status = 'ACTIVE'
                """,
                now,
                contract.contractCode(),
                contract.policyVersion()
        );
        if (retiredPolicy != 1) {
            throw new IllegalStateException(
                    "Issue-underwriter policy retirement count mismatch: "
                            + retiredPolicy
            );
        }
    }

    record ContractReference(long id, String symbol) {
    }

    record ContractSnapshot(
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
            LocalDate stabilizationStartDate,
            LocalDate stabilizationEndDate,
            long stabilizationQuantityLimit,
            BigDecimal stabilizationAmountLimit,
            String status,
            long policyVersion
    ) {
        boolean active() {
            return "STABILIZING".equals(status);
        }
    }

    record AccountSnapshot(
            long accountId,
            String accountStatus,
            String participantCategory,
            String accountSelfTradeGroupId,
            long holdingQuantity,
            long reservedQuantity,
            long participantId,
            String participantType,
            String participantStatus,
            String participantSelfTradeGroupId,
            String accountRole,
            String mappingStatus,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            LocalDate simulationTradeDate,
            int nonContractOpenOrderCount,
            int unmanagedHoldingCount,
            SupplyReconciliation supplyReconciliation
    ) {
        boolean roleEligible(ContractSnapshot contract) {
            boolean datesActive = effectiveFrom != null
                    && !simulationTradeDate.isBefore(effectiveFrom)
                    && (effectiveTo == null || !simulationTradeDate.isAfter(effectiveTo));
            return accountId == contract.accountId()
                    && participantId == contract.participantId()
                    && "ACTIVE".equals(accountStatus)
                    && "ACTIVE".equals(participantStatus)
                    && "ACTIVE".equals(mappingStatus)
                    && "ISSUE_UNDERWRITER".equals(participantCategory)
                    && "ISSUE_UNDERWRITER".equals(participantType)
                    && "ISSUE_UNDERWRITER".equals(accountRole)
                    && participantSelfTradeGroupId != null
                    && participantSelfTradeGroupId.equals(accountSelfTradeGroupId)
                    && datesActive
                    && holdingQuantity >= 0L
                    && reservedQuantity >= 0L
                    && reservedQuantity <= holdingQuantity
                    && nonContractOpenOrderCount == 0
                    && unmanagedHoldingCount == 0;
        }

        long availableQuantity() {
            return Math.max(0L, holdingQuantity - reservedQuantity);
        }

        boolean supplyReconciled(ContractSnapshot contract) {
            return supplyReconciliation.matches(contract);
        }
    }

    record OpenOrderLoad(
            List<AutoOrder> retainedRows,
            boolean overflow,
            List<AutoOrder> allRows,
            int reconciliationMismatchCount
    ) {
    }

    record ExternalBook(
            BigDecimal bestBid,
            BigDecimal bestAsk,
            long topFiveBidDepth
    ) {
    }

    record DailyState(
            boolean persisted,
            LocalDate simulationTradeDate,
            long contractId,
            long referenceDailyVolume,
            long submissionQuantityLimit,
            BigDecimal submissionAmountLimit,
            long submittedQuantity,
            BigDecimal submittedAmount,
            long generatedOrderCount,
            long cancelledOrderCount,
            BigDecimal lastOrderPrice,
            String stateStatus,
            String gateReason,
            long policyVersion,
            long version
    ) {
        static DailyState empty(LocalDate simulationTradeDate, long contractId) {
            return new DailyState(
                    false,
                    simulationTradeDate,
                    contractId,
                    0L,
                    0L,
                    BigDecimal.ZERO.setScale(2),
                    0L,
                    BigDecimal.ZERO.setScale(2),
                    0L,
                    0L,
                    null,
                    "GATED",
                    "NOT_RUN",
                    1L,
                    0L
            );
        }
    }

    record SupplyUsage(long submittedQuantity, BigDecimal submittedAmount) {
    }

    record SupplyReconciliation(
            long issuedShares,
            long tradableShares,
            long totalHoldingQuantity,
            long invalidHoldingCount,
            long initialLedgerQuantity,
            long initialTradableLedgerQuantity,
            long initialLockedLedgerQuantity
    ) {
        private static final SupplyReconciliation MISSING =
                new SupplyReconciliation(-1L, -1L, -1L, 1L, -1L, -1L, -1L);

        boolean matches(ContractSnapshot contract) {
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

    private record AccountRow(
            long id,
            String status,
            String participantCategory,
            String selfTradeGroupId
    ) {
        static AccountRow missing(long accountId) {
            return new AccountRow(accountId, "MISSING", null, null);
        }
    }

    private record HoldingRow(long quantity, long reservedQuantity) {
        private static final HoldingRow EMPTY = new HoldingRow(0L, 0L);
    }

    private record RoleRow(
            long participantId,
            String participantType,
            String participantStatus,
            String participantSelfTradeGroupId,
            String accountRole,
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
                null
        );
    }

    private LocalDate toLocalDate(java.sql.ResultSet rs, String column)
            throws SQLException {
        java.sql.Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private LocalDateTime toLocalDateTime(java.sql.ResultSet rs, String column)
            throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal nullableMoney(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }
}
