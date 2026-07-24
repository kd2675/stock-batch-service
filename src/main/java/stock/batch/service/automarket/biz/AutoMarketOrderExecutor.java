package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import stock.batch.service.batch.automarket.model.AutoOrder;
import stock.batch.service.batch.automarket.model.AutoMarketOrderBookSnapshot;
import stock.batch.service.batch.automarket.model.AutoParticipantBehaviorModelVersion;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.reader.AutoMarketOrderReader;
import stock.batch.service.batch.automarket.writer.AutoMarketWriter;
import stock.batch.service.automarket.profile.ProfileDecisionReason;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;

@Component
@RequiredArgsConstructor
class AutoMarketOrderExecutor {

    private static final String BUY = "BUY";
    private static final String SELL = "SELL";

    private final AutoMarketOrderReader autoMarketOrderReader;
    private final AutoMarketWriter autoMarketWriter;
    private final MarketSessionFenceService marketSessionFenceService;
    private final AutoParticipantFundingBudgetService fundingBudgetService;

    AutoMarketOrderBookState loadOrderBookState(String symbol) {
        AutoMarketOrderBookSnapshot snapshot = autoMarketOrderReader.findOrderBookSnapshot(symbol);
        return new AutoMarketOrderBookState(
                snapshot.bestBid(),
                snapshot.bestAsk(),
                snapshot.openBuyQuantity(),
                snapshot.openSellQuantity()
        );
    }

    AutoMarketOrderBookState loadExternalOrderBookState(String symbol, long excludedAccountId) {
        return new AutoMarketOrderBookState(
                autoMarketOrderReader.findBestExternalPrice(symbol, BUY, excludedAccountId),
                autoMarketOrderReader.findBestExternalPrice(symbol, SELL, excludedAccountId),
                0L,
                0L
        );
    }

    void expireOrder(AutoOrder order, LocalDateTime now) {
        expireOrders(List.of(order), now);
    }

    int expireOrders(List<AutoOrder> orders, LocalDateTime now) {
        if (orders.isEmpty()) {
            return 0;
        }
        lockCancellationResources(orders);
        List<AutoOrder> lockedOrders = autoMarketOrderReader.lockOpenOrdersForUpdate(orders);
        if (lockedOrders.isEmpty()) {
            return 0;
        }
        int cancelledCount = autoMarketWriter.cancelOpenOrders(lockedOrders, now);
        if (cancelledCount != lockedOrders.size()) {
            throw new IllegalStateException(
                    "Auto-order expiry cancellation count mismatch: expected=%d, actual=%d"
                            .formatted(lockedOrders.size(), cancelledCount)
            );
        }
        fundingBudgetService.releaseCancelledOrderBudgets(
                lockedOrders.stream().map(AutoOrder::id).toList(),
                now
        );
        autoMarketWriter.creditCancelledBuyReservations(lockedOrders, now);
        autoMarketWriter.releaseCancelledSellReservations(lockedOrders, now);
        return cancelledCount;
    }

    private void lockCancellationResources(List<AutoOrder> orders) {
        autoMarketWriter.lockAccountsForUpdate(
                orders.stream().map(AutoOrder::accountId).toList()
        );
        autoMarketWriter.lockSellHoldingsForUpdate(orders);
    }

    boolean placeOrder(long accountId, String symbol, String side, BigDecimal price, long quantity) {
        AutoParticipantOrderGenerationResult result = placeOrders(List.of(new AutoMarketPlannedOrder(
                accountId,
                symbol,
                side,
                price,
                quantity
        )));
        return result.generatedOrderCount() == 1;
    }

    boolean placeOrderWithOpenFenceHeld(
            long accountId,
            String symbol,
            String side,
            BigDecimal price,
            long quantity,
            MarketSessionFenceService.MarketSessionApproval sessionApproval
    ) {
        AutoParticipantOrderGenerationResult result = placeOrdersWithOpenFenceHeld(
                List.of(new AutoMarketPlannedOrder(accountId, symbol, side, price, quantity)),
                sessionApproval
        );
        return result.generatedOrderCount() == 1;
    }

