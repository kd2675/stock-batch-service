package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import stock.batch.service.batch.automarket.model.AutoParticipantFundingBudgetType;

@Component
public class AutoParticipantFundingBudgetService {

    private static final int MAX_RESERVATION_ALLOCATIONS = 2_000;
    private static final int MAX_RELEASE_ORDER_IDS = 5_000;
    private static final int MAX_RECONCILIATION_CHUNK_SIZE = 1_000;

    private final JdbcTemplate jdbcTemplate;

    public AutoParticipantFundingBudgetService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void consumeOrderBudget(
            long orderId,
            BigDecimal releasedOrderReservation,
            BigDecimal actualCost,
            LocalDateTime executedAt
    ) {
        // Purpose budgets fund at most the cash reserved when the order was accepted.
        // A later fee or adverse-price shortfall is ordinary account cash and has
        // already been validated and charged by the execution transaction.
        BigDecimal remainingReservation = nonNegative(releasedOrderReservation);
        if (remainingReservation.signum() <= 0) {
            return;
        }
        List<LockedOrderAllocation> allocations = lockOrderAllocations(List.of(orderId));
        if (allocations.isEmpty()) {
            throw new IllegalStateException("Funding-backed order has no budget allocation: orderId=" + orderId);
        }
        BigDecimal remainingCost = nonNegative(actualCost).min(remainingReservation);
        for (LockedOrderAllocation allocation : allocations) {
            if (remainingReservation.signum() <= 0) {
                break;
            }
            BigDecimal settled = allocation.remainingReservedAmount().min(remainingReservation);
            BigDecimal spent = settled.min(remainingCost);
            BigDecimal released = settled.subtract(spent);
            updateOrderAllocation(allocation, settled, spent, released, executedAt);
            updateBudgetAfterSettlement(allocation.budgetId(), settled, spent, released, executedAt);
            remainingReservation = remainingReservation.subtract(settled);
            remainingCost = remainingCost.subtract(spent);
        }
        if (remainingReservation.signum() != 0) {
            throw new IllegalStateException(
                    "Funding budget execution settlement mismatch: orderId=%d, unresolved=%s"
                            .formatted(orderId, remainingReservation)
            );
        }
    }

    @Transactional
    public int releaseCancelledOrderBudgets(List<Long> orderIds, LocalDateTime releasedAt) {
        if (orderIds == null || orderIds.isEmpty()) {
            return 0;
        }
        if (releasedAt == null) {
            throw new IllegalArgumentException("Funding budget release time is required");
        }
        List<Long> normalizedOrderIds = orderIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (normalizedOrderIds.isEmpty()) {
            return 0;
        }
        if (normalizedOrderIds.size() > MAX_RELEASE_ORDER_IDS) {
            throw new IllegalArgumentException(
                    "Funding budget release order chunk exceeds %d: %d"
                            .formatted(MAX_RELEASE_ORDER_IDS, normalizedOrderIds.size())
            );
        }
        List<LockedOrderAllocation> allocations = lockOrderAllocations(normalizedOrderIds);
        int releasedAllocationCount = 0;
        for (LockedOrderAllocation allocation : allocations) {
            BigDecimal released = allocation.remainingReservedAmount();
            if (released.signum() <= 0) {
                continue;
            }
            updateOrderAllocation(allocation, released, BigDecimal.ZERO, released, releasedAt);
            updateBudgetAfterSettlement(
                    allocation.budgetId(),
                    released,
                    BigDecimal.ZERO,
                    released,
                    releasedAt
            );
            releasedAllocationCount++;
        }
        return releasedAllocationCount;
    }

