package stock.batch.service.automarket.profile;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class FomoBuyerBehavior extends AbstractAutoProfileBehavior {

    public FomoBuyerBehavior() {
        super(AutoParticipantProfileType.FOMO_BUYER, new ProfilePolicy(0.35, 0.85, 0.00, 0.05, 0.65, 0.00, 0.35, 0.25, 1.00, 1.10, 0.90, 0.18, 1.00, 0.05, 0.00, 0.05, 0.00).withPricePressureSensitivity(1.30));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if ((context.momentumPressure() > 0.50 || context.herdPressure() > 0.50) && context.isFirstOrder()) {
            return BUY;
        }
        return super.chooseSide(context);
    }

    @Override
    public ProfileDecision decide(ProfileSignalContext context) {
        double shortMomentum = weightedSignal(
                context,
                context.marketSignals().momentum5Minute(),
                ProfilePolicy::momentumWeight
        );
        double momentum = weightedSignal(
                context,
                context.marketSignals().momentum1Hour(),
                ProfilePolicy::momentumWeight
        );
        double crowd = weightedSignal(
                context,
                context.marketSignals().depthImbalance(),
                ProfilePolicy::herdingWeight
        );
        boolean activityConfirmed = context.marketSignals().recentActivityAvailable()
                && context.marketSignals().recentVolumeRatio() >= 1.10
                && context.marketSignals().recentParticipantCount5Minute() >= 3;
        if (shortMomentum >= 0.25 && momentum >= 0.30 && crowd >= 0.10 && activityConfirmed) {
            return signalDecision(BUY, ProfileDecisionReason.SIGNAL, standardOrderCount(context, false), Math.max(shortMomentum, momentum));
        }
        return ProfileDecision.hold(ProfileDecisionReason.INSUFFICIENT_SIGNAL, Math.max(Math.abs(shortMomentum), Math.abs(momentum)));
    }
}
