package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;

public record ProfileSignalContext(
        AutoParticipantStrategy strategy,
        AutoMarketConfig config,
        ProfilePolicy policy,
        int effectiveIntensity,
        double momentumPressure,
        double herdPressure,
        double unrealizedReturn,
        long availableQuantity,
        BigDecimal cashBalance,
        BigDecimal recentDividendCashAmount,
        boolean atLowerPriceLimit,
        int orderIndex,
        double noise
) {
    public ProfileSignalContext(
            AutoParticipantStrategy strategy,
            AutoMarketConfig config,
            ProfilePolicy policy,
            int effectiveIntensity,
            double momentumPressure,
            double herdPressure,
            double unrealizedReturn,
            long availableQuantity,
            BigDecimal cashBalance,
            boolean atLowerPriceLimit,
            int orderIndex,
            double noise
    ) {
        this(
                strategy,
                config,
                policy,
                effectiveIntensity,
                momentumPressure,
                herdPressure,
                unrealizedReturn,
                availableQuantity,
                cashBalance,
                BigDecimal.ZERO,
                atLowerPriceLimit,
                orderIndex,
                noise
        );
    }

    public boolean hasHolding() {
        return availableQuantity > 0;
    }

    public boolean canBuyOne() {
        return cashBalance != null && config != null && cashBalance.compareTo(config.currentPrice()) >= 0;
    }

    public boolean isWinning() {
        return unrealizedReturn > 0;
    }

    public boolean isLosing() {
        return unrealizedReturn < 0;
    }

    public boolean isDeepLoss() {
        return unrealizedReturn <= -0.25;
    }

    public boolean hasRecentDividendPayment() {
        return recentDividendCashAmount != null && recentDividendCashAmount.compareTo(BigDecimal.ZERO) > 0;
    }

    public double pricePressure() {
        return (clamp(effectiveIntensity, 1, 10) - 5.5) / 4.5;
    }

    public ProfileSignalContext withOrderIndex(int nextOrderIndex) {
        return new ProfileSignalContext(
                strategy,
                config,
                policy,
                effectiveIntensity,
                momentumPressure,
                herdPressure,
                unrealizedReturn,
                availableQuantity,
                cashBalance,
                recentDividendCashAmount,
                atLowerPriceLimit,
                nextOrderIndex,
                noise
        );
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
