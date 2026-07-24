package stock.batch.service.automarket.profile;

public record MarketSignalSnapshot(
        double momentum5Minute,
        double momentum1Hour,
        double return1Day,
        double return3Day,
        double return5Day,
        double return10Day,
        double return20Day,
        long averageVolume5Day,
        long averageVolume20Day,
        long secondsToClose,
        double spreadTicks,
        long bidDepth,
        long askDepth,
        double reportPressure,
        long reportAgeSeconds,
        long recentExecutionQuantity5Minute,
        int recentParticipantCount5Minute,
        boolean recentActivityAvailable,
        int completedTradingDayCount
) {
    public static final MarketSignalSnapshot EMPTY = new MarketSignalSnapshot(
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0L,
            0L,
            Long.MAX_VALUE,
            0.0,
            0L,
            0L,
            0.0,
            Long.MAX_VALUE,
            0L,
            0,
            false,
            0
    );

    public MarketSignalSnapshot(
            double momentum5Minute,
            double momentum1Hour,
            double return1Day,
            double return3Day,
            double return5Day,
            double return20Day,
            long averageVolume5Day,
            long averageVolume20Day,
            long secondsToClose,
            double spreadTicks,
            long bidDepth,
            long askDepth,
            double reportPressure,
            long reportAgeSeconds,
            long recentExecutionQuantity5Minute,
            int recentParticipantCount5Minute,
            boolean recentActivityAvailable,
            int completedTradingDayCount
    ) {
        this(
                momentum5Minute,
                momentum1Hour,
                return1Day,
                return3Day,
                return5Day,
                return5Day,
                return20Day,
                averageVolume5Day,
                averageVolume20Day,
                secondsToClose,
                spreadTicks,
                bidDepth,
                askDepth,
                reportPressure,
                reportAgeSeconds,
                recentExecutionQuantity5Minute,
                recentParticipantCount5Minute,
                recentActivityAvailable,
                completedTradingDayCount
        );
    }

    public MarketSignalSnapshot(
            double momentum5Minute,
            double momentum1Hour,
            double return1Day,
            double return3Day,
            double return5Day,
            double return20Day,
            long averageVolume5Day,
            long averageVolume20Day,
            long secondsToClose,
            double spreadTicks,
            long bidDepth,
            long askDepth,
            double reportPressure,
            long reportAgeSeconds,
            long recentExecutionQuantity5Minute,
            int recentParticipantCount5Minute,
            boolean recentActivityAvailable
    ) {
        this(
                momentum5Minute,
                momentum1Hour,
                return1Day,
                return3Day,
                return5Day,
                return5Day,
                return20Day,
                averageVolume5Day,
                averageVolume20Day,
                secondsToClose,
                spreadTicks,
                bidDepth,
                askDepth,
                reportPressure,
                reportAgeSeconds,
                recentExecutionQuantity5Minute,
                recentParticipantCount5Minute,
                recentActivityAvailable,
                21
        );
    }

    public MarketSignalSnapshot(
            double momentum5Minute,
            double momentum1Hour,
            double return1Day,
            double return3Day,
            double return5Day,
            double return20Day,
            long averageVolume5Day,
            long averageVolume20Day,
            long secondsToClose,
            double spreadTicks,
            long bidDepth,
            long askDepth,
            double reportPressure,
            long reportAgeSeconds
    ) {
        this(
                momentum5Minute,
                momentum1Hour,
                return1Day,
                return3Day,
                return5Day,
                return5Day,
                return20Day,
                averageVolume5Day,
                averageVolume20Day,
                secondsToClose,
                spreadTicks,
                bidDepth,
                askDepth,
                reportPressure,
                reportAgeSeconds,
                0L,
                0,
                false,
                21
        );
    }

    public MarketSignalSnapshot {
        momentum5Minute = clampPressure(momentum5Minute);
        momentum1Hour = clampPressure(momentum1Hour);
        return1Day = clampReturn(return1Day);
        return3Day = clampReturn(return3Day);
        return5Day = clampReturn(return5Day);
        return10Day = clampReturn(return10Day);
        return20Day = clampReturn(return20Day);
        averageVolume5Day = Math.max(0L, averageVolume5Day);
        averageVolume20Day = Math.max(0L, averageVolume20Day);
        secondsToClose = Math.max(0L, secondsToClose);
        spreadTicks = Math.max(0.0, spreadTicks);
        bidDepth = Math.max(0L, bidDepth);
        askDepth = Math.max(0L, askDepth);
        reportPressure = clampPressure(reportPressure);
        reportAgeSeconds = Math.max(0L, reportAgeSeconds);
        recentExecutionQuantity5Minute = Math.max(0L, recentExecutionQuantity5Minute);
        recentParticipantCount5Minute = Math.max(0, recentParticipantCount5Minute);
        completedTradingDayCount = Math.max(0, completedTradingDayCount);
    }

    public double decayedReportPressure(long halfLifeSeconds) {
        if (reportPressure == 0.0 || reportAgeSeconds == Long.MAX_VALUE) {
            return 0.0;
        }
        long normalizedHalfLife = Math.max(1L, halfLifeSeconds);
        double decay = Math.pow(0.5, (double) reportAgeSeconds / normalizedHalfLife);
        return Math.clamp(reportPressure * decay, -1.0, 1.0);
    }

    public double depthImbalance() {
        long totalDepth = bidDepth + askDepth;
        if (totalDepth <= 0) {
            return 0.0;
        }
        return Math.clamp((double) (bidDepth - askDepth) / totalDepth, -1.0, 1.0);
    }

    public double recentVolumeRatio() {
        if (!recentActivityAvailable) {
            return 0.0;
        }
        long expectedFiveMinuteQuantity = averageVolume5Day <= 0
                ? 0L
                : Math.max(1L, Math.round(averageVolume5Day / 144.0));
        if (expectedFiveMinuteQuantity == 0L) {
            return recentExecutionQuantity5Minute > 0 ? 1.0 : 0.0;
        }
        return Math.clamp((double) recentExecutionQuantity5Minute / expectedFiveMinuteQuantity, 0.0, 10.0);
    }

    public boolean hasTrailingReturn(int tradingDays) {
        return tradingDays >= 1 && completedTradingDayCount > tradingDays;
    }

    private static double clampReturn(double value) {
        return Double.isFinite(value) ? Math.clamp(value, -1.0, 10.0) : 0.0;
    }

    private static double clampPressure(double value) {
        return Double.isFinite(value) ? Math.clamp(value, -1.0, 1.0) : 0.0;
    }
}
