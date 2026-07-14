package stock.batch.service.automarket.biz;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

record AutoParticipantOrderGenerationResult(
        int decisionCount,
        int plannedOrderCount,
        int generatedOrderCount,
        int reservedBuyCount,
        int reservedSellCount,
        Map<AutoMarketOrderDropReason, Integer> droppedOrderCounts
) {

    AutoParticipantOrderGenerationResult {
        EnumMap<AutoMarketOrderDropReason, Integer> normalizedCounts = new EnumMap<>(AutoMarketOrderDropReason.class);
        if (droppedOrderCounts != null) {
            droppedOrderCounts.forEach((reason, count) -> {
                if (reason != null && count != null && count > 0) {
                    normalizedCounts.merge(reason, count, Integer::sum);
                }
            });
        }
        droppedOrderCounts = Collections.unmodifiableMap(normalizedCounts);
    }

    static AutoParticipantOrderGenerationResult execution(
            int plannedOrderCount,
            int generatedOrderCount,
            int reservedBuyCount,
            int reservedSellCount,
            int failedBuyReserveCount,
            int failedSellReserveCount
    ) {
        EnumMap<AutoMarketOrderDropReason, Integer> droppedOrderCounts = new EnumMap<>(AutoMarketOrderDropReason.class);
        putPositive(droppedOrderCounts, AutoMarketOrderDropReason.BUY_RESERVATION_FAILED, failedBuyReserveCount);
        putPositive(droppedOrderCounts, AutoMarketOrderDropReason.SELL_RESERVATION_FAILED, failedSellReserveCount);
        return new AutoParticipantOrderGenerationResult(
                plannedOrderCount,
                plannedOrderCount,
                generatedOrderCount,
                reservedBuyCount,
                reservedSellCount,
                droppedOrderCounts
        );
    }

    AutoParticipantOrderGenerationResult withPlanning(
            int decisionCount,
            Map<AutoMarketOrderDropReason, Integer> planningDropCounts
    ) {
        EnumMap<AutoMarketOrderDropReason, Integer> mergedCounts = new EnumMap<>(AutoMarketOrderDropReason.class);
        mergedCounts.putAll(droppedOrderCounts);
        if (planningDropCounts != null) {
            planningDropCounts.forEach((reason, count) -> putPositive(mergedCounts, reason, count));
        }
        return new AutoParticipantOrderGenerationResult(
                decisionCount,
                plannedOrderCount,
                generatedOrderCount,
                reservedBuyCount,
                reservedSellCount,
                mergedCounts
        );
    }

    int droppedOrderCount(AutoMarketOrderDropReason reason) {
        return droppedOrderCounts.getOrDefault(reason, 0);
    }

    int totalDroppedOrderCount() {
        return droppedOrderCounts.values().stream().mapToInt(Integer::intValue).sum();
    }

    int failedReserveCount() {
        return droppedOrderCount(AutoMarketOrderDropReason.BUY_RESERVATION_FAILED)
                + droppedOrderCount(AutoMarketOrderDropReason.SELL_RESERVATION_FAILED);
    }

    private static void putPositive(
            Map<AutoMarketOrderDropReason, Integer> counts,
            AutoMarketOrderDropReason reason,
            Integer count
    ) {
        if (reason != null && count != null && count > 0) {
            counts.merge(reason, count, Integer::sum);
        }
    }
}
