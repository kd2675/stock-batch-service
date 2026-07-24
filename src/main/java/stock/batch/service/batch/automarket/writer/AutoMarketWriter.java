package stock.batch.service.batch.automarket.writer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import stock.batch.service.batch.automarket.model.AutoOrder;
import stock.batch.service.batch.automarket.model.AutoParticipantCashDeposit;
import stock.batch.service.batch.automarket.model.AutoParticipantFundingBudgetGrant;
import stock.batch.service.execution.queue.OrderBookReadySymbolQueue;

@Component
public class AutoMarketWriter {

    public static final int MAX_LIMIT_ORDER_INSERT_ROWS = 800;

    private final JdbcTemplate jdbcTemplate;
    private final OrderBookReadySymbolQueue readySymbolQueue;
    private final Counter orderInsertFailureCounter;

    public AutoMarketWriter(
            JdbcTemplate jdbcTemplate,
            OrderBookReadySymbolQueue readySymbolQueue,
            MeterRegistry meterRegistry
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.readySymbolQueue = readySymbolQueue;
        this.orderInsertFailureCounter = meterRegistry.counter("stock.auto.market.order.insert.failures");
    }

    /**
     * Locks a planned-order account cohort in one primary-key ordered round trip. Both BUY and
     * SELL accounts are included so every order-creation path follows the execution engine's
     * account -> holding -> order lock order. In particular, a SELL-only chunk must not lock a
     * holding first and later wait for the parent-account foreign-key check during order insert.
     */
    public void lockAccountsForUpdate(List<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return;
        }
        List<Long> orderedAccountIds = accountIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (orderedAccountIds.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", Collections.nCopies(orderedAccountIds.size(), "?"));
        jdbcTemplate.queryForList(
                "select id from stock_account where id in (%s) order by id asc for update"
                        .formatted(placeholders),
                Long.class,
                orderedAccountIds.toArray()
        );
    }

