package stock.batch.service.automarket.profile;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class LimitDownTrappedBehavior extends AbstractAutoProfileBehavior {

    public LimitDownTrappedBehavior() {
        super(AutoParticipantProfileType.LIMIT_DOWN_TRAPPED, new ProfilePolicy(0.20, 0.00, 0.20, 1.00, 0.05, 0.00, 0.00, 0.00, 0.55, 0.55, 2.50, 0.08, 0.50, 0.00, 0.25, 1.00, 1.00).withPricePressureSensitivity(0.40));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.atLowerPriceLimit() || context.isDeepLoss()) {
            return BUY;
        }
        return super.chooseSide(context);
    }

    @Override
    public ProfileDecision decide(ProfileSignalContext context) {
        if (context.atLowerPriceLimit()) {
            return context.hasHolding()
                    ? signalDecision(SELL, ProfileDecisionReason.PRICE_LIMIT_TRAP, 1, 1.0)
                    : ProfileDecision.hold(ProfileDecisionReason.PRICE_LIMIT_TRAP, 1.0);
        }
        if (!context.hasHolding()) {
            return super.decide(context);
        }
        if (context.isDeepLoss()) {
            return ProfileDecision.hold(ProfileDecisionReason.LOSS_HOLD, Math.min(1.0, -context.unrealizedReturn()));
        }
        return super.decide(context);
    }
}
