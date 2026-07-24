package stock.batch.service.automarket.biz;

import stock.batch.service.automarket.profile.ProfileDecision;
import stock.batch.service.automarket.profile.ProfileDecisionAction;
import stock.batch.service.automarket.profile.ProfileDecisionReason;
import stock.batch.service.automarket.profile.ProfileInventoryMode;
import stock.batch.service.automarket.profile.ProfilePricingMode;
import stock.batch.service.automarket.profile.ProfileSignalContext;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

final class AutoMarketExecutionStylePlanner {

    AutoMarketExecutionIntent intentFor(
            AutoParticipantProfileType profileType,
            ProfileSignalContext context,
            ProfileDecision decision,
            int orderIndex,
            int totalOrderCount
    ) {
        if (decision.action() == ProfileDecisionAction.HOLD || totalOrderCount <= 0) {
            throw new IllegalArgumentException("A HOLD decision cannot be converted into an execution intent");
        }
        if (profileType == AutoParticipantProfileType.MARKET_MAKER
                && context.policy().executionPolicy().inventoryMode()
                == ProfileInventoryMode.TARGET_ALLOCATION
                && context.policy().executionPolicy().pricingMode() == ProfilePricingMode.MARKET_MAKING) {
            return marketMakerIntent(context, decision, orderIndex);
        }
        if (decision.action() == ProfileDecisionAction.SELL
                && (decision.reason() == ProfileDecisionReason.SESSION_CLOSE
                || decision.reason() == ProfileDecisionReason.HOLDING_PERIOD)) {
            int remainingOrders = Math.max(1, totalOrderCount - orderIndex);
            long requestedQuantity = divideRoundingUp(context.availableQuantity(), remainingOrders);
            return new AutoMarketExecutionIntent(
                    ProfileDecisionAction.SELL,
                    1.0,
                    0,
                    0,
                    requestedQuantity
            );
        }
        if (decision.action() == ProfileDecisionAction.SELL
                && (profileType == AutoParticipantProfileType.STOP_LOSS_TRADER
                || profileType == AutoParticipantProfileType.SCALPER)) {
            return new AutoMarketExecutionIntent(
                    ProfileDecisionAction.SELL,
                    1.0,
                    0,
                    0,
                    context.availableQuantity()
            );
        }
        if (profileType == AutoParticipantProfileType.PROFIT_LOCKER
                && decision.action() == ProfileDecisionAction.SELL
                && decision.reason() == ProfileDecisionReason.EXIT_THRESHOLD) {
            long requestedQuantity = Math.max(1L, Math.round(context.availableQuantity() * 0.35));
            return new AutoMarketExecutionIntent(
                    ProfileDecisionAction.SELL,
                    1.0,
                    0,
                    0,
                    requestedQuantity
            );
        }
        return AutoMarketExecutionIntent.directional(decision.action());
    }

    private AutoMarketExecutionIntent marketMakerIntent(
            ProfileSignalContext context,
            ProfileDecision decision,
            int orderIndex
    ) {
        if (decision.reason() == ProfileDecisionReason.INVENTORY_BALANCED) {
            double normalizedDeviation = context.marketMakerNormalizedInventoryDeviation();
            int inventorySkewTicks = -(int) Math.round(normalizedDeviation * 2.0);
            boolean buy = Math.floorMod(orderIndex, 2) == 0;
            double quantityMultiplier = buy
                    ? Math.clamp(1.0 - normalizedDeviation * 0.60, 0.40, 1.60)
                    : Math.clamp(1.0 + normalizedDeviation * 0.60, 0.40, 1.60);
            return new AutoMarketExecutionIntent(
                    buy ? ProfileDecisionAction.BUY : ProfileDecisionAction.SELL,
                    quantityMultiplier,
                    inventorySkewTicks,
                    1
            );
        }
        int inventorySkewTicks = decision.action() == ProfileDecisionAction.BUY ? 1 : -1;
        double quantityMultiplier = Math.clamp(1.0 + decision.signalStrength() * 0.50, 1.0, 1.50);
        return new AutoMarketExecutionIntent(
                decision.action(),
                quantityMultiplier,
                inventorySkewTicks,
                1
        );
    }

    private long divideRoundingUp(long dividend, int divisor) {
        if (dividend <= 0) {
            return 0L;
        }
        return Math.floorDiv(dividend + divisor - 1L, divisor);
    }
}
