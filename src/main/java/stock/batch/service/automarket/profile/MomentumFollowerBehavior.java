package stock.batch.service.automarket.profile;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class MomentumFollowerBehavior extends AbstractAutoProfileBehavior {

    public MomentumFollowerBehavior() {
        super(AutoParticipantProfileType.MOMENTUM_FOLLOWER, new ProfilePolicy(0.25, 0.85, 0.00, 0.20, 0.35, 0.00, 0.15, 0.25, 1.00, 1.10, 1.00, 0.12, 1.00, 0.05, 0.00, 0.10, 0.05).withPricePressureSensitivity(1.20));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.momentumPressure() > 0.35 && context.isFirstOrder()) {
            return BUY;
        }
        if (context.momentumPressure() < -0.35 && context.isFirstOrder()) {
            return SELL;
        }
        return chooseByBuyBias(context);
    }

    @Override
    public ProfileDecision decide(ProfileSignalContext context) {
        if (!context.marketSignals().hasTrailingReturn(1)) {
            return ProfileDecision.hold(ProfileDecisionReason.HISTORICAL_SIGNAL_UNAVAILABLE, 0.0);
        }
        double intraday = weightedSignal(
                context,
                context.marketSignals().momentum1Hour(),
                ProfilePolicy::momentumWeight
        );
        double daily = weightedSignal(
                context,
                context.marketSignals().return1Day(),
                ProfilePolicy::momentumWeight
        );
        boolean upward = intraday >= 0.25 && daily >= 0.01;
        boolean downward = intraday <= -0.25 && daily <= -0.01;
        if (!upward && !downward) {
            return ProfileDecision.hold(
                    ProfileDecisionReason.INSUFFICIENT_SIGNAL,
                    Math.max(Math.abs(intraday), Math.abs(daily))
            );
        }
        return signalDecision(
                upward ? BUY : SELL,
                ProfileDecisionReason.MULTI_DAY_SIGNAL,
                standardOrderCount(context, false),
                Math.max(Math.abs(intraday), Math.abs(daily))
        );
    }
}
