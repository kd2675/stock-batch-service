package stock.batch.service.automarket.profile;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class HerdFollowerBehavior extends AbstractAutoProfileBehavior {

    public HerdFollowerBehavior() {
        super(AutoParticipantProfileType.HERD_FOLLOWER, new ProfilePolicy(0.25, 0.25, 0.00, 0.15, 0.90, 0.00, 0.15, 0.20, 1.05, 1.05, 1.00, 0.12, 1.00, 0.15, 0.00, 0.05, 0.00).withPricePressureSensitivity(1.20));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.herdPressure() > 0.50 && context.isFirstOrder()) {
            return BUY;
        }
        if (context.herdPressure() < -0.50 && context.isFirstOrder()) {
            return SELL;
        }
        return chooseByBuyBias(context);
    }

    @Override
    public ProfileDecision decide(ProfileSignalContext context) {
        double crowd = weightedSignal(
                context,
                context.marketSignals().depthImbalance(),
                ProfilePolicy::herdingWeight
        );
        double momentum = weightedSignal(
                context,
                context.marketSignals().momentum5Minute(),
                ProfilePolicy::momentumWeight
        );
        boolean crowdConfirmed = context.marketSignals().recentActivityAvailable()
                && context.marketSignals().recentVolumeRatio() >= 0.75
                && context.marketSignals().recentParticipantCount5Minute() >= 4;
        if (crowd >= 0.45 && momentum >= 0.10 && crowdConfirmed) {
            return signalDecision(BUY, ProfileDecisionReason.SIGNAL, standardOrderCount(context, false), Math.max(crowd, momentum));
        }
        if (crowd <= -0.45 && momentum <= -0.10 && context.hasHolding() && crowdConfirmed) {
            return signalDecision(SELL, ProfileDecisionReason.SIGNAL, standardOrderCount(context, false), Math.max(-crowd, -momentum));
        }
        return ProfileDecision.hold(ProfileDecisionReason.INSUFFICIENT_SIGNAL, Math.max(Math.abs(crowd), Math.abs(momentum)));
    }
}
