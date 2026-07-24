package stock.batch.service.automarket.profile;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class OverconfidentBehavior extends AbstractAutoProfileBehavior {

    public OverconfidentBehavior() {
        super(AutoParticipantProfileType.OVERCONFIDENT, new ProfilePolicy(0.35, 0.45, 0.00, 0.20, 0.25, 0.00, 0.95, 0.10, 1.25, 1.15, 0.90, 0.15, 1.25, 0.05, 0.05, 0.10, 0.05).withPricePressureSensitivity(1.10));
    }

    @Override
    public int orderCount(ProfileSignalContext context) {
        int base = standardOrderCount(context, false);
        if (context.isWinning()) {
            return Math.clamp(base + 1, 1, 8);
        }
        return base;
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.isWinning() && context.isFirstOrder()) {
            return BUY;
        }
        return super.chooseSide(context);
    }

    @Override
    public ProfileDecision decide(ProfileSignalContext context) {
        double confidenceWeight = context.policy().overconfidenceWeight();
        boolean recentlySuccessful = context.behavioralMemory().hasRealizedPerformanceSample(5)
                && context.behavioralMemory().recentProfitableTradingDayRate() >= 0.65;
        double corroboratedMomentum = weightedSignal(
                context,
                Math.min(
                        context.marketSignals().momentum1Hour(),
                        context.marketSignals().return1Day() * 4.0
                ),
                ProfilePolicy::overconfidenceWeight
        );
        if (confidenceWeight > 0.0
                && (context.isWinning() || recentlySuccessful)
                && corroboratedMomentum >= 0.20) {
            return signalDecision(
                    BUY,
                    ProfileDecisionReason.SIGNAL,
                    orderCount(context),
                    Math.max(
                            Math.max(context.unrealizedReturn(), corroboratedMomentum),
                            context.behavioralMemory().recentProfitableTradingDayRate()
                    )
            );
        }
        if (context.isLosing()) {
            return ProfileDecision.hold(ProfileDecisionReason.LOSS_HOLD, Math.min(1.0, -context.unrealizedReturn()));
        }
        return super.decide(context);
    }
}