    public Map<Long, AccountReservationState> lockAccountReservationStatesForUpdate(List<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return Map.of();
        }
        List<Long> orderedAccountIds = accountIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (orderedAccountIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(orderedAccountIds.size(), "?"));
        Map<Long, AccountReservationState> states = new LinkedHashMap<>();
        jdbcTemplate.query(
                """
                select id, status, cash_balance
                  from stock_account
                 where id in (%s)
                 order by id asc
                 for update
                """.formatted(placeholders),
                resultSet -> {
                    long accountId = resultSet.getLong("id");
                    states.put(accountId, new AccountReservationState(
                            accountId,
                            resultSet.getString("status"),
                            resultSet.getBigDecimal("cash_balance")
                    ));
                },
                orderedAccountIds.toArray()
        );
        return Map.copyOf(states);
    }

    public Map<HoldingReservationKey, HoldingReservationState> lockHoldingReservationStatesForUpdate(
            List<HoldingReservationKey> requestedKeys
    ) {
        if (requestedKeys == null || requestedKeys.isEmpty()) {
            return Map.of();
        }
        List<HoldingReservationKey> keys = requestedKeys.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparingLong(HoldingReservationKey::accountId)
                        .thenComparing(HoldingReservationKey::symbol))
                .toList();
        if (keys.isEmpty()) {
            return Map.of();
        }
        String predicates = String.join(" or ", Collections.nCopies(
                keys.size(),
                "(account_id = ? and symbol = ?)"
        ));
        List<Object> parameters = new ArrayList<>(keys.size() * 2);
        for (HoldingReservationKey key : keys) {
            parameters.add(key.accountId());
            parameters.add(key.symbol());
        }
        Map<HoldingReservationKey, HoldingReservationState> states = new TreeMap<>(
                Comparator.comparingLong(HoldingReservationKey::accountId)
                        .thenComparing(HoldingReservationKey::symbol)
        );
        jdbcTemplate.query(
                """
                select account_id, symbol, quantity, reserved_quantity
                  from stock_holding
                 where %s
                 order by account_id asc, symbol asc
                 for update
                """.formatted(predicates),
                resultSet -> {
                    HoldingReservationKey key = new HoldingReservationKey(
                            resultSet.getLong("account_id"),
                            resultSet.getString("symbol")
                    );
                    states.put(key, new HoldingReservationState(
                            key,
                            resultSet.getLong("quantity"),
                            resultSet.getLong("reserved_quantity")
                    ));
                },
                parameters.toArray()
        );
        return Map.copyOf(states);
    }

    public void lockSellHoldingsForUpdate(List<AutoOrder> orders) {
        List<HoldingReservationKey> keys = orders.stream()
                .filter(order -> "SELL".equals(order.side()))
                .map(order -> new HoldingReservationKey(order.accountId(), order.symbol()))
                .distinct()
                .sorted(Comparator.comparingLong(HoldingReservationKey::accountId)
                        .thenComparing(HoldingReservationKey::symbol))
                .toList();
        if (keys.isEmpty()) {
            return;
        }
        String predicates = String.join(" or ", Collections.nCopies(
                keys.size(),
                "(account_id = ? and symbol = ?)"
        ));
        List<Object> parameters = new ArrayList<>(keys.size() * 2);
        for (HoldingReservationKey key : keys) {
            parameters.add(key.accountId());
            parameters.add(key.symbol());
        }
        int lockedCount = jdbcTemplate.queryForList(
                """
                select id
                  from stock_holding
                 where %s
                 order by account_id asc, symbol asc
                 for update
                """.formatted(predicates),
                Long.class,
                parameters.toArray()
        ).size();
        requireChunkCount("auto-order expiry holding lock", keys.size(), lockedCount);
    }

    public int depositCashFlowChunk(
            List<AutoParticipantCashDeposit> deposits,
            String createdBy,
            LocalDateTime createdAt
    ) {
        if (deposits.isEmpty()) {
            return 0;
        }
        List<AutoParticipantCashDeposit> orderedDeposits = deposits.stream()
                .sorted(java.util.Comparator.comparingLong(AutoParticipantCashDeposit::accountId))
                .toList();
        long uniqueAccountCount = orderedDeposits.stream()
                .map(AutoParticipantCashDeposit::accountId)
                .distinct()
                .count();
        if (uniqueAccountCount != orderedDeposits.size()) {
            throw new IllegalArgumentException("Recurring cash chunk contains duplicate account ids");
        }

        String accountPlaceholders = String.join(",", Collections.nCopies(orderedDeposits.size(), "?"));
        List<Object> accountIds = orderedDeposits.stream()
                .map(AutoParticipantCashDeposit::accountId)
                .map(Object.class::cast)
                .toList();
        int lockedAccountCount = jdbcTemplate.queryForList(
                "select id from stock_account where id in (%s) order by id asc for update"
                        .formatted(accountPlaceholders),
                Long.class,
                accountIds.toArray()
        ).size();
        requireChunkCount("recurring cash account lock", orderedDeposits.size(), lockedAccountCount);

        String amountCases = String.join(" ", Collections.nCopies(orderedDeposits.size(), "when ? then ?"));
        List<Object> updateParameters = new ArrayList<>(orderedDeposits.size() * 3 + 1);
        for (AutoParticipantCashDeposit deposit : orderedDeposits) {
            updateParameters.add(deposit.accountId());
            updateParameters.add(deposit.amount());
        }
        updateParameters.add(createdAt);
        updateParameters.addAll(accountIds);
        int updatedAccountCount = jdbcTemplate.update(
                """
                update stock_account
                   set cash_balance = cash_balance + case id %s else 0 end,
                       updated_at = ?
                 where id in (%s)
                """.formatted(amountCases, accountPlaceholders),
                updateParameters.toArray()
        );
        requireChunkCount("recurring cash account update", orderedDeposits.size(), updatedAccountCount);

        String cashFlowValues = String.join(",", Collections.nCopies(
                orderedDeposits.size(),
                "(?, 'DEPOSIT', ?, ?, ?, ?)"
        ));
        List<Object> cashFlowParameters = new ArrayList<>(orderedDeposits.size() * 5);
        for (AutoParticipantCashDeposit deposit : orderedDeposits) {
            cashFlowParameters.add(deposit.accountId());
            cashFlowParameters.add(deposit.amount());
            cashFlowParameters.add(deposit.reason());
            cashFlowParameters.add(createdBy);
            cashFlowParameters.add(createdAt);
        }
        int insertedCashFlowCount = jdbcTemplate.update(
                """
                insert into stock_account_cash_flow(
                    account_id, flow_type, amount, reason, created_by, created_at
                ) values %s
                """.formatted(cashFlowValues),
                cashFlowParameters.toArray()
        );
        requireChunkCount("recurring cash-flow insert", orderedDeposits.size(), insertedCashFlowCount);
        return orderedDeposits.size();
    }

    public int grantPaydayFundingBudgets(
            List<AutoParticipantFundingBudgetGrant> grants,
            LocalDate expiresBusinessDate,
            LocalDateTime createdAt
    ) {
        if (grants.isEmpty()) {
            return 0;
        }
        String values = String.join(",", Collections.nCopies(
                grants.size(),
                "(?, 'PAYDAY', ?, null, null, null, ?, ?, 0, 0, ?, 'ACTIVE', ?, ?)"
        ));
        List<Object> parameters = new ArrayList<>(grants.size() * 6);
        for (AutoParticipantFundingBudgetGrant grant : grants) {
            parameters.add(grant.accountId());
            parameters.add(grant.sourceKey());
            parameters.add(grant.amount());
            parameters.add(grant.amount());
            parameters.add(expiresBusinessDate);
            parameters.add(createdAt);
            parameters.add(createdAt);
        }
        int inserted = jdbcTemplate.update(
                """
                insert into stock_auto_participant_funding_budget(
                    account_id, budget_type, source_key, source_symbol,
                    corporate_action_id, corporate_action_entitlement_id,
                    granted_amount, available_amount, reserved_amount, spent_amount,
                    expires_business_date, status, created_at, updated_at
                ) values %s
                """.formatted(values),
                parameters.toArray()
        );
        requireChunkCount("payday funding budget insert", grants.size(), inserted);
        return inserted;
    }

    private void requireChunkCount(String operation, int expectedCount, int actualCount) {
        if (expectedCount != actualCount) {
            throw new IllegalStateException(
                    "%s count mismatch: expected=%d, actual=%d"
                            .formatted(operation, expectedCount, actualCount)
            );
        }
    }

    public int cancelOpenOrders(List<AutoOrder> orders, LocalDateTime cancelledAt) {
        List<Long> orderIds = orders.stream().map(AutoOrder::id).distinct().sorted().toList();
        if (orderIds.isEmpty()) {
            return 0;
        }
        if (orderIds.size() != orders.size()) {
            throw new IllegalArgumentException("Auto-order expiry chunk contains duplicate order ids");
        }
        String placeholders = String.join(",", Collections.nCopies(orderIds.size(), "?"));
        List<Object> parameters = new ArrayList<>(orderIds.size() + 1);
        parameters.add(cancelledAt);
        parameters.addAll(orderIds);
        return jdbcTemplate.update(
                """
                update stock_order
                   set status = 'CANCELLED',
                       reserved_cash = 0,
                       updated_at = ?
                 where id in (%s)
                   and status in ('PENDING', 'PARTIALLY_FILLED')
                """.formatted(placeholders),
                parameters.toArray()
        );
    }

    public int creditCancelledBuyReservations(List<AutoOrder> orders, LocalDateTime updatedAt) {
        Map<Long, BigDecimal> cashByAccountId = new TreeMap<>();
        for (AutoOrder order : orders) {
            if ("BUY".equals(order.side()) && order.reservedCash().compareTo(BigDecimal.ZERO) > 0) {
                cashByAccountId.merge(order.accountId(), order.reservedCash(), BigDecimal::add);
            }
        }
        if (cashByAccountId.isEmpty()) {
            return 0;
        }
        String cashCases = String.join(" ", Collections.nCopies(cashByAccountId.size(), "when ? then ?"));
        String placeholders = String.join(",", Collections.nCopies(cashByAccountId.size(), "?"));
        List<Object> parameters = new ArrayList<>(cashByAccountId.size() * 3 + 1);
        cashByAccountId.forEach((accountId, cash) -> {
            parameters.add(accountId);
            parameters.add(cash);
        });
        parameters.add(updatedAt);
        parameters.addAll(cashByAccountId.keySet());
        int updatedCount = jdbcTemplate.update(
                """
                update stock_account
                   set cash_balance = cash_balance + case id %s else 0 end,
                       updated_at = ?
                 where id in (%s)
                """.formatted(cashCases, placeholders),
                parameters.toArray()
        );
        requireChunkCount("auto-order expiry buy reservation release", cashByAccountId.size(), updatedCount);
        return updatedCount;
    }

    public int releaseCancelledSellReservations(List<AutoOrder> orders, LocalDateTime updatedAt) {
        Map<HoldingReservationKey, Long> quantityByHolding = new TreeMap<>(
                Comparator.comparingLong(HoldingReservationKey::accountId)
                        .thenComparing(HoldingReservationKey::symbol)
        );
        for (AutoOrder order : orders) {
            long remainingQuantity = order.quantity() - order.filledQuantity();
            if ("SELL".equals(order.side()) && remainingQuantity > 0) {
                quantityByHolding.merge(
                        new HoldingReservationKey(order.accountId(), order.symbol()),
                        remainingQuantity,
                        Long::sum
                );
            }
        }
        if (quantityByHolding.isEmpty()) {
            return 0;
        }
        String quantityCases = String.join(" ", Collections.nCopies(
                quantityByHolding.size(),
                "when account_id = ? and symbol = ? then ?"
        ));
        String predicates = String.join(" or ", Collections.nCopies(
                quantityByHolding.size(),
                "(account_id = ? and symbol = ?)"
        ));
        List<Object> parameters = new ArrayList<>(quantityByHolding.size() * 5 + 1);
        quantityByHolding.forEach((key, quantity) -> {
            parameters.add(key.accountId());
            parameters.add(key.symbol());
            parameters.add(quantity);
        });
        parameters.add(updatedAt);
        quantityByHolding.keySet().forEach(key -> {
            parameters.add(key.accountId());
            parameters.add(key.symbol());
        });
        int updatedCount = jdbcTemplate.update(
                """
                update stock_holding
                   set reserved_quantity = greatest(
                           0,
                           reserved_quantity - case %s else 0 end
                       ),
                       updated_at = ?
                 where %s
                """.formatted(quantityCases, predicates),
                parameters.toArray()
        );
        requireChunkCount("auto-order expiry sell reservation release", quantityByHolding.size(), updatedCount);
        return updatedCount;
    }

    public int reserveBuyCashChunk(Map<Long, BigDecimal> reservationByAccountId, LocalDateTime updatedAt) {
        if (reservationByAccountId == null || reservationByAccountId.isEmpty()) {
            return 0;
        }
        Map<Long, BigDecimal> orderedReservations = new TreeMap<>(reservationByAccountId);
        orderedReservations.forEach((accountId, amount) -> {
            if (accountId == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Buy reservation chunk requires positive account amounts");
            }
        });
        String amountCases = String.join(" ", Collections.nCopies(
                orderedReservations.size(),
                "when ? then ?"
        ));
        String eligiblePredicates = String.join(" or ", Collections.nCopies(
                orderedReservations.size(),
                "(id = ? and status = 'ACTIVE' and cash_balance >= ?)"
        ));
        List<Object> parameters = new ArrayList<>(orderedReservations.size() * 4 + 1);
        orderedReservations.forEach((accountId, amount) -> {
            parameters.add(accountId);
            parameters.add(amount);
        });
        parameters.add(updatedAt);
        orderedReservations.forEach((accountId, amount) -> {
            parameters.add(accountId);
            parameters.add(amount);
        });
        int updatedCount = jdbcTemplate.update(
                """
                update stock_account
                   set cash_balance = cash_balance - case id %s else 0 end,
                       updated_at = ?
                 where %s
                """.formatted(amountCases, eligiblePredicates),
                parameters.toArray()
        );
        requireChunkCount("auto-order buy reservation", orderedReservations.size(), updatedCount);
        return updatedCount;
    }

    public int reserveSellQuantityChunk(
            Map<HoldingReservationKey, Long> reservationByHolding,
            LocalDateTime updatedAt
    ) {
        if (reservationByHolding == null || reservationByHolding.isEmpty()) {
            return 0;
        }
        Map<HoldingReservationKey, Long> orderedReservations = new TreeMap<>(
                Comparator.comparingLong(HoldingReservationKey::accountId)
                        .thenComparing(HoldingReservationKey::symbol)
        );
        orderedReservations.putAll(reservationByHolding);
        orderedReservations.forEach((key, quantity) -> {
            if (key == null || quantity == null || quantity <= 0) {
                throw new IllegalArgumentException("Sell reservation chunk requires positive holding quantities");
            }
        });
        String quantityCases = String.join(" ", Collections.nCopies(
                orderedReservations.size(),
                "when account_id = ? and symbol = ? then ?"
        ));
        String eligiblePredicates = String.join(" or ", Collections.nCopies(
                orderedReservations.size(),
                "(account_id = ? and symbol = ? and quantity - reserved_quantity >= ?)"
        ));
        List<Object> parameters = new ArrayList<>(orderedReservations.size() * 6 + 1);
        orderedReservations.forEach((key, quantity) -> {
            parameters.add(key.accountId());
            parameters.add(key.symbol());
            parameters.add(quantity);
        });
        parameters.add(updatedAt);
        orderedReservations.forEach((key, quantity) -> {
            parameters.add(key.accountId());
            parameters.add(key.symbol());
            parameters.add(quantity);
        });
        int updatedCount = jdbcTemplate.update(
                """
                update stock_holding
                   set reserved_quantity = reserved_quantity + case %s else 0 end,
                       updated_at = ?
                 where %s
                """.formatted(quantityCases, eligiblePredicates),
                parameters.toArray()
        );
        requireChunkCount("auto-order sell reservation", orderedReservations.size(), updatedCount);
        return updatedCount;
    }

    public int insertLimitOrders(List<LimitOrderInsert> orders, LocalDateTime createdAt) {
        if (orders.isEmpty()) {
            return 0;
        }
        if (orders.size() > MAX_LIMIT_ORDER_INSERT_ROWS) {
            throw new IllegalArgumentException(
                    "Auto-order multi-row insert exceeds the maximum row count: %d > %d"
                            .formatted(orders.size(), MAX_LIMIT_ORDER_INSERT_ROWS)
            );
        }
        String values = String.join(",", Collections.nCopies(
                orders.size(),
                "(?, ?, ?, 'ORDER_BOOK', ?, 'LIMIT', 'PENDING', ?, ?, 0, null, ?, ?, ?, ?, ?, ?, ?)"
        ));
        List<Object> parameters = new ArrayList<>(orders.size() * 13);
        for (LimitOrderInsert order : orders) {
            parameters.add(order.clientOrderId());
            parameters.add(order.accountId());
            parameters.add(order.symbol());
            parameters.add(order.side());
            parameters.add(order.price());
            parameters.add(order.quantity());
            parameters.add(order.reservedCash());
            parameters.add(order.fundingBudgetType());
            parameters.add(order.expiresAt());
            parameters.add(order.autoProfileType());
            parameters.add(order.autoBehaviorModelVersion());
            parameters.add(createdAt);
            parameters.add(createdAt);
        }
        int insertedCount;
        try {
            insertedCount = jdbcTemplate.update(
                    """
                    insert into stock_order(
                        client_order_id, account_id, symbol, market_type, side, order_type, status,
                        limit_price, quantity, filled_quantity, average_fill_price,
                        reserved_cash, funding_budget_type, expires_at, auto_profile_type,
                        auto_behavior_model_version, created_at, updated_at
                    )
                    values %s
                    """.formatted(values),
                    parameters.toArray()
            );
        } catch (RuntimeException ex) {
            orderInsertFailureCounter.increment();
            throw ex;
        }
        if (insertedCount > 0) {
            enqueueInsertedSymbolsAfterCommit(orders);
        }
        return insertedCount;
    }

    public int markAverageDownDecisions(
            List<PositionStateKey> positions,
            LocalDate businessDate,
            LocalDateTime updatedAt
    ) {
        if (positions == null || positions.isEmpty()) {
            return 0;
        }
        List<PositionStateKey> orderedPositions = positions.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparingLong(PositionStateKey::accountId).thenComparing(PositionStateKey::symbol))
                .toList();
        if (orderedPositions.isEmpty()) {
            return 0;
        }
        if (orderedPositions.size() > MAX_LIMIT_ORDER_INSERT_ROWS) {
            throw new IllegalArgumentException(
                    "Average-down position-state update exceeds the maximum row count: %d > %d"
                            .formatted(orderedPositions.size(), MAX_LIMIT_ORDER_INSERT_ROWS)
            );
        }
        String predicates = String.join(" or ", Collections.nCopies(
                orderedPositions.size(),
                "(account_id = ? and symbol = ?)"
        ));
        List<Object> parameters = new ArrayList<>(orderedPositions.size() * 2 + 2);
        parameters.add(businessDate);
        parameters.add(updatedAt);
        orderedPositions.forEach(position -> {
                    parameters.add(position.accountId());
                    parameters.add(position.symbol());
                });
        int updatedCount = jdbcTemplate.update(
                "update stock_auto_participant_position_state "
                        + "set last_average_down_business_date = ?, updated_at = ? "
                        + "where " + predicates,
                parameters.toArray()
        );
        if (updatedCount == orderedPositions.size()) {
            return updatedCount;
        }

        List<Object> insertParameters = new ArrayList<>(orderedPositions.size() * 2 + 4);
        insertParameters.add(businessDate);
        insertParameters.add(businessDate);
        insertParameters.add(businessDate);
        insertParameters.add(updatedAt);
        orderedPositions.forEach(position -> {
            insertParameters.add(position.accountId());
            insertParameters.add(position.symbol());
        });
        int insertedCount = jdbcTemplate.update(
                """
                insert into stock_auto_participant_position_state(
                    account_id, symbol, position_opened_business_date, holding_trading_days,
                    average_down_rounds, last_average_down_business_date, peak_close_price,
                    last_seen_business_date, updated_at
                )
                select h.account_id, h.symbol, ?, 1, 0, ?, h.average_price, ?, ?
                  from stock_holding h
                 where (%s)
                   and not exists (
                       select 1
                         from stock_auto_participant_position_state s
                        where s.account_id = h.account_id
                          and s.symbol = h.symbol
                   )
                """.formatted(predicates),
                insertParameters.toArray()
        );
        return updatedCount + insertedCount;
    }

    private void enqueueInsertedSymbolsAfterCommit(List<LimitOrderInsert> orders) {
        Set<String> symbols = orders.stream()
                .map(LimitOrderInsert::symbol)
                .collect(Collectors.toSet());
        Runnable enqueueAction = () -> readySymbolQueue.enqueueAll(symbols);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            enqueueAction.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                enqueueAction.run();
            }
        });
    }

    public record AccountReservationState(long accountId, String status, BigDecimal cashBalance) {
    }

    public record HoldingReservationKey(long accountId, String symbol) {
    }

    public record PositionStateKey(long accountId, String symbol) {
    }

    public record HoldingReservationState(
            HoldingReservationKey key,
            long quantity,
            long reservedQuantity
    ) {

        public long availableQuantity() {
            return Math.max(0L, quantity - reservedQuantity);
        }
    }

    public record LimitOrderInsert(
            String clientOrderId,
            long accountId,
            String symbol,
            String side,
            BigDecimal price,
            long quantity,
            BigDecimal reservedCash,
            String fundingBudgetType,
            LocalDateTime expiresAt,
            String autoProfileType,
            String autoBehaviorModelVersion
    ) {
        public LimitOrderInsert(
                String clientOrderId,
                long accountId,
                String symbol,
                String side,
                BigDecimal price,
                long quantity,
                BigDecimal reservedCash
        ) {
            this(clientOrderId, accountId, symbol, side, price, quantity, reservedCash, null, null, null, null);
        }

        public LimitOrderInsert(
                String clientOrderId,
                long accountId,
                String symbol,
                String side,
                BigDecimal price,
                long quantity,
                BigDecimal reservedCash,
                String fundingBudgetType
        ) {
            this(
                    clientOrderId,
                    accountId,
                    symbol,
                    side,
                    price,
                    quantity,
                    reservedCash,
                    fundingBudgetType,
                    null,
                    null,
                    null
            );
        }
    }
}
