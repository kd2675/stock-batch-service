package stock.batch.service.automarket.profile;

import java.math.BigDecimal;
import java.time.LocalDate;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;

public record ProfileSignalContext(
        AutoParticipantStrategy strategy,
        AutoMarketConfig config,
        ProfilePolicy policy,
        int activityLevel,
        double momentumPressure,
        double herdPressure,
        double unrealizedReturn,
        long availableQuantity,
        BigDecimal cashBalance,
        BigDecimal recentDividendCashAmount,
        boolean atLowerPriceLimit,
        int orderIndex,
        double noise,
        ParticipantPortfolioSnapshot portfolio,
        MarketSignalSnapshot marketSignals,
        FundingBudgetSnapshot fundingBudgets,
        BehavioralMemory behavioralMemory
) {
    public ProfileSignalContext {
        recentDividendCashAmount = recentDividendCashAmount == null ? BigDecimal.ZERO : recentDividendCashAmount;
        cashBalance = cashBalance == null ? BigDecimal.ZERO : cashBalance;
        portfolio = portfolio == null ? ParticipantPortfolioSnapshot.EMPTY : portfolio;
        marketSignals = marketSignals == null ? MarketSignalSnapshot.EMPTY : marketSignals;
        fundingBudgets = fundingBudgets == null ? FundingBudgetSnapshot.EMPTY : fundingBudgets;
        behavioralMemory = behavioralMemory == null ? BehavioralMemory.EMPTY : behavioralMemory;
    }

    public ProfileSignalContext(
            AutoParticipantStrategy strategy,
            AutoMarketConfig config,
            ProfilePolicy policy,
            int activityLevel,
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
        this(
                strategy,
                config,
                policy,
                activityLevel,
                momentumPressure,
                herdPressure,
                unrealizedReturn,
                availableQuantity,
                cashBalance,
                recentDividendCashAmount,
                atLowerPriceLimit,
                orderIndex,
                noise,
                ParticipantPortfolioSnapshot.EMPTY,
                MarketSignalSnapshot.EMPTY,
                FundingBudgetSnapshot.EMPTY,
                BehavioralMemory.EMPTY
        );
    }

    public ProfileSignalContext(
            AutoParticipantStrategy strategy,
            AutoMarketConfig config,
            ProfilePolicy policy,
            int activityLevel,
            double momentumPressure,
            double herdPressure,
            double unrealizedReturn,
            long availableQuantity,
            BigDecimal cashBalance,
            BigDecimal recentDividendCashAmount,
            boolean atLowerPriceLimit,
            int orderIndex,
            double noise,
            ParticipantPortfolioSnapshot portfolio,
            MarketSignalSnapshot marketSignals
    ) {
        this(
                strategy,
                config,
                policy,
                activityLevel,
                momentumPressure,
                herdPressure,
                unrealizedReturn,
                availableQuantity,
                cashBalance,
                recentDividendCashAmount,
                atLowerPriceLimit,
                orderIndex,
                noise,
                portfolio,
                marketSignals,
                FundingBudgetSnapshot.EMPTY,
                BehavioralMemory.EMPTY
        );
    }

    public ProfileSignalContext(
            AutoParticipantStrategy strategy,
            AutoMarketConfig config,
            ProfilePolicy policy,
            int activityLevel,
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
                activityLevel,
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
        return availableQuantity > 0 || portfolio.holdingQuantity() > 0;
    }

    public boolean hasAvailableHolding() {
        return availableQuantity > 0;
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

    public boolean isFirstOrder() {
        return orderIndex == 0;
    }

    public boolean isAdditionalOrder() {
        return orderIndex > 0;
    }

    public boolean isThirdOrLaterOrder() {
        return orderIndex > 1;
    }

    public boolean hasRecentDividendPayment() {
        return recentDividendCashAmount != null && recentDividendCashAmount.compareTo(BigDecimal.ZERO) > 0;
    }

    public double inventoryToOrderCapacityRatio() {
        if (availableQuantity <= 0) {
            return 0.0;
        }
        long capacity = config == null ? 1L : Math.max(1L, config.maxOrderQuantity());
        return (double) availableQuantity / capacity;
    }

    public double stockAllocationRatio() {
        if (config == null || config.currentPrice() == null) {
            return 0.0;
        }
        long allocationQuantity = portfolio.holdingQuantity() > 0
                ? portfolio.holdingQuantity()
                : availableQuantity;
        if (allocationQuantity <= 0) {
            return 0.0;
        }
        BigDecimal stockValue = config.currentPrice().max(BigDecimal.ZERO)
                .multiply(BigDecimal.valueOf(allocationQuantity));
        if (portfolio.liquidAsset().signum() > 0) {
            return Math.clamp(
                    stockValue.divide(portfolio.liquidAsset(), 8, java.math.RoundingMode.HALF_UP).doubleValue(),
                    0.0,
                    1.0
            );
        }
        BigDecimal availableCash = cashBalance == null ? BigDecimal.ZERO : cashBalance.max(BigDecimal.ZERO);
        BigDecimal total = stockValue.add(availableCash);
        if (total.signum() <= 0) {
            return 0.0;
        }
        return Math.clamp(stockValue.divide(total, 8, java.math.RoundingMode.HALF_UP).doubleValue(), 0.0, 1.0);
    }

    public double cashAllocationRatio() {
        if (portfolio.liquidAsset().signum() <= 0) {
            return 0.0;
        }
        return Math.clamp(
                cashBalance.max(BigDecimal.ZERO)
                        .divide(portfolio.liquidAsset(), 8, java.math.RoundingMode.HALF_UP)
                        .doubleValue(),
                0.0,
                1.0
        );
    }

    public double symbolConcentrationRatio() {
        return stockAllocationRatio();
    }

    public double marketMakerTargetAllocationRatio() {
        return 0.50 / Math.max(1, portfolio.eligibleSymbolCount());
    }

    public double marketMakerLowerAllocationRatio() {
        return marketMakerTargetAllocationRatio() * 0.80;
    }

    public double marketMakerUpperAllocationRatio() {
        return marketMakerTargetAllocationRatio() * 1.20;
    }

    public double marketMakerNormalizedInventoryDeviation() {
        double target = marketMakerTargetAllocationRatio();
        double allocation = stockAllocationRatio();
        double denominator = allocation >= target
                ? marketMakerUpperAllocationRatio() - target
                : target - marketMakerLowerAllocationRatio();
        if (denominator <= 0.0) {
            return 0.0;
        }
        return Math.clamp((allocation - target) / denominator, -1.0, 1.0);
    }

    public double projectedStockAllocationRatio() {
        if (portfolio.liquidAsset().signum() <= 0) {
            return stockAllocationRatio();
        }
        BigDecimal nonNegativePrice = config.currentPrice().max(BigDecimal.ZERO);
        BigDecimal projectedStockValue = nonNegativePrice
                .multiply(BigDecimal.valueOf(Math.max(0L, portfolio.holdingQuantity())))
                .add(nonNegativePrice.multiply(BigDecimal.valueOf(portfolio.symbolOpenBuyQuantity())));
        return Math.clamp(
                projectedStockValue.divide(portfolio.liquidAsset(), 8, java.math.RoundingMode.HALF_UP)
                        .doubleValue(),
                0.0,
                1.0
        );
    }

    public LocalDate businessDate() {
        return strategy == null || strategy.decisionSlotAt() == null
                ? null
                : strategy.decisionSlotAt().toLocalDate();
    }

    public double pricePressure() {
        if (config == null) {
            return 0.0;
        }
        double newsWeight = policy == null ? 0.0 : policy.newsWeight();
        double pressure = config.dailyPricePressure() + config.reportPricePressure() * newsWeight * 0.55;
        return Math.clamp(pressure, -1.0, 1.0);
    }

    public double assetPreferencePressure() {
        if (config == null) {
            return 0.0;
        }
        return Math.clamp(config.assetPreferencePressure(), -1.0, 1.0);
    }

    public ProfileSignalContext withOrderIndex(int nextOrderIndex) {
        return new ProfileSignalContext(
                strategy,
                config,
                policy,
                activityLevel,
                momentumPressure,
                herdPressure,
                unrealizedReturn,
                availableQuantity,
                cashBalance,
                recentDividendCashAmount,
                atLowerPriceLimit,
                nextOrderIndex,
                noise,
                portfolio,
                marketSignals,
                fundingBudgets,
                behavioralMemory
        );
    }

}