    AutoParticipantOrderGenerationResult placeOrders(List<AutoMarketPlannedOrder> plannedOrders) {
        if (plannedOrders.isEmpty()) {
            return AutoParticipantOrderGenerationResult.execution(0, 0, 0, 0, 0, 0);
        }
        requireBoundedPlannedOrders(plannedOrders);
        var sessionApproval = marketSessionFenceService.lockOpenOrderBookFences(
                plannedOrders.stream().map(AutoMarketPlannedOrder::symbol).toList()
        );
        if (sessionApproval.isEmpty()) {
            return AutoParticipantOrderGenerationResult.droppedExecution(
                    plannedOrders.size(),
                    AutoMarketOrderDropReason.SESSION_CLOSED
            );
        }
        return placeOrdersAfterApproval(plannedOrders, sessionApproval.get());
    }

    AutoParticipantOrderGenerationResult placeOrdersWithOpenFenceHeld(
            List<AutoMarketPlannedOrder> plannedOrders,
            MarketSessionFenceService.MarketSessionApproval sessionApproval
    ) {
        if (plannedOrders.isEmpty()) {
            return AutoParticipantOrderGenerationResult.execution(0, 0, 0, 0, 0, 0);
        }
        requireBoundedPlannedOrders(plannedOrders);
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("An active transaction is required while reusing a market session fence");
        }
        boolean missingApprovedSymbol = plannedOrders.stream()
                .map(AutoMarketPlannedOrder::symbol)
                .distinct()
                .anyMatch(symbol -> !sessionApproval.sessionEpochs().containsKey(symbol));
        if (missingApprovedSymbol) {
            throw new IllegalArgumentException("Planned order symbol is not covered by the held market session fence");
        }
        return placeOrdersAfterApproval(plannedOrders, sessionApproval);
    }

    private AutoParticipantOrderGenerationResult placeOrdersAfterApproval(
            List<AutoMarketPlannedOrder> plannedOrders,
            MarketSessionFenceService.MarketSessionApproval sessionApproval
    ) {
        LocalDateTime now = sessionApproval.businessEffectiveAt();
        Map<Long, AutoMarketWriter.AccountReservationState> accountStates =
                autoMarketWriter.lockAccountReservationStatesForUpdate(
                plannedOrders.stream()
                        .map(AutoMarketPlannedOrder::accountId)
                        .distinct()
                        .sorted()
                        .toList()
        );
        Map<AutoMarketWriter.HoldingReservationKey, List<AutoMarketPlannedOrder>> sellOrdersByHolding =
                groupSellOrdersByHolding(plannedOrders);
        Map<AutoMarketWriter.HoldingReservationKey, AutoMarketWriter.HoldingReservationState> holdingStates =
                autoMarketWriter.lockHoldingReservationStatesForUpdate(
                        sellOrdersByHolding.keySet().stream()
                                .sorted(Comparator.comparingLong(AutoMarketWriter.HoldingReservationKey::accountId)
                                        .thenComparing(AutoMarketWriter.HoldingReservationKey::symbol))
                                .toList()
                );
        BalancedPairFilterResult pairFilter = filterBalancedMarketMakerPairs(
                plannedOrders,
                accountStates,
                holdingStates
        );
        List<AutoMarketPlannedOrder> pairEligibleOrders = pairFilter.eligibleOrders();
        AutoParticipantFundingBudgetService.ReservationPlan fundingPlan =
                pairEligibleOrders.stream().noneMatch(order -> order.fundingBudgetType() != null)
                        ? AutoParticipantFundingBudgetService.ReservationPlan.empty()
                        : fundingBudgetService.planReservations(pairEligibleOrders, now.toLocalDate());
        List<AutoMarketPlannedOrder> fundingEligibleOrders = pairEligibleOrders.stream()
                .filter(fundingPlan::accepts)
                .toList();
        int failedFundingBudgetCount = (int) pairEligibleOrders.stream()
                .filter(order -> order.fundingBudgetType() != null)
                .filter(order -> !fundingPlan.accepts(order))
                .count();
        Set<AutoMarketPlannedOrder> acceptedOrderSet = Collections.newSetFromMap(new IdentityHashMap<>());
        int failedBuyReserveCount = pairFilter.rejectedBuyCount() + failedFundingBudgetCount
                + reserveBuyOrders(fundingEligibleOrders, accountStates, acceptedOrderSet, now);
        int failedSellReserveCount = pairFilter.rejectedSellCount() + reserveSellOrders(
                groupSellOrdersByHolding(fundingEligibleOrders),
                accountStates,
                holdingStates,
                acceptedOrderSet,
                now
        );
        List<AutoMarketPlannedOrder> acceptedOrders = plannedOrders.stream()
                .filter(acceptedOrderSet::contains)
                .toList();
        if (acceptedOrders.isEmpty()) {
            return AutoParticipantOrderGenerationResult.execution(
                    plannedOrders.size(),
                    0,
                    0,
                    0,
                    failedBuyReserveCount,
                    failedSellReserveCount
            );
        }
        Map<AutoMarketPlannedOrder, String> clientOrderIds = new IdentityHashMap<>();
        List<AutoMarketWriter.LimitOrderInsert> inserts = acceptedOrders.stream()
                .map(order -> {
                    String clientOrderId = nextClientOrderId();
                    clientOrderIds.put(order, clientOrderId);
                    return new AutoMarketWriter.LimitOrderInsert(
                        clientOrderId,
                        order.accountId(),
                        order.symbol(),
                        order.side(),
                        order.price(),
                            order.quantity(),
                            order.reservedCash(),
                            order.fundingBudgetType() == null ? null : order.fundingBudgetType().name(),
                            order.expiresAt(),
                            order.profileType() == null ? null : order.profileType().name(),
                            order.behaviorModelVersion() == null ? null : order.behaviorModelVersion().name()
                    );
                })
                .toList();
        int insertedCount = autoMarketWriter.insertLimitOrders(inserts, now);
        if (insertedCount != acceptedOrders.size()) {
            throw new IllegalStateException("Auto order batch insert count mismatch: expected=%d, actual=%d"
                    .formatted(acceptedOrders.size(), insertedCount));
        }
        fundingBudgetService.reserve(fundingPlan, clientOrderIds, acceptedOrderSet, now);
        markAverageDownDecisions(acceptedOrders, now);
        int reservedBuyCount = 0;
        int reservedSellCount = 0;
        for (AutoMarketPlannedOrder order : acceptedOrders) {
            if (BUY.equals(order.side())) {
                reservedBuyCount++;
            } else if (SELL.equals(order.side())) {
                reservedSellCount++;
            }
        }
        return AutoParticipantOrderGenerationResult.execution(
                plannedOrders.size(),
                insertedCount,
                reservedBuyCount,
                reservedSellCount,
                failedBuyReserveCount,
                failedSellReserveCount
        );
    }

    private BalancedPairFilterResult filterBalancedMarketMakerPairs(
            List<AutoMarketPlannedOrder> plannedOrders,
            Map<Long, AutoMarketWriter.AccountReservationState> accountStates,
            Map<AutoMarketWriter.HoldingReservationKey, AutoMarketWriter.HoldingReservationState> holdingStates
    ) {
        Map<BalancedPairKey, List<AutoMarketPlannedOrder>> pairsByKey = new LinkedHashMap<>();
        for (AutoMarketPlannedOrder order : plannedOrders) {
            if (isBalancedV2MarketMakerOrder(order)) {
                pairsByKey.computeIfAbsent(
                        new BalancedPairKey(order.accountId(), order.symbol()),
                        ignored -> new ArrayList<>()
                ).add(order);
            }
        }
        if (pairsByKey.isEmpty()) {
            return new BalancedPairFilterResult(plannedOrders, 0, 0);
        }
        Set<AutoMarketPlannedOrder> rejected = Collections.newSetFromMap(new IdentityHashMap<>());
        int rejectedBuyCount = 0;
        int rejectedSellCount = 0;
        for (Map.Entry<BalancedPairKey, List<AutoMarketPlannedOrder>> entry : pairsByKey.entrySet()) {
            List<AutoMarketPlannedOrder> pairOrders = entry.getValue();
            List<AutoMarketPlannedOrder> buyOrders = pairOrders.stream().filter(order -> BUY.equals(order.side())).toList();
            List<AutoMarketPlannedOrder> sellOrders = pairOrders.stream().filter(order -> SELL.equals(order.side())).toList();
            AutoMarketWriter.AccountReservationState accountState = accountStates.get(entry.getKey().accountId());
            AutoMarketWriter.HoldingReservationState holdingState = holdingStates.get(
                    new AutoMarketWriter.HoldingReservationKey(entry.getKey().accountId(), entry.getKey().symbol())
            );
            BigDecimal requiredCash = buyOrders.stream()
                    .map(AutoMarketPlannedOrder::reservedCash)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            long requiredHolding = sellOrders.stream().mapToLong(AutoMarketPlannedOrder::quantity).sum();
            boolean completePair = !buyOrders.isEmpty() && buyOrders.size() == sellOrders.size();
            boolean resourcesAvailable = isActive(accountState)
                    && accountState.cashBalance().compareTo(requiredCash) >= 0
                    && holdingState != null
                    && holdingState.availableQuantity() >= requiredHolding;
            if (completePair && resourcesAvailable) {
                continue;
            }
            rejected.addAll(pairOrders);
            rejectedBuyCount += buyOrders.size();
            rejectedSellCount += sellOrders.size();
        }
        if (rejected.isEmpty()) {
            return new BalancedPairFilterResult(plannedOrders, 0, 0);
        }
        return new BalancedPairFilterResult(
                plannedOrders.stream().filter(order -> !rejected.contains(order)).toList(),
                rejectedBuyCount,
                rejectedSellCount
        );
    }

    private boolean isBalancedV2MarketMakerOrder(AutoMarketPlannedOrder order) {
        return order.profileType() == AutoParticipantProfileType.MARKET_MAKER
                && order.behaviorModelVersion() == AutoParticipantBehaviorModelVersion.V2
                && order.decisionReason() == ProfileDecisionReason.INVENTORY_BALANCED;
    }

    private void markAverageDownDecisions(
            List<AutoMarketPlannedOrder> acceptedOrders,
            LocalDateTime now
    ) {
        List<AutoMarketWriter.PositionStateKey> positions = acceptedOrders.stream()
                .filter(order -> order.decisionReason() == ProfileDecisionReason.AVERAGE_DOWN)
                .map(order -> new AutoMarketWriter.PositionStateKey(order.accountId(), order.symbol()))
                .distinct()
                .toList();
        if (positions.isEmpty()) {
            return;
        }
        int updated = autoMarketWriter.markAverageDownDecisions(positions, now.toLocalDate(), now);
        if (updated != positions.size()) {
            throw new IllegalStateException(
                    "Average-down position-state update count mismatch: expected=%d, actual=%d"
                            .formatted(positions.size(), updated)
            );
        }
    }

    private int reserveBuyOrders(
            List<AutoMarketPlannedOrder> plannedOrders,
            Map<Long, AutoMarketWriter.AccountReservationState> accountStates,
            Set<AutoMarketPlannedOrder> acceptedOrders,
            LocalDateTime now
    ) {
        Map<Long, List<AutoMarketPlannedOrder>> ordersByAccount = new LinkedHashMap<>();
        for (AutoMarketPlannedOrder order : plannedOrders) {
            if (BUY.equals(order.side())) {
                ordersByAccount.computeIfAbsent(order.accountId(), ignored -> new ArrayList<>()).add(order);
            }
        }
        int failedReserveCount = 0;
        Map<Long, BigDecimal> acceptedReservations = new LinkedHashMap<>();
        List<Map.Entry<Long, List<AutoMarketPlannedOrder>>> orderedEntries = ordersByAccount.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        for (Map.Entry<Long, List<AutoMarketPlannedOrder>> entry : orderedEntries) {
            BigDecimal totalReservedCash = entry.getValue().stream()
                    .map(AutoMarketPlannedOrder::reservedCash)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            AutoMarketWriter.AccountReservationState accountState = accountStates.get(entry.getKey());
            if (isActive(accountState) && accountState.cashBalance().compareTo(totalReservedCash) >= 0) {
                acceptedReservations.put(entry.getKey(), totalReservedCash);
                acceptedOrders.addAll(entry.getValue());
            } else {
                failedReserveCount += entry.getValue().size();
            }
        }
        autoMarketWriter.reserveBuyCashChunk(acceptedReservations, now);
        return failedReserveCount;
    }

    private int reserveSellOrders(
            Map<AutoMarketWriter.HoldingReservationKey, List<AutoMarketPlannedOrder>> ordersByHolding,
            Map<Long, AutoMarketWriter.AccountReservationState> accountStates,
            Map<AutoMarketWriter.HoldingReservationKey, AutoMarketWriter.HoldingReservationState> holdingStates,
            Set<AutoMarketPlannedOrder> acceptedOrders,
            LocalDateTime now
    ) {
        int failedReserveCount = 0;
        Map<AutoMarketWriter.HoldingReservationKey, Long> acceptedReservations = new LinkedHashMap<>();
        List<Map.Entry<AutoMarketWriter.HoldingReservationKey, List<AutoMarketPlannedOrder>>> orderedEntries =
                ordersByHolding.entrySet()
                .stream()
                .sorted(Comparator
                        .comparing((Map.Entry<AutoMarketWriter.HoldingReservationKey, List<AutoMarketPlannedOrder>> entry) ->
                                entry.getKey().accountId())
                        .thenComparing(entry -> entry.getKey().symbol()))
                .toList();
        for (Map.Entry<AutoMarketWriter.HoldingReservationKey, List<AutoMarketPlannedOrder>> entry : orderedEntries) {
            long totalQuantity = entry.getValue().stream()
                    .mapToLong(AutoMarketPlannedOrder::quantity)
                    .sum();
            AutoMarketWriter.HoldingReservationKey key = entry.getKey();
            AutoMarketWriter.HoldingReservationState holdingState = holdingStates.get(key);
            if (isActive(accountStates.get(key.accountId()))
                    && holdingState != null
                    && holdingState.availableQuantity() >= totalQuantity) {
                acceptedReservations.put(key, totalQuantity);
                acceptedOrders.addAll(entry.getValue());
            } else {
                failedReserveCount += entry.getValue().size();
            }
        }
        autoMarketWriter.reserveSellQuantityChunk(acceptedReservations, now);
        return failedReserveCount;
    }

    private Map<AutoMarketWriter.HoldingReservationKey, List<AutoMarketPlannedOrder>> groupSellOrdersByHolding(
            List<AutoMarketPlannedOrder> plannedOrders
    ) {
        Map<AutoMarketWriter.HoldingReservationKey, List<AutoMarketPlannedOrder>> ordersByHolding =
                new LinkedHashMap<>();
        for (AutoMarketPlannedOrder order : plannedOrders) {
            if (SELL.equals(order.side())) {
                AutoMarketWriter.HoldingReservationKey key = new AutoMarketWriter.HoldingReservationKey(
                        order.accountId(),
                        order.symbol()
                );
                ordersByHolding.computeIfAbsent(key, ignored -> new ArrayList<>()).add(order);
            }
        }
        return ordersByHolding;
    }

    private boolean isActive(AutoMarketWriter.AccountReservationState accountState) {
        return accountState != null && "ACTIVE".equals(accountState.status());
    }

    private void requireBoundedPlannedOrders(List<AutoMarketPlannedOrder> plannedOrders) {
        if (plannedOrders.size() > AutoMarketWriter.MAX_LIMIT_ORDER_INSERT_ROWS) {
            throw new IllegalArgumentException(
                    "Auto-order generation chunk exceeds the maximum planned-order count: %d > %d"
                            .formatted(plannedOrders.size(), AutoMarketWriter.MAX_LIMIT_ORDER_INSERT_ROWS)
            );
        }
    }

    private String nextClientOrderId() {
        return "auto-" + UUID.randomUUID().toString().replace("-", "");
    }

    private record BalancedPairKey(long accountId, String symbol) {
    }

    private record BalancedPairFilterResult(
            List<AutoMarketPlannedOrder> eligibleOrders,
            int rejectedBuyCount,
            int rejectedSellCount
    ) {
    }

}