    public FundingBudgetExpiryChunk expireUnusedBudgetChunk(
            LocalDate businessDate,
            LocalDateTime expiredAt,
            long afterBudgetId,
            int chunkSize
    ) {
        if (businessDate == null || expiredAt == null) {
            throw new IllegalArgumentException("Funding budget expiry date and time are required");
        }
        if (afterBudgetId < 0) {
            throw new IllegalArgumentException("Funding budget expiry checkpoint must not be negative");
        }
        if (chunkSize < 1 || chunkSize > MAX_RECONCILIATION_CHUNK_SIZE) {
            throw new IllegalArgumentException(
                    "Funding budget expiry chunk size must be between 1 and %d: %d"
                            .formatted(MAX_RECONCILIATION_CHUNK_SIZE, chunkSize)
            );
        }
        List<Long> budgetIds = jdbcTemplate.queryForList(
                """
                select id
                  from stock_auto_participant_funding_budget
                 where id > ?
                   and status = 'ACTIVE'
                   and expires_business_date is not null
                   and expires_business_date < ?
                   and reserved_amount = 0
                 order by id asc
                 limit ?
                """,
                Long.class,
                afterBudgetId,
                businessDate,
                chunkSize
        );
        if (budgetIds.isEmpty()) {
            return new FundingBudgetExpiryChunk(0, afterBudgetId, true);
        }
        String placeholders = String.join(",", Collections.nCopies(budgetIds.size(), "?"));
        List<Object> parameters = new ArrayList<>(budgetIds.size() + 2);
        parameters.add(expiredAt);
        parameters.add(businessDate);
        parameters.addAll(budgetIds);
        int updated = jdbcTemplate.update(
                """
                update stock_auto_participant_funding_budget
                   set status = 'EXPIRED',
                       updated_at = ?
                 where expires_business_date < ?
                   and status = 'ACTIVE'
                   and reserved_amount = 0
                   and id in (%s)
                """.formatted(placeholders),
                parameters.toArray()
        );
        if (updated <= 0) {
            throw new IllegalStateException(
                    "Funding budget expiry candidates changed without progress: candidateCount=" + budgetIds.size()
            );
        }
        return new FundingBudgetExpiryChunk(
                updated,
                budgetIds.getLast(),
                budgetIds.size() < chunkSize
        );
    }

    public FundingBudgetReconciliationChunk validateReconciliationChunk(long afterBudgetId, int chunkSize) {
        if (afterBudgetId < 0) {
            throw new IllegalArgumentException("Funding budget reconciliation checkpoint must not be negative");
        }
        if (chunkSize < 1 || chunkSize > MAX_RECONCILIATION_CHUNK_SIZE) {
            throw new IllegalArgumentException(
                    "Funding budget reconciliation chunk size must be between 1 and %d: %d"
                            .formatted(MAX_RECONCILIATION_CHUNK_SIZE, chunkSize)
            );
        }
        List<FundingBudgetReconciliationRow> rows = jdbcTemplate.query(
                """
                select b.id,
                       b.granted_amount,
                       b.available_amount,
                       b.reserved_amount,
                       b.spent_amount,
                       coalesce(sum(ob.allocated_amount), 0) as allocated_amount,
                       coalesce(sum(ob.remaining_reserved_amount), 0) as allocation_reserved_amount,
                       coalesce(sum(ob.spent_amount), 0) as allocation_spent_amount,
                       coalesce(sum(ob.released_amount), 0) as allocation_released_amount
                  from stock_auto_participant_funding_budget b
                  left join stock_auto_participant_order_budget ob on ob.budget_id = b.id
                 where b.id > ?
                 group by b.id,
                          b.granted_amount,
                          b.available_amount,
                          b.reserved_amount,
                          b.spent_amount
                 order by b.id asc
                 limit ?
                """,
                (rs, rowNum) -> new FundingBudgetReconciliationRow(
                        rs.getLong("id"),
                        rs.getBigDecimal("granted_amount"),
                        rs.getBigDecimal("available_amount"),
                        rs.getBigDecimal("reserved_amount"),
                        rs.getBigDecimal("spent_amount"),
                        rs.getBigDecimal("allocated_amount"),
                        rs.getBigDecimal("allocation_reserved_amount"),
                        rs.getBigDecimal("allocation_spent_amount"),
                        rs.getBigDecimal("allocation_released_amount")
                ),
                afterBudgetId,
                chunkSize
        );
        for (FundingBudgetReconciliationRow row : rows) {
            row.validate();
        }
        long lastBudgetId = rows.isEmpty() ? afterBudgetId : rows.getLast().budgetId();
        return new FundingBudgetReconciliationChunk(rows.size(), lastBudgetId, rows.size() < chunkSize);
    }

