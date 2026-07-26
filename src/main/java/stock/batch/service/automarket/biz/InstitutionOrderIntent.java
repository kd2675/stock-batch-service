package stock.batch.service.automarket.biz;

import java.math.BigDecimal;

record InstitutionOrderIntent(
        long decisionRunId,
        String symbol,
        long portfolioId,
        long participantId,
        long accountId,
        String side,
        long requestedQuantity,
        BigDecimal plannedAmount,
        long referenceDailyVolume,
        BigDecimal executionAggressionPressure,
        long policyVersion,
        long deterministicSeed,
        String portfolioStatus,
        String executionMode,
        String accountStatus,
        String participantCategory,
        String accountSelfTradeGroupId,
        String participantStatus,
        String participantType,
        String participantSelfTradeGroupId,
        String accountRole,
        String mappingStatus
) {

    String validationFailure() {
        if (!"ACTIVE".equals(portfolioStatus) || !"PILOT".equals(executionMode)) {
            return "PORTFOLIO_NOT_ACTIVE_PILOT";
        }
        if (!"ACTIVE".equals(accountStatus)
                || !"INSTITUTIONAL_INVESTOR".equals(participantCategory)) {
            return "ACCOUNT_ROLE_INVALID";
        }
        if (!"ACTIVE".equals(participantStatus)
                || !"INSTITUTIONAL_INVESTOR".equals(participantType)
                || !"INSTITUTIONAL_INVESTOR".equals(accountRole)
                || !"ACTIVE".equals(mappingStatus)) {
            return "PARTICIPANT_MAPPING_INVALID";
        }
        if (accountSelfTradeGroupId == null
                || accountSelfTradeGroupId.isBlank()
                || !accountSelfTradeGroupId.equals(participantSelfTradeGroupId)) {
            return "SELF_TRADE_GROUP_MISMATCH";
        }
        if ((!"BUY".equals(side) && !"SELL".equals(side))
                || requestedQuantity <= 0L
                || plannedAmount == null
                || plannedAmount.signum() <= 0
                || referenceDailyVolume <= 0L
                || policyVersion <= 0L) {
            return "INTENT_CONTRACT_INVALID";
        }
        return null;
    }
}
