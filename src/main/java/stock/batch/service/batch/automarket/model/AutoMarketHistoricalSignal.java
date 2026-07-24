package stock.batch.service.batch.automarket.model;

public record AutoMarketHistoricalSignal(
        double return1Day,
        double return3Day,
        double return5Day,
        double return10Day,
        double return20Day,
        long averageVolume5Day,
        long averageVolume20Day,
        int completedTradingDayCount
) {
    public static final AutoMarketHistoricalSignal EMPTY = new AutoMarketHistoricalSignal(
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0L,
            0L,
            0
    );

    public AutoMarketHistoricalSignal {
        return1Day = finiteOrZero(return1Day);
        return3Day = finiteOrZero(return3Day);
        return5Day = finiteOrZero(return5Day);
        return10Day = finiteOrZero(return10Day);
        return20Day = finiteOrZero(return20Day);
        averageVolume5Day = Math.max(0L, averageVolume5Day);
        averageVolume20Day = Math.max(0L, averageVolume20Day);
        completedTradingDayCount = Math.max(0, completedTradingDayCount);
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? Math.clamp(value, -1.0, 10.0) : 0.0;
    }
}