    private List<LockedOrderAllocation> lockOrderAllocations(List<Long> orderIds) {
        String placeholders = String.join(",", Collections.nCopies(orderIds.size(), "?"));
        return jdbcTemplate.query(
                """
                select ob.order_id,
                       ob.budget_id,
                       ob.remaining_reserved_amount
                  from stock_auto_participant_order_budget ob
                  join stock_auto_participant_funding_budget b on b.id = ob.budget_id
                 where ob.order_id in (%s)
                   and ob.remaining_reserved_amount > 0
                 order by ob.budget_id asc, ob.order_id asc
                 for update
                """.formatted(placeholders),
                (rs, rowNum) -> new LockedOrderAllocation(
                        rs.getLong("order_id"),
                        rs.getLong("budget_id"),
                        rs.getBigDecimal("remaining_reserved_amount")
                ),
                orderIds.toArray()
        );
    }

    private void updateOrderAllocation(
            LockedOrderAllocation allocation,
            BigDecimal settled,
            BigDecimal spent,
            BigDecimal released,
            LocalDateTime updatedAt
    ) {
        int updated = jdbcTemplate.update(
                """
                update stock_auto_participant_order_budget
                   set remaining_reserved_amount = remaining_reserved_amount - ?,
                       spent_amount = spent_amount + ?,
                       released_amount = released_amount + ?,
                       updated_at = ?
                 where order_id = ?
                   and budget_id = ?
                   and remaining_reserved_amount >= ?
                """,
                settled,
                spent,
                released,
                updatedAt,
                allocation.orderId(),
                allocation.budgetId(),
                settled
        );
        if (updated != 1) {
            throw new IllegalStateException("Funding order allocation changed during settlement");
        }
    }

    private void updateBudgetAfterSettlement(
            long budgetId,
            BigDecimal settled,
            BigDecimal spent,
            BigDecimal released,
            LocalDateTime updatedAt
    ) {
        int updated = jdbcTemplate.update(
                """
                update stock_auto_participant_funding_budget
                   set reserved_amount = reserved_amount - ?,
                       spent_amount = spent_amount + ?,
                       available_amount = available_amount + ?,
                       updated_at = ?
                 where id = ?
                   and reserved_amount >= ?
                """,
                settled,
                spent,
                released,
                updatedAt,
                budgetId,
                settled
        );
        if (updated != 1) {
            throw new IllegalStateException("Funding budget changed during settlement: budgetId=" + budgetId);
        }
        int statusUpdated = jdbcTemplate.update(
                """
                update stock_auto_participant_funding_budget
                   set status = case
                           when available_amount = 0 and reserved_amount = 0 then 'EXHAUSTED'
                           when expires_business_date is not null
                            and expires_business_date < ?
                            and reserved_amount = 0 then 'EXPIRED'
                           else 'ACTIVE'
                       end,
                       updated_at = ?
                 where id = ?
                """,
                updatedAt.toLocalDate(),
                updatedAt,
                budgetId
        );
        if (statusUpdated != 1) {
            throw new IllegalStateException(
                    "Funding budget status changed during settlement: budgetId=" + budgetId
            );
        }
    }

    private BigDecimal nonNegative(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
    }

