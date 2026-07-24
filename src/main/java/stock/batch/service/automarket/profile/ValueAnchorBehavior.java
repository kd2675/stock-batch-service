package stock.batch.service.automarket.profile;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class ValueAnchorBehavior extends AbstractAutoProfileBehavior {

    public ValueAnchorBehavior() {
        super(AutoParticipantProfileType.VALUE_ANCHOR, new ProfilePolicy(0.20, 0.00, 0.45, 0.55, 0.00, 0.10, 0.00, 0.15, 0.80, 0.75, 1.60, 0.08, 0.80, 0.00, 0.25, 0.50, 0.35).withPricePressureSensitivity(0.70));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.momentumPressure() > 0.35 && context.isFirstOrder()) {
            return SELL;
        }
        if (context.momentumPressure() < -0.35 && context.isFirstOrder()) {
            return BUY;
        }
        return super.chooseSide(context);
    }

    @Override
    public ProfileDecision decide(ProfileSignalContext context) {
        if (!context.marketSignals().hasTrailingReturn(20)) {
            return ProfileDecision.hold(ProfileDecisionReason.HISTORICAL_SIGNAL_UNAVAILABLE, 0.0);
        }
        double return20Day = weightedSignal(
                context,
                context.marketSignals().return20Day(),
                ProfilePolicy::contrarianWeight
        );
        if (return20Day <= -0.12) {
            return signalDecision(BUY, ProfileDecisionReason.MULTI_DAY_SIGNAL, standardOrderCount(context, false), -return20Day);
        }
        if (return20Day >= 0.18 && context.hasHolding()) {
            return signalDecision(SELL, ProfileDecisionReason.MULTI_DAY_SIGNAL, standardOrderCount(context, false), return20Day);
        }
        return ProfileDecision.hold(ProfileDecisionReason.INSUFFICIENT_SIGNAL, Math.abs(return20Day));
    }
}
