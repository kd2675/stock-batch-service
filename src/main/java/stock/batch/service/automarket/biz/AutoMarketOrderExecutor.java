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
import stock.batch.service.batch.automarket.reader.AutoMarketOrderReader;
import stock.batch.service.batch.automarket.writer.AutoMarketWriter;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;

@Component
@RequiredArgsConstructor
class AutoMarketOrderExecutor {

    private static final String BUY = "BUY";
    private static final String SELL = "SELL";

    private final AutoMarketOrderReader autoMarketOrderReader;
    private final AutoMarketWriter autoMarketWriter;
    private final MarketSessionFenceService marketSessionFenceService;

    AutoMarketOrderBookState loadOrderBookState(String symbol) {
        return new AutoMarketOrderBookState(
                autoMarketOrderReader.findBestPrice(symbol, BUY),
                autoMarketOrderReader.findBestPrice(symbol, SELL),
                autoMarketOrderReader.getOpenOrderQuantity(symbol, BUY),
                autoMarketOrderReader.getOpenOrderQuantity(symbol, SELL)
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
        Set<AutoMarketPlannedOrder> acceptedOrderSet = Collections.newSetFromMap(new IdentityHashMap<>());
        int failedBuyReserveCount = reserveBuyOrders(plannedOrders, accountStates, acceptedOrderSet, now);
        int failedSellReserveCount = reserveSellOrders(
                sellOrdersByHolding,
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
        List<AutoMarketWriter.LimitOrderInsert> inserts = acceptedOrders.stream()
                .map(order -> new AutoMarketWriter.LimitOrderInsert(
                        nextClientOrderId(),
                        order.accountId(),
                        order.symbol(),
                        order.side(),
                        order.price(),
                        order.quantity(),
                        order.reservedCash()
                ))
                .toList();
        int insertedCount = autoMarketWriter.insertLimitOrders(inserts, now);
        if (insertedCount != acceptedOrders.size()) {
            throw new IllegalStateException("Auto order batch insert count mismatch: expected=%d, actual=%d"
                    .formatted(acceptedOrders.size(), insertedCount));
        }
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

}
