package stock.batch.service.automarket.profile;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class DipBuyerBehavior extends AbstractAutoProfileBehavior {

    public DipBuyerBehavior() {
        super(AutoParticipantProfileType.DIP_BUYER, new ProfilePolicy(0.25, 0.00, 0.65, 0.35, 0.10, 0.00, 0.05, 0.20, 1.15, 1.05, 0.80, 0.15, 1.00, 0.00, 0.90, 0.25, 0.25).withPricePressureSensitivity(0.90));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.momentumPressure() < -0.35 && context.isFirstOrder()) {
            return BUY;
        }
        return super.chooseSide(context);
    }

    @Override
    public ProfileDecision decide(ProfileSignalContext context) {
        double hourMomentum = weightedSignal(
                context,
                context.marketSignals().momentum1Hour(),
                ProfilePolicy::dipBuyWeight
        );
        double shortMomentum = weightedSignal(
                context,
                context.marketSignals().momentum5Minute(),
                ProfilePolicy::dipBuyWeight
        );
        if (hourMomentum <= -0.30 && shortMomentum >= 0.10) {
            return signalDecision(
                    BUY,
                    ProfileDecisionReason.REVERSAL_CONFIRMED,
                    standardOrderCount(context, false),
                    Math.max(-hourMomentum, shortMomentum)
            );
        }
        return ProfileDecision.hold(
                ProfileDecisionReason.INSUFFICIENT_SIGNAL,
                Math.max(Math.abs(hourMomentum), Math.abs(shortMomentum))
        );
    }
}
