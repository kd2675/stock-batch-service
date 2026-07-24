package stock.batch.service.automarket.profile;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class SmallDiversifierBehavior extends AbstractAutoProfileBehavior {

    public SmallDiversifierBehavior() {
        super(AutoParticipantProfileType.SMALL_DIVERSIFIER, new ProfilePolicy(0.25, 0.20, 0.10, 0.30, 0.10, 0.00, 0.05, 0.25, 1.20, 0.70, 1.20, 0.10, 0.45, 0.00, 0.05, 0.25, 0.15).withPricePressureSensitivity(0.65));
    }

    @Override
    public int orderCount(ProfileSignalContext context) {
        return Math.clamp(standardOrderCount(context, false) + 1, 1, 8);
    }

    @Override
    public ProfileDecision decide(ProfileSignalContext context) {
        double concentration = context.symbolConcentrationRatio();
        if (concentration >= 0.25 && context.hasHolding()) {
            return signalDecision(SELL, ProfileDecisionReason.PORTFOLIO_TARGET, 1, concentration);
        }
        if (context.portfolio().positionCount() < 3 || concentration < 0.15) {
            return signalDecision(BUY, ProfileDecisionReason.PORTFOLIO_TARGET, orderCount(context), 1.0 - concentration);
        }
        return ProfileDecision.hold(ProfileDecisionReason.PORTFOLIO_TARGET, concentration);
    }

}
