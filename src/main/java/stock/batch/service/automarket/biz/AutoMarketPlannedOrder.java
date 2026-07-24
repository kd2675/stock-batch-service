package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import stock.batch.service.automarket.profile.ProfileDecisionReason;
import stock.batch.service.batch.automarket.model.AutoParticipantBehaviorModelVersion;
import stock.batch.service.batch.automarket.model.AutoParticipantFundingBudgetType;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

record AutoMarketPlannedOrder(
        long accountId,
        String symbol,
        String side,
        BigDecimal price,
        long quantity,
        AutoParticipantFundingBudgetType fundingBudgetType,
        ProfileDecisionReason decisionReason,
        LocalDateTime expiresAt,
        AutoParticipantProfileType profileType,
        AutoParticipantBehaviorModelVersion behaviorModelVersion
) {

    AutoMarketPlannedOrder(long accountId, String symbol, String side, BigDecimal price, long quantity) {
        this(accountId, symbol, side, price, quantity, null, null, null, null, null);
    }

    AutoMarketPlannedOrder(
            long accountId,
            String symbol,
            String side,
            BigDecimal price,
            long quantity,
            AutoParticipantFundingBudgetType fundingBudgetType
    ) {
        this(accountId, symbol, side, price, quantity, fundingBudgetType, null, null, null, null);
    }

    AutoMarketPlannedOrder(
            long accountId,
            String symbol,
            String side,
            BigDecimal price,
            long quantity,
            AutoParticipantFundingBudgetType fundingBudgetType,
            ProfileDecisionReason decisionReason
    ) {
        this(accountId, symbol, side, price, quantity, fundingBudgetType, decisionReason, null, null, null);
    }

    BigDecimal reservedCash() {
        if (!"BUY".equals(side)) {
            return BigDecimal.ZERO;
        }
        return price.multiply(BigDecimal.valueOf(quantity));
    }
}