    ReservationPlan planReservations(List<AutoMarketPlannedOrder> orders, LocalDate businessDate) {
        List<AutoMarketPlannedOrder> budgetedOrders = orders.stream()
                .filter(order -> order.fundingBudgetType() != null)
                .toList();
        if (budgetedOrders.isEmpty()) {
            return ReservationPlan.empty();
        }
        List<Long> accountIds = budgetedOrders.stream()
                .map(AutoMarketPlannedOrder::accountId)
                .distinct()
                .sorted()
                .toList();
        String placeholders = String.join(",", Collections.nCopies(accountIds.size(), "?"));
        List<Object> parameters = new ArrayList<>(accountIds.size() + 1);
        parameters.addAll(accountIds);
        parameters.add(businessDate);
        List<BudgetBalance> balances = jdbcTemplate.query(
                """
                select id, account_id, budget_type, source_symbol, available_amount
                  from stock_auto_participant_funding_budget
                 where account_id in (%s)
                   and status = 'ACTIVE'
                   and available_amount > 0
                   and (expires_business_date is null or expires_business_date >= ?)
                 order by account_id asc, budget_type asc, expires_business_date asc, id asc
                 for update
                """.formatted(placeholders),
                (rs, rowNum) -> new BudgetBalance(
                        rs.getLong("id"),
                        rs.getLong("account_id"),
                        AutoParticipantFundingBudgetType.valueOf(rs.getString("budget_type")),
                        rs.getString("source_symbol"),
                        rs.getBigDecimal("available_amount")
                ),
                parameters.toArray()
        );
        Map<BudgetKey, List<BudgetBalance>> balancesByKey = new LinkedHashMap<>();
        for (BudgetBalance balance : balances) {
            balancesByKey.computeIfAbsent(balance.key(), ignored -> new ArrayList<>()).add(balance);
        }
        Set<AutoMarketPlannedOrder> accepted = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<AutoMarketPlannedOrder, List<BudgetAllocation>> allocations = new IdentityHashMap<>();
        int allocationCount = 0;
        for (AutoMarketPlannedOrder order : budgetedOrders) {
            BudgetKey key = BudgetKey.of(order);
            List<BudgetBalance> matching = balancesByKey.getOrDefault(key, List.of());
            BigDecimal required = order.reservedCash();
            BigDecimal available = matching.stream()
                    .map(BudgetBalance::remaining)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (required.signum() <= 0 || available.compareTo(required) < 0) {
                continue;
            }
            BigDecimal remaining = required;
            List<BudgetAllocation> orderAllocations = new ArrayList<>();
            for (BudgetBalance balance : matching) {
                if (remaining.signum() <= 0) {
                    break;
                }
                BigDecimal allocated = balance.take(remaining);
                if (allocated.signum() > 0) {
                    orderAllocations.add(new BudgetAllocation(balance.id(), allocated));
                    remaining = remaining.subtract(allocated);
                    allocationCount++;
                }
            }
            if (remaining.signum() != 0) {
                throw new IllegalStateException("Funding budget reservation planning lost available balance");
            }
            if (allocationCount > MAX_RESERVATION_ALLOCATIONS) {
                throw new IllegalStateException("Funding budget allocation chunk exceeds the bounded limit");
            }
            accepted.add(order);
            allocations.put(order, List.copyOf(orderAllocations));
        }
        return new ReservationPlan(accepted, allocations);
    }

    void reserve(
            ReservationPlan plan,
            Map<AutoMarketPlannedOrder, String> clientOrderIds,
            Set<AutoMarketPlannedOrder> acceptedOrders,
            LocalDateTime reservedAt
    ) {
        List<StoredAllocation> storedAllocations = new ArrayList<>();
        for (AutoMarketPlannedOrder order : acceptedOrders) {
            if (order.fundingBudgetType() == null) {
                continue;
            }
            String clientOrderId = clientOrderIds.get(order);
            if (clientOrderId == null) {
                throw new IllegalStateException("Funding budget order has no client order id");
            }
            for (BudgetAllocation allocation : plan.allocationsFor(order)) {
                storedAllocations.add(new StoredAllocation(
                        clientOrderId,
                        allocation.budgetId(),
                        allocation.amount()
                ));
            }
        }
        if (storedAllocations.isEmpty()) {
            return;
        }
        Map<Long, BigDecimal> amountByBudgetId = new LinkedHashMap<>();
        for (StoredAllocation allocation : storedAllocations) {
            amountByBudgetId.merge(allocation.budgetId(), allocation.amount(), BigDecimal::add);
        }
        updateBudgetReservations(amountByBudgetId, reservedAt);
        insertOrderAllocations(storedAllocations, reservedAt);
    }

