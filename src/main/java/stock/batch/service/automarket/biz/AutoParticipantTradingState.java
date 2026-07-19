package stock.batch.service.automarket.biz;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantTradingSnapshot;

final class AutoParticipantTradingState {

    private static final String BUY = "BUY";
    private static final String SELL = "SELL";

    private final long accountId;
    private BigDecimal cashBalance;
    private long availableQuantity;
    private final BigDecimal averagePrice;
    private final BigDecimal recentDividendCashAmount;
    private long openBuyQuantity;
    private long openSellQuantity;

    private AutoParticipantTradingState(
            long accountId,
            BigDecimal cashBalance,
            long availableQuantity,
            BigDecimal averagePrice,
            BigDecimal recentDividendCashAmount,
            long openBuyQuantity,
            long openSellQuantity
    ) {
        this.accountId = accountId;
        this.cashBalance = zeroIfNull(cashBalance);
        this.availableQuantity = Math.max(0L, availableQuantity);
        this.averagePrice = zeroIfNull(averagePrice);
        this.recentDividendCashAmount = zeroIfNull(recentDividendCashAmount);
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
                snapshot.openSellQuantity()
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
                0L
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

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
