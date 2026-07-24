package stock.batch.service.automarket.profile;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.automarket.support.AutoMarketDeterministicRandom;

public class ScalperBehavior extends AbstractAutoProfileBehavior {

    public ScalperBehavior() {
        super(AutoParticipantProfileType.SCALPER, new ProfilePolicy(0.25, 0.55, 0.00, 0.10, 0.35, 0.00, 0.20, 0.85, 1.15, 1.15, 0.65, 0.22, 0.65, 0.10, 0.00, 0.00, 0.00).withPricePressureSensitivity(1.05));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.unrealizedReturn() >= 0.08 && context.isFirstOrder()) {
            return SELL;
        }
        if (context.momentumPressure() > 0.45 && context.isFirstOrder()) {
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
        long liquidationWindowSeconds = AutoMarketDeterministicRandom.stableLongRange(
                context.strategy(), "V2:SCALPER:CLOSE_WINDOW_SECONDS", 600L, 1_200L
        );
        double takeProfitReturn = AutoMarketDeterministicRandom.stableRange(
                context.strategy(), "V2:SCALPER:TAKE_PROFIT_RETURN", 0.004, 0.006
        );
        double stopLossReturn = -AutoMarketDeterministicRandom.stableRange(
                context.strategy(), "V2:SCALPER:STOP_LOSS_RETURN", 0.006, 0.009
        );
        long maximumHoldingSeconds = AutoMarketDeterministicRandom.stableLongRange(
                context.strategy(), "V2:SCALPER:MAXIMUM_HOLDING_SECONDS", 180L, 300L
        );
        if (context.hasHolding() && context.marketSignals().secondsToClose() <= liquidationWindowSeconds) {
            return signalDecision(
                    SELL,
                    ProfileDecisionReason.SESSION_CLOSE,
                    liquidationOrderCount(context),
                    1.0
            );
        }
        if (context.hasHolding() && context.behavioralMemory().holdingTradingDays() >= 1) {
            return signalDecision(
                    SELL,
                    ProfileDecisionReason.HOLDING_PERIOD,
                    liquidationOrderCount(context),
                    1.0
            );
        }
        if (context.hasHolding()
                && context.behavioralMemory().intradayPositionAgeAvailable()
                && context.behavioralMemory().intradayPositionAgeSeconds() >= maximumHoldingSeconds) {
            return signalDecision(
                    SELL,
                    ProfileDecisionReason.HOLDING_PERIOD,
                    liquidationOrderCount(context),
                    1.0
            );
        }
        if (context.hasHolding() && context.unrealizedReturn() >= takeProfitReturn) {
            return signalDecision(SELL, ProfileDecisionReason.SIGNAL, 1, context.unrealizedReturn());
        }
        if (context.hasHolding() && context.unrealizedReturn() <= stopLossReturn) {
            return signalDecision(SELL, ProfileDecisionReason.EXIT_THRESHOLD, 1, -context.unrealizedReturn());
        }
        if (shortMomentum >= 0.20) {
            return signalDecision(BUY, ProfileDecisionReason.SIGNAL, standardOrderCount(context, false), shortMomentum);
        }
        if (shortMomentum <= -0.20 && context.hasHolding()) {
            return signalDecision(SELL, ProfileDecisionReason.SIGNAL, standardOrderCount(context, false), shortMomentum);
        }
        return ProfileDecision.hold(ProfileDecisionReason.INSUFFICIENT_SIGNAL, Math.abs(shortMomentum));
    }
}
