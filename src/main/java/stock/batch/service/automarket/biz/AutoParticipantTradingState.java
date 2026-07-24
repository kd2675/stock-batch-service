package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.time.LocalDate;

import stock.batch.service.batch.automarket.model.AutoParticipantTradingSnapshot;

final class AutoParticipantTradingState {

    private static final String BUY = "BUY";
    private static final String SELL = "SELL";

    private final long accountId;
    private BigDecimal cashBalance;
    private long availableQuantity;
    private final BigDecimal averagePrice;
    private final BigDecimal recentDividendCashAmount;
    private final long holdingQuantity;
    private final long reservedQuantity;
    private final BigDecimal openBuyReservedCash;
    private final BigDecimal portfolioHoldingMarketValue;
    private final BigDecimal liquidPortfolioAsset;
    private final int portfolioPositionCount;
    private BigDecimal paydayAvailableBudget;
    private BigDecimal dividendAvailableBudget;
    private final LocalDate positionOpenedBusinessDate;
    private final int holdingTradingDays;
    private final int averageDownRounds;
    private final LocalDate lastAverageDownBusinessDate;
    private final BigDecimal peakClosePrice;
    private final int recentProfitableTradingDays;
    private final int recentClosedTradingDays;
    private long openBuyQuantity;
    private long openSellQuantity;

    private AutoParticipantTradingState(
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
        this.accountId = accountId;
        this.cashBalance = zeroIfNull(cashBalance);
        this.availableQuantity = Math.max(0L, availableQuantity);
        this.averagePrice = zeroIfNull(averagePrice);
        this.recentDividendCashAmount = zeroIfNull(recentDividendCashAmount);
        this.holdingQuantity = Math.max(0L, holdingQuantity);
        this.reservedQuantity = Math.max(0L, reservedQuantity);
        this.openBuyReservedCash = zeroIfNull(openBuyReservedCash);
        this.portfolioHoldingMarketValue = zeroIfNull(portfolioHoldingMarketValue);
        this.liquidPortfolioAsset = zeroIfNull(liquidPortfolioAsset);
        this.portfolioPositionCount = Math.max(0, portfolioPositionCount);
        this.paydayAvailableBudget = zeroIfNull(paydayAvailableBudget);
        this.dividendAvailableBudget = zeroIfNull(dividendAvailableBudget);
        this.positionOpenedBusinessDate = positionOpenedBusinessDate;
        this.holdingTradingDays = Math.max(0, holdingTradingDays);
        this.averageDownRounds = Math.max(0, averageDownRounds);
        this.lastAverageDownBusinessDate = lastAverageDownBusinessDate;
        this.peakClosePrice = zeroIfNull(peakClosePrice);
        this.recentClosedTradingDays = Math.clamp(recentClosedTradingDays, 0, 20);
        this.recentProfitableTradingDays = Math.clamp(
                recentProfitableTradingDays,
                0,
                this.recentClosedTradingDays
        );
        this.openBuyQuantity = Math.max(0L, openBuyQuantity);
        this.openSellQuantity = Math.max(0L, openSellQuantity);
    }

    static AutoParticipantTradingState from(AutoParticipantTradingSnapshot snapshot) {
        return new AutoParticipantTradingState(
                snapshot.accountId(),
                snapshot.cashBalance(),
                snapshot.availableQuantity(),
                snapshot.averagePrice(),
                snapshot.recentDividendCashAmount(),
                snapshot.openBuyQuantity(),
                snapshot.openSellQuantity(),
                snapshot.holdingQuantity(),
                snapshot.reservedQuantity(),
                snapshot.openBuyReservedCash(),
                snapshot.portfolioHoldingMarketValue(),
                snapshot.liquidPortfolioAsset(),
                snapshot.portfolioPositionCount(),
                snapshot.paydayAvailableBudget(),
                snapshot.dividendAvailableBudget(),
                snapshot.positionOpenedBusinessDate(),
                snapshot.holdingTradingDays(),
                snapshot.averageDownRounds(),
                snapshot.lastAverageDownBusinessDate(),
                snapshot.peakClosePrice(),
                snapshot.recentProfitableTradingDays(),
                snapshot.recentClosedTradingDays()
        );
    }

    static AutoParticipantTradingState empty(long accountId) {
        return new AutoParticipantTradingState(
                accountId,
                BigDecimal.ZERO,
                0L,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0L,
                0L,
                0L,
                0L,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0,
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

    long accountId() {
        return accountId;
    }

    BigDecimal cashBalance() {
        return cashBalance;
    }

    long availableQuantity() {
        return availableQuantity;
    }

    BigDecimal averagePrice() {
        return averagePrice;
    }

    BigDecimal recentDividendCashAmount() {
        return recentDividendCashAmount;
    }

    long holdingQuantity() {
        return holdingQuantity;
    }

    long reservedQuantity() {
        return reservedQuantity;
    }

    BigDecimal openBuyReservedCash() {
        return openBuyReservedCash;
    }

    BigDecimal portfolioHoldingMarketValue() {
        return portfolioHoldingMarketValue;
    }

    BigDecimal liquidPortfolioAsset() {
        return liquidPortfolioAsset;
    }

    int portfolioPositionCount() {
        return portfolioPositionCount;
    }

    long openBuyQuantity() {
        return openBuyQuantity;
    }

    BigDecimal paydayAvailableBudget() {
        return paydayAvailableBudget;
    }

    BigDecimal dividendAvailableBudget() {
        return dividendAvailableBudget;
    }

    LocalDate positionOpenedBusinessDate() {
        return positionOpenedBusinessDate;
    }

    int holdingTradingDays() {
        return holdingTradingDays;
    }

    int averageDownRounds() {
        return averageDownRounds;
    }

    LocalDate lastAverageDownBusinessDate() {
        return lastAverageDownBusinessDate;
    }

    BigDecimal peakClosePrice() {
        return peakClosePrice;
    }

    int recentProfitableTradingDays() {
        return recentProfitableTradingDays;
    }

    int recentClosedTradingDays() {
        return recentClosedTradingDays;
    }

    long remainingOpenCapacity(String side, long maxOpenQuantity) {
        long currentOpenQuantity = BUY.equals(side) ? openBuyQuantity : openSellQuantity;
        return Math.max(0L, maxOpenQuantity - currentOpenQuantity);
    }

    void reserve(String side, BigDecimal price, long quantity) {
        if (BUY.equals(side)) {
            cashBalance = cashBalance.subtract(price.multiply(BigDecimal.valueOf(quantity))).max(BigDecimal.ZERO);
            openBuyQuantity += quantity;
            return;
        }
        if (SELL.equals(side)) {
            availableQuantity = Math.max(0L, availableQuantity - quantity);
            openSellQuantity += quantity;
        }
    }

    void reserveFundingBudget(
            stock.batch.service.batch.automarket.model.AutoParticipantFundingBudgetType budgetType,
            BigDecimal amount
    ) {
        if (budgetType == null || amount == null || amount.signum() <= 0) {
            return;
        }
        if (budgetType == stock.batch.service.batch.automarket.model.AutoParticipantFundingBudgetType.PAYDAY) {
            paydayAvailableBudget = paydayAvailableBudget.subtract(amount).max(BigDecimal.ZERO);
        } else {
            dividendAvailableBudget = dividendAvailableBudget.subtract(amount).max(BigDecimal.ZERO);
        }
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
