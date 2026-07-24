package stock.batch.service.automarket.profile;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.automarket.support.AutoMarketDeterministicRandom;

public class StopLossTraderBehavior extends AbstractAutoProfileBehavior {

    private static final double LEGACY_STOP_LOSS_RETURN = -0.05;
    private static final double LEGACY_STRONG_DOWNWARD_MOMENTUM = -0.65;

    public StopLossTraderBehavior() {
        super(AutoParticipantProfileType.STOP_LOSS_TRADER, new ProfilePolicy(0.25, 0.35, 0.00, 0.00, 0.20, 0.00, 0.05, 0.65, 1.20, 1.25, 0.55, 0.18, 0.95, 0.80, 0.00, 0.00, 0.10).withPricePressureSensitivity(1.20));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.unrealizedReturn() <= LEGACY_STOP_LOSS_RETURN
                || context.momentumPressure() <= LEGACY_STRONG_DOWNWARD_MOMENTUM) {
            return SELL;
        }
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
        double lossSignal = weightedSignal(
                context,
                -context.unrealizedReturn(),
                ProfilePolicy::panicSellWeight
        );
        double downwardMomentumSignal = weightedSignal(
                context,
                -context.marketSignals().momentum5Minute(),
                ProfilePolicy::panicSellWeight
        );
        if (context.hasHolding() && (lossSignal >= -stopLossReturn(context)
                || downwardMomentumSignal >= -strongDownwardMomentum(context))) {
            return signalDecision(
                    SELL,
                    ProfileDecisionReason.EXIT_THRESHOLD,
                    1,
                    Math.max(lossSignal, downwardMomentumSignal)
            );
        }
        if (!context.isLosing()) {
            return super.decide(context);
        }
        return ProfileDecision.hold(ProfileDecisionReason.INSUFFICIENT_SIGNAL, Math.abs(context.unrealizedReturn()));
    }

    private double stopLossReturn(ProfileSignalContext context) {
        return -AutoMarketDeterministicRandom.stableRange(
                context.strategy(), "V2:STOP_LOSS:RETURN", 0.04, 0.06
        );
    }

    private double strongDownwardMomentum(ProfileSignalContext context) {
        return -AutoMarketDeterministicRandom.stableRange(
                context.strategy(), "V2:STOP_LOSS:MOMENTUM", 0.60, 0.70
        );
    }
}
