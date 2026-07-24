package stock.batch.service.automarket.profile;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class PanicSellerBehavior extends AbstractAutoProfileBehavior {

    public PanicSellerBehavior() {
        super(AutoParticipantProfileType.PANIC_SELLER, new ProfilePolicy(0.25, 0.22, 0.00, 0.22, 0.38, 0.00, 0.08, 0.62, 0.95, 1.05, 0.90, 0.16, 0.95, 0.82, 0.00, 0.00, 0.00).withPricePressureSensitivity(1.30));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if ((context.momentumPressure() < -0.50 || context.herdPressure() < -0.50) && context.isFirstOrder()) {
            return SELL;
        }
        return super.chooseSide(context);
    }

    @Override
    public ProfileDecision decide(ProfileSignalContext context) {
        double rawShortMomentum = context.marketSignals().momentum5Minute();
        double rawMomentum = context.marketSignals().momentum1Hour();
        double rawCrowd = context.marketSignals().depthImbalance();
        double shortMomentum = weightedSignal(
                context,
                rawShortMomentum,
                ProfilePolicy::panicSellWeight
        );
        double momentum = weightedSignal(
                context,
                rawMomentum,
                ProfilePolicy::panicSellWeight
        );
        double crowd = weightedSignal(
                context,
                rawCrowd,
                ProfilePolicy::herdingWeight
        );
        boolean activityConfirmed = context.marketSignals().recentActivityAvailable()
                && context.marketSignals().recentVolumeRatio() >= 1.10
                && context.marketSignals().recentParticipantCount5Minute() >= 3;
        boolean rawPanicSetup = context.hasHolding()
                && rawShortMomentum <= -0.25
                && rawMomentum <= -0.30
                && rawCrowd <= -0.10;
        if (rawPanicSetup) {
            if (activityConfirmed
                    && shortMomentum <= -0.25
                    && momentum <= -0.30
                    && crowd <= -0.10) {
                return signalDecision(
                        SELL,
                        ProfileDecisionReason.SIGNAL,
                        standardOrderCount(context, false),
                        Math.max(-shortMomentum, -momentum)
                );
            }
            return ProfileDecision.hold(
                    ProfileDecisionReason.INSUFFICIENT_SIGNAL,
                    Math.max(Math.abs(shortMomentum), Math.abs(momentum))
            );
        }
        return super.decide(context);
    }
}
