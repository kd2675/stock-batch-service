package stock.batch.service.automarket.biz;

import stock.batch.service.automarket.profile.ProfileDecision;
import stock.batch.service.automarket.profile.ProfileDecisionAction;
import stock.batch.service.automarket.profile.ProfileDecisionReason;
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

    private long divideRoundingUp(long dividend, int divisor) {
        if (dividend <= 0) {
            return 0L;
        }
        return Math.floorDiv(dividend + divisor - 1L, divisor);
    }
}
