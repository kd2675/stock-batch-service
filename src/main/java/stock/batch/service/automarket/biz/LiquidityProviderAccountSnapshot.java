package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.time.LocalDate;

record LiquidityProviderAccountSnapshot(
        long accountId,
        String accountStatus,
        String participantCategory,
        String accountSelfTradeGroupId,
        BigDecimal cashBalance,
        long holdingQuantity,
        long reservedQuantity,
        BigDecimal averagePrice,
        long participantId,
        String participantType,
        String participantStatus,
        String participantSelfTradeGroupId,
        String accountRole,
        String deskCode,
        String mappingStatus,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        int nonLiquidityOpenOrderCount,
        int unmanagedHoldingCount
) {

    BigDecimal availableCash() {
        return cashBalance == null || cashBalance.signum() < 0 ? BigDecimal.ZERO : cashBalance;
    }

    long availableQuantity() {
        return Math.max(0L, holdingQuantity - reservedQuantity);
    }

    String eligibilityFailure(LiquidityProviderMandate mandate, LocalDate tradeDate) {
        if (accountId != mandate.accountId() || participantId != mandate.participantId()) {
            return "ROLE_MAPPING_MISMATCH";
        }
        if (!"ACTIVE".equals(accountStatus)
                || !"LIQUIDITY_PROVIDER".equals(participantCategory)) {
            return "ACCOUNT_NOT_ELIGIBLE";
        }
        if (!"ACTIVE".equals(participantStatus)
                || !"LIQUIDITY_PROVIDER".equals(participantType)) {
            return "PARTICIPANT_NOT_ELIGIBLE";
        }
        if (!"ACTIVE".equals(mappingStatus)
                || !"LIQUIDITY_PROVIDER".equals(accountRole)
                || effectiveFrom == null
                || tradeDate.isBefore(effectiveFrom)
                || (effectiveTo != null && tradeDate.isAfter(effectiveTo))) {
            return "ROLE_MAPPING_NOT_EFFECTIVE";
        }
        if (!mandate.symbol().equals(deskCode)) {
            return "ROLE_DESK_SYMBOL_MISMATCH";
        }
        if (accountSelfTradeGroupId == null
                || accountSelfTradeGroupId.isBlank()
                || !accountSelfTradeGroupId.equals(participantSelfTradeGroupId)) {
            return "SELF_TRADE_GROUP_MISMATCH";
        }
        if (holdingQuantity < 0 || reservedQuantity < 0 || reservedQuantity > holdingQuantity) {
            return "INVALID_INVENTORY_STATE";
        }
        if (cashBalance == null || cashBalance.signum() < 0) {
            return "INVALID_CASH_STATE";
        }
        if (nonLiquidityOpenOrderCount > 0) {
            return "NON_LP_OPEN_ORDER_ON_DEDICATED_ACCOUNT";
        }
        if (unmanagedHoldingCount > 0) {
            return "UNMANAGED_HOLDING_ON_DEDICATED_ACCOUNT";
        }
        return null;
    }

    BigDecimal unrealizedProfit(BigDecimal currentPrice) {
        if (holdingQuantity <= 0L
                || currentPrice == null
                || currentPrice.signum() <= 0
                || averagePrice == null
                || averagePrice.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return currentPrice.subtract(averagePrice)
                .multiply(BigDecimal.valueOf(holdingQuantity))
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
