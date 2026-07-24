package stock.batch.service.automarket.profile;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class LossAverseBehavior extends AbstractAutoProfileBehavior {

    public LossAverseBehavior() {
        super(AutoParticipantProfileType.LOSS_AVERSE, new ProfilePolicy(0.25, 0.10, 0.00, 0.95, 0.10, 0.00, 0.05, 0.05, 0.85, 0.80, 1.80, 0.08, 0.80, 0.05, 0.00, 0.75, 0.60).withPricePressureSensitivity(0.80));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.isLosing()) {
            return null;
        }
        return super.chooseSide(context);
    }

    @Override
    public ProfileDecision decide(ProfileSignalContext context) {
        if (!context.hasHolding()) {
            return super.decide(context);
        }
        if (context.isLosing()) {
            return ProfileDecision.hold(ProfileDecisionReason.LOSS_HOLD, Math.min(1.0, -context.unrealizedReturn()));
        }
        if (context.hasHolding() && context.unrealizedReturn() >= 0.05) {
            return signalDecision(SELL, ProfileDecisionReason.EXIT_THRESHOLD, 1, context.unrealizedReturn());
        }
        return super.decide(context);
    }
}
