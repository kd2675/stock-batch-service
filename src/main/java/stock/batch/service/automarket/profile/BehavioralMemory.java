package stock.batch.service.automarket.profile;

import java.math.BigDecimal;
import java.time.LocalDate;

public record BehavioralMemory(
        LocalDate positionOpenedBusinessDate,
        int holdingTradingDays,
        int averageDownRounds,
        LocalDate lastAverageDownBusinessDate,
        BigDecimal peakClosePrice,
        int recentProfitableTradingDays,
        int recentClosedTradingDays,
        long intradayPositionAgeSeconds,
        boolean intradayPositionAgeAvailable
) {
    public static final BehavioralMemory EMPTY = new BehavioralMemory(
            null,
            0,
            0,
            null,
            BigDecimal.ZERO,
            0,
            0,
            0L,
            false
    );

    public BehavioralMemory(
            LocalDate positionOpenedBusinessDate,
            int holdingTradingDays,
            int averageDownRounds,
            LocalDate lastAverageDownBusinessDate,
            BigDecimal peakClosePrice
    ) {
        this(
                positionOpenedBusinessDate,
                holdingTradingDays,
                averageDownRounds,
                lastAverageDownBusinessDate,
                peakClosePrice,
                0,
                0,
                0L,
                false
        );
    }

    public BehavioralMemory(
            LocalDate positionOpenedBusinessDate,
            int holdingTradingDays,
            int averageDownRounds,
            LocalDate lastAverageDownBusinessDate,
            BigDecimal peakClosePrice,
            int recentProfitableTradingDays,
            int recentClosedTradingDays
    ) {
        this(
                positionOpenedBusinessDate,
                holdingTradingDays,
                averageDownRounds,
                lastAverageDownBusinessDate,
                peakClosePrice,
                recentProfitableTradingDays,
                recentClosedTradingDays,
                0L,
                false
        );
    }

    public BehavioralMemory {
        holdingTradingDays = Math.max(0, holdingTradingDays);
        averageDownRounds = Math.max(0, averageDownRounds);
        peakClosePrice = peakClosePrice == null ? BigDecimal.ZERO : peakClosePrice.max(BigDecimal.ZERO);
        recentClosedTradingDays = Math.clamp(recentClosedTradingDays, 0, 20);
        recentProfitableTradingDays = Math.clamp(recentProfitableTradingDays, 0, recentClosedTradingDays);
        intradayPositionAgeSeconds = Math.max(0L, intradayPositionAgeSeconds);
    }

    public boolean hasRealizedPerformanceSample(int minimumClosedTradingDays) {
        return recentClosedTradingDays >= Math.max(1, minimumClosedTradingDays);
    }

    public double recentProfitableTradingDayRate() {
        return recentClosedTradingDays == 0
                ? 0.0
                : (double) recentProfitableTradingDays / recentClosedTradingDays;
    }
}
