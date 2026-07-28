package stock.batch.service.automarket.profile;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public record ProfileExecutionPolicy(
        double decisionFrequencyMultiplier,
        double ordersPerDecisionMultiplier,
        ProfilePricingMode pricingMode,
        ProfileExitMode exitMode,
        ProfileInventoryMode inventoryMode
) {
    static ProfileExecutionPolicy defaults(
            AutoParticipantProfileType profileType,
            double orderMultiplier,
            double orderTtlMultiplier
    ) {
        double normalizedOrderMultiplier = Math.max(0.0, orderMultiplier);
        double normalizedTtlMultiplier = Math.max(0.1, orderTtlMultiplier);
        ProfileExitMode exitMode = switch (profileType == null
                ? AutoParticipantProfileType.defaultType()
                : profileType) {
            case PROFIT_LOCKER -> ProfileExitMode.TAKE_PROFIT_FIRST;
            case LONG_TERM_HOLDER, PAYDAY_ACCUMULATOR, DIVIDEND_REINVESTOR, LIMIT_DOWN_TRAPPED ->
                    ProfileExitMode.HOLD_LOSSES;
            default -> ProfileExitMode.SIGNAL_DRIVEN;
        };
        return new ProfileExecutionPolicy(
                normalizedOrderMultiplier / normalizedTtlMultiplier,
                normalizedOrderMultiplier,
                ProfilePricingMode.DIRECTIONAL,
                exitMode,
                ProfileInventoryMode.SIGNAL_DRIVEN
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