    private void updateBudgetReservations(Map<Long, BigDecimal> amountByBudgetId, LocalDateTime reservedAt) {
        String cases = String.join(" ", Collections.nCopies(amountByBudgetId.size(), "when ? then ?"));
        String placeholders = String.join(",", Collections.nCopies(amountByBudgetId.size(), "?"));
        List<Object> parameters = new ArrayList<>(amountByBudgetId.size() * 4 + 1);
        amountByBudgetId.forEach((budgetId, amount) -> {
            parameters.add(budgetId);
            parameters.add(amount);
        });
        amountByBudgetId.forEach((budgetId, amount) -> {
            parameters.add(budgetId);
            parameters.add(amount);
        });
        parameters.add(reservedAt);
        parameters.addAll(amountByBudgetId.keySet());
        int updated = jdbcTemplate.update(
                """
                update stock_auto_participant_funding_budget
                   set available_amount = available_amount - case id %s else 0 end,
                       reserved_amount = reserved_amount + case id %s else 0 end,
                       updated_at = ?
                 where id in (%s)
                   and status = 'ACTIVE'
                """.formatted(cases, cases, placeholders),
                parameters.toArray()
        );
        if (updated != amountByBudgetId.size()) {
            throw new IllegalStateException(
                    "Funding budget reservation count mismatch: expected=%d, actual=%d"
                            .formatted(amountByBudgetId.size(), updated)
            );
        }
    }

    private void insertOrderAllocations(List<StoredAllocation> allocations, LocalDateTime reservedAt) {
        String values = String.join(",", Collections.nCopies(
                allocations.size(),
                "((select id from stock_order where client_order_id = ?), ?, ?, ?, 0, 0, ?, ?)"
        ));
        List<Object> parameters = new ArrayList<>(allocations.size() * 6);
        for (StoredAllocation allocation : allocations) {
            parameters.add(allocation.clientOrderId());
            parameters.add(allocation.budgetId());
            parameters.add(allocation.amount());
            parameters.add(allocation.amount());
            parameters.add(reservedAt);
            parameters.add(reservedAt);
        }
        int inserted = jdbcTemplate.update(
                """
                insert into stock_auto_participant_order_budget(
                    order_id, budget_id, allocated_amount, remaining_reserved_amount,
                    spent_amount, released_amount, created_at, updated_at
                ) values %s
                """.formatted(values),
                parameters.toArray()
        );
        if (inserted != allocations.size()) {
            throw new IllegalStateException(
                    "Funding order allocation count mismatch: expected=%d, actual=%d"
                            .formatted(allocations.size(), inserted)
            );
        }
    }

    record ReservationPlan(
            Set<AutoMarketPlannedOrder> acceptedOrders,
            Map<AutoMarketPlannedOrder, List<BudgetAllocation>> allocationsByOrder
    ) {
        ReservationPlan {
            Set<AutoMarketPlannedOrder> copiedAccepted = Collections.newSetFromMap(new IdentityHashMap<>());
            copiedAccepted.addAll(acceptedOrders);
            acceptedOrders = Collections.unmodifiableSet(copiedAccepted);
            Map<AutoMarketPlannedOrder, List<BudgetAllocation>> copied = new IdentityHashMap<>();
            allocationsByOrder.forEach((order, allocations) -> copied.put(order, List.copyOf(allocations)));
            allocationsByOrder = Collections.unmodifiableMap(copied);
        }

        static ReservationPlan empty() {
            return new ReservationPlan(Set.of(), Map.of());
        }

        boolean accepts(AutoMarketPlannedOrder order) {
            return order.fundingBudgetType() == null || acceptedOrders.contains(order);
        }

        List<BudgetAllocation> allocationsFor(AutoMarketPlannedOrder order) {
            return allocationsByOrder.getOrDefault(order, List.of());
        }
    }

    private record BudgetKey(long accountId, AutoParticipantFundingBudgetType type, String sourceSymbol) {
        static BudgetKey of(AutoMarketPlannedOrder order) {
            return new BudgetKey(
                    order.accountId(),
                    order.fundingBudgetType(),
                    order.fundingBudgetType() == AutoParticipantFundingBudgetType.DIVIDEND
                            ? order.symbol()
                            : null
            );
        }
    }

