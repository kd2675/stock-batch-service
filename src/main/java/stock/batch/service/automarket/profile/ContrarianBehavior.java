package stock.batch.service.automarket.profile;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class ContrarianBehavior extends AbstractAutoProfileBehavior {

    public ContrarianBehavior() {
        super(AutoParticipantProfileType.CONTRARIAN, new ProfilePolicy(0.20, 0.00, 0.85, 0.25, 0.00, 0.10, 0.05, 0.35, 1.00, 0.90, 1.20, 0.12, 1.00, 0.00, 0.35, 0.20, 0.15).withPricePressureSensitivity(0.95));
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
        if (!context.marketSignals().hasTrailingReturn(5)) {
            return ProfileDecision.hold(ProfileDecisionReason.HISTORICAL_SIGNAL_UNAVAILABLE, 0.0);
        }
        double return3Day = weightedSignal(
                context,
                context.marketSignals().return3Day(),
                ProfilePolicy::contrarianWeight
        );
        double return5Day = weightedSignal(
                context,
                context.marketSignals().return5Day(),
                ProfilePolicy::contrarianWeight
        );
        if (return3Day <= -0.05 && return5Day <= -0.07) {
            return signalDecision(BUY, ProfileDecisionReason.MULTI_DAY_SIGNAL, standardOrderCount(context, false), Math.abs(return5Day));
        }
        if (return3Day >= 0.05 && return5Day >= 0.07 && context.hasHolding()) {
            return signalDecision(SELL, ProfileDecisionReason.MULTI_DAY_SIGNAL, standardOrderCount(context, false), return5Day);
        }
        return ProfileDecision.hold(ProfileDecisionReason.INSUFFICIENT_SIGNAL, Math.max(Math.abs(return3Day), Math.abs(return5Day)));
    }
}
