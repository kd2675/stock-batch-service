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
import java.util.TreeMap;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import stock.batch.service.batch.automarket.model.AutoOrder;
import stock.batch.service.batch.automarket.reader.AutoMarketOrderReader;
import stock.batch.service.batch.automarket.writer.AutoMarketWriter;
import stock.batch.service.simulation.SimulationClockService;

@Component
@RequiredArgsConstructor
class AutoMarketOrderExecutor {

    private static final String BUY = "BUY";
    private static final String SELL = "SELL";

    private final AutoMarketOrderReader autoMarketOrderReader;
    private final AutoMarketWriter autoMarketWriter;
    private final SimulationClockService simulationClockService;

    AutoMarketOrderBookState loadOrderBookState(String symbol) {
        return new AutoMarketOrderBookState(
                autoMarketOrderReader.findBestPrice(symbol, BUY),
                autoMarketOrderReader.findBestPrice(symbol, SELL),
                autoMarketOrderReader.getOpenOrderQuantity(symbol, BUY),
                autoMarketOrderReader.getOpenOrderQuantity(symbol, SELL)
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
        List<AutoOrder> cancelledOrders = new ArrayList<>();
        for (AutoOrder order : lockedOrders) {
            if (autoMarketWriter.cancelOpenOrder(order, now)) {
                cancelledOrders.add(order);
            }
        }
        creditCancelledBuyReservations(cancelledOrders, now);
        releaseCancelledSellReservations(cancelledOrders, now);
        return cancelledOrders.size();
    }

    private void lockCancellationResources(List<AutoOrder> orders) {
        orders.stream()
                .map(AutoOrder::accountId)
                .distinct()
                .sorted()
                .forEach(autoMarketWriter::lockAccountForUpdate);
        orders.stream()
                .filter(order -> SELL.equals(order.side()))
                .map(order -> new SellReservationKey(order.accountId(), order.symbol()))
                .distinct()
                .sorted(Comparator.comparingLong(SellReservationKey::accountId)
                        .thenComparing(SellReservationKey::symbol))
                .forEach(key -> autoMarketWriter.lockHoldingForUpdate(key.accountId(), key.symbol()));
    }

    private void creditCancelledBuyReservations(List<AutoOrder> cancelledOrders, LocalDateTime now) {
        Map<Long, BigDecimal> reservedCashByAccount = new TreeMap<>();
        for (AutoOrder order : cancelledOrders) {
            if (!BUY.equals(order.side()) || order.reservedCash().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            reservedCashByAccount.merge(order.accountId(), order.reservedCash(), BigDecimal::add);
        }
        reservedCashByAccount.forEach((accountId, reservedCash) -> autoMarketWriter.creditCash(accountId, reservedCash, now));
    }

    private void releaseCancelledSellReservations(List<AutoOrder> cancelledOrders, LocalDateTime now) {
        Map<SellReservationKey, Long> reservedQuantityByHolding = new TreeMap<>(
                Comparator.comparingLong(SellReservationKey::accountId)
                        .thenComparing(SellReservationKey::symbol)
        );
        for (AutoOrder order : cancelledOrders) {
            long remaining = order.quantity() - order.filledQuantity();
            if (!SELL.equals(order.side()) || remaining <= 0) {
                continue;
            }
            reservedQuantityByHolding.merge(new SellReservationKey(order.accountId(), order.symbol()), remaining, Long::sum);
        }
        reservedQuantityByHolding.forEach((key, quantity) ->
                autoMarketWriter.releaseReservedSellQuantity(key.accountId(), key.symbol(), quantity, now)
        );
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

    AutoParticipantOrderGenerationResult placeOrders(List<AutoMarketPlannedOrder> plannedOrders) {
        if (plannedOrders.isEmpty()) {
            return new AutoParticipantOrderGenerationResult(0, 0, 0, 0);
        }
        LocalDateTime now = simulationClockService.currentMarketDateTime();
        Set<AutoMarketPlannedOrder> acceptedOrderSet = Collections.newSetFromMap(new IdentityHashMap<>());
        int failedReserveCount = reserveBuyOrders(plannedOrders, acceptedOrderSet, now)
                + reserveSellOrders(plannedOrders, acceptedOrderSet, now);
        List<AutoMarketPlannedOrder> acceptedOrders = plannedOrders.stream()
                .filter(acceptedOrderSet::contains)
                .toList();
        if (acceptedOrders.isEmpty()) {
            return new AutoParticipantOrderGenerationResult(0, 0, 0, failedReserveCount);
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
        return new AutoParticipantOrderGenerationResult(
                insertedCount,
                reservedBuyCount,
                reservedSellCount,
                failedReserveCount
        );
    }

    private int reserveBuyOrders(
            List<AutoMarketPlannedOrder> plannedOrders,
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
        List<Map.Entry<Long, List<AutoMarketPlannedOrder>>> orderedEntries = ordersByAccount.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        for (Map.Entry<Long, List<AutoMarketPlannedOrder>> entry : orderedEntries) {
            BigDecimal totalReservedCash = entry.getValue().stream()
                    .map(AutoMarketPlannedOrder::reservedCash)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (autoMarketWriter.reserveBuyCash(entry.getKey(), totalReservedCash, now)) {
                acceptedOrders.addAll(entry.getValue());
            } else {
                failedReserveCount += entry.getValue().size();
            }
        }
        return failedReserveCount;
    }

    private int reserveSellOrders(
            List<AutoMarketPlannedOrder> plannedOrders,
            Set<AutoMarketPlannedOrder> acceptedOrders,
            LocalDateTime now
    ) {
        Map<SellReservationKey, List<AutoMarketPlannedOrder>> ordersByHolding = new LinkedHashMap<>();
        for (AutoMarketPlannedOrder order : plannedOrders) {
            if (SELL.equals(order.side())) {
                SellReservationKey key = new SellReservationKey(order.accountId(), order.symbol());
                ordersByHolding.computeIfAbsent(key, ignored -> new ArrayList<>()).add(order);
            }
        }
        int failedReserveCount = 0;
        List<Map.Entry<SellReservationKey, List<AutoMarketPlannedOrder>>> orderedEntries = ordersByHolding.entrySet()
                .stream()
                .sorted(Comparator
                        .comparing((Map.Entry<SellReservationKey, List<AutoMarketPlannedOrder>> entry) -> entry.getKey().accountId())
                        .thenComparing(entry -> entry.getKey().symbol()))
                .toList();
        for (Map.Entry<SellReservationKey, List<AutoMarketPlannedOrder>> entry : orderedEntries) {
            long totalQuantity = entry.getValue().stream()
                    .mapToLong(AutoMarketPlannedOrder::quantity)
                    .sum();
            SellReservationKey key = entry.getKey();
            if (autoMarketWriter.reserveSellQuantity(key.accountId(), key.symbol(), totalQuantity, now)) {
                acceptedOrders.addAll(entry.getValue());
            } else {
                failedReserveCount += entry.getValue().size();
            }
        }
        return failedReserveCount;
    }

    private String nextClientOrderId() {
        return "auto-" + UUID.randomUUID().toString().replace("-", "");
    }

    private record SellReservationKey(long accountId, String symbol) {
    }
}
