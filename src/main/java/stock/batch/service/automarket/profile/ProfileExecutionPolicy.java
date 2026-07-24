package stock.batch.service.automarket.profile;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public record ProfileExecutionPolicy(
        double decisionFrequencyMultiplier,
        double ordersPerDecisionMultiplier,
        ProfilePricingMode pricingMode,
        ProfileExitMode exitMode,
        ProfileInventoryMode inventoryMode
) {
    static ProfileExecutionPolicy v2Default(
            AutoParticipantProfileType profileType,
            double orderMultiplier,
            double orderTtlMultiplier
    ) {
        double normalizedOrderMultiplier = Math.max(0.0, orderMultiplier);
        double normalizedTtlMultiplier = Math.max(0.1, orderTtlMultiplier);
        ProfilePricingMode pricingMode = profileType == AutoParticipantProfileType.MARKET_MAKER
                ? ProfilePricingMode.MARKET_MAKING
                : ProfilePricingMode.DIRECTIONAL;
        ProfileExitMode exitMode = switch (profileType) {
            case PROFIT_LOCKER -> ProfileExitMode.TAKE_PROFIT_FIRST;
            case LONG_TERM_HOLDER, PAYDAY_ACCUMULATOR, DIVIDEND_REINVESTOR, LIMIT_DOWN_TRAPPED ->
                    ProfileExitMode.HOLD_LOSSES;
            default -> ProfileExitMode.SIGNAL_DRIVEN;
        };
        ProfileInventoryMode inventoryMode = profileType == AutoParticipantProfileType.MARKET_MAKER
                ? ProfileInventoryMode.TARGET_ALLOCATION
                : ProfileInventoryMode.SIGNAL_DRIVEN;
        return new ProfileExecutionPolicy(
                normalizedOrderMultiplier / normalizedTtlMultiplier,
                normalizedOrderMultiplier,
                pricingMode,
                exitMode,
                inventoryMode
        );
    }

    static ProfileExecutionPolicy legacy(
            double orderMultiplier,
            double orderTtlMultiplier,
            double marketMakingWeight,
            double profitTakingWeight,
            double holdingPatienceWeight
    ) {
        double normalizedOrderMultiplier = Math.max(0.0, orderMultiplier);
        double legacyActivityMultiplier = Math.max(0.25, normalizedOrderMultiplier);
        double legacyPatienceMultiplier = Math.max(0.25, orderTtlMultiplier);
        return new ProfileExecutionPolicy(
                legacyActivityMultiplier / legacyPatienceMultiplier,
                normalizedOrderMultiplier,
                marketMakingWeight >= 0.8 ? ProfilePricingMode.MARKET_MAKING : ProfilePricingMode.DIRECTIONAL,
                profitTakingWeight >= 0.90
                        ? ProfileExitMode.TAKE_PROFIT_FIRST
                        : holdingPatienceWeight >= 0.85
                                ? ProfileExitMode.HOLD_LOSSES
                                : ProfileExitMode.SIGNAL_DRIVEN,
                marketMakingWeight >= 0.8
                        ? ProfileInventoryMode.TARGET_ALLOCATION
                        : ProfileInventoryMode.SIGNAL_DRIVEN
        );
    }

    public ProfileExecutionPolicy {
        decisionFrequencyMultiplier = Math.max(0.0, decisionFrequencyMultiplier);
        ordersPerDecisionMultiplier = Math.max(0.0, ordersPerDecisionMultiplier);
        pricingMode = pricingMode == null ? ProfilePricingMode.DIRECTIONAL : pricingMode;
        exitMode = exitMode == null ? ProfileExitMode.SIGNAL_DRIVEN : exitMode;
        inventoryMode = inventoryMode == null ? ProfileInventoryMode.SIGNAL_DRIVEN : inventoryMode;
    }
}