    private static final class BudgetBalance {
        private final long id;
        private final long accountId;
        private final AutoParticipantFundingBudgetType type;
        private final String sourceSymbol;
        private BigDecimal remaining;

        private BudgetBalance(
                long id,
                long accountId,
                AutoParticipantFundingBudgetType type,
                String sourceSymbol,
                BigDecimal remaining
        ) {
            this.id = id;
            this.accountId = accountId;
            this.type = type;
            this.sourceSymbol = sourceSymbol;
            this.remaining = remaining == null ? BigDecimal.ZERO : remaining.max(BigDecimal.ZERO);
        }

        private long id() {
            return id;
        }

        private BigDecimal remaining() {
            return remaining;
        }

        private BudgetKey key() {
            return new BudgetKey(accountId, type, type == AutoParticipantFundingBudgetType.DIVIDEND ? sourceSymbol : null);
        }

        private BigDecimal take(BigDecimal requested) {
            BigDecimal taken = remaining.min(requested).max(BigDecimal.ZERO);
            remaining = remaining.subtract(taken);
            return taken;
        }
    }

    private record BudgetAllocation(long budgetId, BigDecimal amount) {
    }

    private record StoredAllocation(String clientOrderId, long budgetId, BigDecimal amount) {
    }

    private record LockedOrderAllocation(
            long orderId,
            long budgetId,
            BigDecimal remainingReservedAmount
    ) {
    }

    private record FundingBudgetReconciliationRow(
            long budgetId,
            BigDecimal grantedAmount,
            BigDecimal availableAmount,
            BigDecimal reservedAmount,
            BigDecimal spentAmount,
            BigDecimal allocatedAmount,
            BigDecimal allocationReservedAmount,
            BigDecimal allocationSpentAmount,
            BigDecimal allocationReleasedAmount
    ) {
        private void validate() {
            requireNonNegative("granted", grantedAmount);
            requireNonNegative("available", availableAmount);
            requireNonNegative("reserved", reservedAmount);
            requireNonNegative("spent", spentAmount);
            requireNonNegative("allocated", allocatedAmount);
            requireNonNegative("allocationReserved", allocationReservedAmount);
            requireNonNegative("allocationSpent", allocationSpentAmount);
            requireNonNegative("allocationReleased", allocationReleasedAmount);
            BigDecimal budgetBalance = availableAmount.add(reservedAmount).add(spentAmount);
            if (grantedAmount.compareTo(budgetBalance) != 0) {
                throw mismatch("grant", grantedAmount, budgetBalance);
            }
            BigDecimal allocationBalance = allocationReservedAmount
                    .add(allocationSpentAmount)
                    .add(allocationReleasedAmount);
            if (allocatedAmount.compareTo(allocationBalance) != 0) {
                throw mismatch("allocation", allocatedAmount, allocationBalance);
            }
            if (reservedAmount.compareTo(allocationReservedAmount) != 0) {
                throw mismatch("reserved", reservedAmount, allocationReservedAmount);
            }
            if (spentAmount.compareTo(allocationSpentAmount) != 0) {
                throw mismatch("spent", spentAmount, allocationSpentAmount);
            }
        }

        private void requireNonNegative(String field, BigDecimal value) {
            if (value == null || value.signum() < 0) {
                throw new IllegalStateException(
                        "Funding budget reconciliation found negative %s amount: budgetId=%d, value=%s"
                                .formatted(field, budgetId, value)
                );
            }
        }

        private IllegalStateException mismatch(String field, BigDecimal budgetValue, BigDecimal derivedValue) {
            return new IllegalStateException(
                    "Funding budget reconciliation mismatch: budgetId=%d, field=%s, budget=%s, derived=%s"
                            .formatted(budgetId, field, budgetValue, derivedValue)
            );
        }
    }

    public record FundingBudgetReconciliationChunk(
            int processedCount,
            long lastBudgetId,
            boolean finished
    ) {
    }

    public record FundingBudgetExpiryChunk(
            int expiredCount,
            long lastBudgetId,
            boolean finished
    ) {
    }
}
