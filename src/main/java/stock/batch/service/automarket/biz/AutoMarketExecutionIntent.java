package stock.batch.service.automarket.biz;

import stock.batch.service.automarket.profile.ProfileDecisionAction;

record AutoMarketExecutionIntent(
        ProfileDecisionAction action,
        double quantityMultiplier,
        int inventorySkewTicks,
        int minimumQuoteDepthTicks,
        long requestedQuantity
) {
    AutoMarketExecutionIntent(
            ProfileDecisionAction action,
            double quantityMultiplier,
            int inventorySkewTicks,
            int minimumQuoteDepthTicks
    ) {
        this(action, quantityMultiplier, inventorySkewTicks, minimumQuoteDepthTicks, 0L);
    }

    AutoMarketExecutionIntent {
        if (action == null || action == ProfileDecisionAction.HOLD) {
            throw new IllegalArgumentException("An execution intent requires BUY or SELL action");
        }
        quantityMultiplier = Math.clamp(quantityMultiplier, 0.0, 2.0);
        inventorySkewTicks = Math.clamp(inventorySkewTicks, -5, 5);
        minimumQuoteDepthTicks = Math.clamp(minimumQuoteDepthTicks, 0, 10);
        requestedQuantity = Math.max(0L, requestedQuantity);
    }

    static AutoMarketExecutionIntent directional(ProfileDecisionAction action) {
        return new AutoMarketExecutionIntent(action, 1.0, 0, 0, 0L);
    }
}
