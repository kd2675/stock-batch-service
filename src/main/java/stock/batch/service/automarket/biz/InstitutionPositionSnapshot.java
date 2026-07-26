package stock.batch.service.automarket.biz;

record InstitutionPositionSnapshot(
        long actualQuantity,
        long reservedQuantity,
        long openBuyQuantity,
        long openSellQuantity
) {

    static final InstitutionPositionSnapshot EMPTY = new InstitutionPositionSnapshot(0L, 0L, 0L, 0L);

    InstitutionPositionSnapshot {
        if (actualQuantity < 0L
                || reservedQuantity < 0L
                || reservedQuantity > actualQuantity
                || openBuyQuantity < 0L
                || openSellQuantity < 0L) {
            throw new IllegalArgumentException(
                    "Institution position quantities and reservations must reconcile"
            );
        }
    }

    long projectedQuantity() {
        long afterBuys = saturatingAdd(actualQuantity, openBuyQuantity);
        return Math.max(0L, afterBuys - Math.min(afterBuys, openSellQuantity));
    }

    long projectedQuantity(long plannedBuyQuantity, long plannedSellQuantity) {
        long afterPlannedBuys = saturatingAdd(
                projectedQuantity(),
                Math.max(0L, plannedBuyQuantity)
        );
        return Math.max(
                0L,
                afterPlannedBuys - Math.min(afterPlannedBuys, Math.max(0L, plannedSellQuantity))
        );
    }

    long availableSellQuantity() {
        return Math.max(0L, actualQuantity - reservedQuantity);
    }

    long availableSellQuantity(long plannedSellQuantity) {
        long available = availableSellQuantity();
        return Math.max(0L, available - Math.min(available, Math.max(0L, plannedSellQuantity)));
    }

    private long saturatingAdd(long left, long right) {
        if (right > Long.MAX_VALUE - left) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
