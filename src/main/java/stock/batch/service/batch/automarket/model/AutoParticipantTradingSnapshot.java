package stock.batch.service.batch.automarket.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AutoParticipantTradingSnapshot(
        long accountId,
        BigDecimal cashBalance,
        long availableQuantity,
        BigDecimal averagePrice,
        BigDecimal recentDividendCashAmount,
        long openBuyQuantity,
        long openSellQuantity,
        long holdingQuantity,
        long reservedQuantity,
        BigDecimal openBuyReservedCash,
        BigDecimal portfolioHoldingMarketValue,
        BigDecimal liquidPortfolioAsset,
        int portfolioPositionCount,
        BigDecimal paydayAvailableBudget,
        BigDecimal dividendAvailableBudget,
        LocalDate positionOpenedBusinessDate,
        int holdingTradingDays,
        int averageDownRounds,
        LocalDate lastAverageDownBusinessDate,
        BigDecimal peakClosePrice,
        int recentProfitableTradingDays,
        int recentClosedTradingDays
) {
    public AutoParticipantTradingSnapshot(
            long accountId,
            BigDecimal cashBalance,
            long availableQuantity,
            BigDecimal averagePrice,
            BigDecimal recentDividendCashAmount,
            long openBuyQuantity,
            long openSellQuantity
    ) {
        this(
                accountId,
                cashBalance,
                availableQuantity,
                averagePrice,
                recentDividendCashAmount,
                openBuyQuantity,
                openSellQuantity,
                availableQuantity + openSellQuantity,
                openSellQuantity,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                zeroIfNull(cashBalance),
                availableQuantity > 0 ? 1 : 0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                0,
                0,
                null,
                BigDecimal.ZERO,
                0,
                0
        );
    }

    public AutoParticipantTradingSnapshot(
            long accountId,
            BigDecimal cashBalance,
            long availableQuantity,
            BigDecimal averagePrice,
            BigDecimal recentDividendCashAmount,
            long openBuyQuantity,
            long openSellQuantity,
            long holdingQuantity,
            long reservedQuantity,
            BigDecimal openBuyReservedCash,
            BigDecimal portfolioHoldingMarketValue,
            BigDecimal liquidPortfolioAsset,
            int portfolioPositionCount
    ) {
        this(
                accountId,
                cashBalance,
                availableQuantity,
                averagePrice,
                recentDividendCashAmount,
                openBuyQuantity,
                openSellQuantity,
                holdingQuantity,
                reservedQuantity,
                openBuyReservedCash,
                portfolioHoldingMarketValue,
                liquidPortfolioAsset,
                portfolioPositionCount,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                0,
                0,
                null,
                BigDecimal.ZERO,
                0,
                0
        );
    }

    public AutoParticipantTradingSnapshot {
        cashBalance = zeroIfNull(cashBalance);
        availableQuantity = Math.max(0L, availableQuantity);
        averagePrice = zeroIfNull(averagePrice);
        recentDividendCashAmount = zeroIfNull(recentDividendCashAmount);
        openBuyQuantity = Math.max(0L, openBuyQuantity);
        openSellQuantity = Math.max(0L, openSellQuantity);
        holdingQuantity = Math.max(0L, holdingQuantity);
        reservedQuantity = Math.max(0L, reservedQuantity);
        openBuyReservedCash = zeroIfNull(openBuyReservedCash);
        portfolioHoldingMarketValue = zeroIfNull(portfolioHoldingMarketValue);
        liquidPortfolioAsset = zeroIfNull(liquidPortfolioAsset);
        portfolioPositionCount = Math.max(0, portfolioPositionCount);
        paydayAvailableBudget = zeroIfNull(paydayAvailableBudget);
        dividendAvailableBudget = zeroIfNull(dividendAvailableBudget);
        holdingTradingDays = Math.max(0, holdingTradingDays);
        averageDownRounds = Math.max(0, averageDownRounds);
        peakClosePrice = zeroIfNull(peakClosePrice);
        recentClosedTradingDays = Math.clamp(recentClosedTradingDays, 0, 20);
        recentProfitableTradingDays = Math.clamp(recentProfitableTradingDays, 0, recentClosedTradingDays);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
