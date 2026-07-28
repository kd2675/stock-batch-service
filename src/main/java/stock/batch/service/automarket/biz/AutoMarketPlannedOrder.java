package stock.batch.service.automarket.biz;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import stock.batch.service.automarket.profile.ProfileDecisionReason;
import stock.batch.service.automarket.v3.AutoParticipantDecisionUrgency;
import stock.batch.service.batch.automarket.model.AutoParticipantBehaviorModelVersion;
import stock.batch.service.batch.automarket.model.AutoParticipantFundingBudgetType;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.StockOrderOriginType;

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
        AutoParticipantBehaviorModelVersion behaviorModelVersion,
        StockOrderOriginType originType,
        AutoMarketOrderStrategyOrigin strategyOrigin,
        Long autoPolicyVersion,
        Long autoBehaviorEventSequence,
        AutoParticipantDecisionUrgency decisionUrgency
) {

    AutoMarketPlannedOrder(
            long accountId,
            String symbol,
            String side,
            BigDecimal price,
            long quantity,
            AutoParticipantFundingBudgetType fundingBudgetType,
            ProfileDecisionReason decisionReason,
            LocalDateTime expiresAt,
            AutoParticipantProfileType profileType,
            AutoParticipantBehaviorModelVersion behaviorModelVersion,
            StockOrderOriginType originType,
            AutoMarketOrderStrategyOrigin strategyOrigin
    ) {
        this(
                accountId,
                symbol,
                side,
                price,
                quantity,
                fundingBudgetType,
                decisionReason,
                expiresAt,
                profileType,
                behaviorModelVersion,
                originType,
                strategyOrigin,
                null,
                null,
                null
        );
    }

    AutoMarketPlannedOrder(long accountId, String symbol, String side, BigDecimal price, long quantity) {
        this(
                accountId,
                symbol,
                side,
                price,
                quantity,
                null,
                null,
                null,
                null,
                null,
                StockOrderOriginType.AUTO_PARTICIPANT,
                null,
                null,
                null,
                null
        );
    }

    AutoMarketPlannedOrder(
            long accountId,
            String symbol,
            String side,
            BigDecimal price,
            long quantity,
            StockOrderOriginType originType
    ) {
        this(
                accountId,
                symbol,
                side,
                price,
                quantity,
                null,
                null,
                null,
                null,
                null,
                originType,
                null,
                null,
                null,
                null
        );
    }

    AutoMarketPlannedOrder(
            long accountId,
            String symbol,
            String side,
            BigDecimal price,
            long quantity,
            AutoParticipantFundingBudgetType fundingBudgetType
    ) {
        this(
                accountId,
                symbol,
                side,
                price,
                quantity,
                fundingBudgetType,
                null,
                null,
                null,
                null,
                StockOrderOriginType.AUTO_PARTICIPANT,
                null,
                null,
                null,
                null
        );
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
        this(
                accountId,
                symbol,
                side,
                price,
                quantity,
                fundingBudgetType,
                decisionReason,
                null,
                null,
                null,
                StockOrderOriginType.AUTO_PARTICIPANT,
                null,
                null,
                null,
                null
        );
    }

    AutoMarketPlannedOrder(
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
        this(
                accountId,
                symbol,
                side,
                price,
                quantity,
                fundingBudgetType,
                decisionReason,
                expiresAt,
                profileType,
                behaviorModelVersion,
                StockOrderOriginType.AUTO_PARTICIPANT,
                null,
                null,
                null,
                null
        );
    }

    AutoMarketPlannedOrder {
        if (originType == null) {
            throw new IllegalArgumentException("Order origin type is required");
        }
        if (strategyOrigin != null && strategyOrigin.originType() != originType) {
            throw new IllegalArgumentException(
                    "Order origin and strategy-origin metadata must match"
            );
        }
        if ((originType == StockOrderOriginType.INSTITUTIONAL_INVESTOR
                || originType == StockOrderOriginType.LIQUIDITY_PROVIDER
                || originType == StockOrderOriginType.ISSUE_UNDERWRITER)
                && strategyOrigin == null) {
            throw new IllegalArgumentException(
                    "Institutional market-role orders require strategy-origin metadata"
            );
        }
        if (originType == StockOrderOriginType.AUTO_PARTICIPANT
                && behaviorModelVersion == AutoParticipantBehaviorModelVersion.V3
                && (autoPolicyVersion == null
                || autoPolicyVersion <= 0
                || autoBehaviorEventSequence == null
                || autoBehaviorEventSequence < 0
                || decisionUrgency == null)) {
            throw new IllegalArgumentException(
                    "V3 auto-participant orders require policy, event sequence, and urgency metadata"
            );
        }
    }

    BigDecimal reservedCash() {
        if (!"BUY".equals(side)) {
            return BigDecimal.ZERO;
        }
        return price.multiply(BigDecimal.valueOf(quantity));
    }
}
