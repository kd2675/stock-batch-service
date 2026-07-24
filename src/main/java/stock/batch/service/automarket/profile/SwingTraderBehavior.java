package stock.batch.service.automarket.profile;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.automarket.support.AutoMarketDeterministicRandom;

public class SwingTraderBehavior extends AbstractAutoProfileBehavior {

    public SwingTraderBehavior() {
        super(AutoParticipantProfileType.SWING_TRADER, new ProfilePolicy(0.30, 0.45, 0.25, 0.25, 0.15, 0.00, 0.15, 0.45, 0.95, 1.05, 1.10, 0.12, 1.05, 0.05, 0.20, 0.20, 0.15).withPricePressureSensitivity(1.00));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.unrealizedReturn() >= 0.15 && context.isFirstOrder()) {
            return SELL;
        }
        if (context.momentumPressure() < -0.45 && context.isFirstOrder()) {
            return BUY;
        }
        return super.chooseSide(context);
    }

    @Override
    public ProfileDecision decide(ProfileSignalContext context) {
        double return3Day = weightedSignal(
                context,
                context.marketSignals().return3Day(),
                ProfilePolicy::momentumWeight
        );
        double return5Day = weightedSignal(
                context,
                context.marketSignals().return5Day(),
                ProfilePolicy::momentumWeight
        );
        double return10Day = weightedSignal(
                context,
                context.marketSignals().return10Day(),
                ProfilePolicy::momentumWeight
        );
        int holdingDays = context.behavioralMemory().holdingTradingDays();
        int minimumHoldingDays = Math.toIntExact(AutoMarketDeterministicRandom.stableLongRange(
                context.strategy(), "V2:SWING:MINIMUM_HOLDING_DAYS", 2L, 3L
        ));
        int maximumHoldingDays = Math.toIntExact(AutoMarketDeterministicRandom.stableLongRange(
                context.strategy(), "V2:SWING:MAXIMUM_HOLDING_DAYS", 8L, 10L
        ));
        if (context.hasHolding() && context.unrealizedReturn() <= -0.15) {
            return signalDecision(SELL, ProfileDecisionReason.EXIT_THRESHOLD, 1, -context.unrealizedReturn());
        }
        if (context.hasHolding() && holdingDays < minimumHoldingDays) {
            return ProfileDecision.hold(
                    ProfileDecisionReason.HOLDING_PERIOD,
                    Math.max(0.0, 1.0 - (double) holdingDays / minimumHoldingDays)
            );
        }
        if (!context.marketSignals().hasTrailingReturn(10)) {
            return ProfileDecision.hold(ProfileDecisionReason.HISTORICAL_SIGNAL_UNAVAILABLE, 0.0);
        }
        if (context.hasHolding() && (context.unrealizedReturn() >= 0.15
                || return3Day <= -0.10
                || return5Day <= -0.08 && return10Day <= 0.0
                || holdingDays >= maximumHoldingDays && return3Day <= 0.0)) {
            return signalDecision(
                    SELL,
                    ProfileDecisionReason.MULTI_DAY_SIGNAL,
                    standardOrderCount(context, false),
                    Math.max(Math.max(Math.abs(return3Day), Math.abs(return5Day)), context.unrealizedReturn())
            );
        }
        boolean confirmedUptrend = return3Day >= 0.025 && return5Day >= 0.04 && return10Day >= 0.03;
        boolean pullbackInUptrend = return3Day <= -0.02 && return5Day >= 0.02 && return10Day >= 0.03;
        if ((!context.hasHolding() || holdingDays < maximumHoldingDays) && (confirmedUptrend || pullbackInUptrend)) {
            return signalDecision(
                    BUY,
                    ProfileDecisionReason.MULTI_DAY_SIGNAL,
                    standardOrderCount(context, false),
                    Math.max(Math.max(Math.abs(return3Day), Math.abs(return5Day)), Math.abs(return10Day))
            );
        }
        return ProfileDecision.hold(
                ProfileDecisionReason.INSUFFICIENT_SIGNAL,
                Math.max(Math.max(Math.abs(return3Day), Math.abs(return5Day)), Math.abs(return10Day))
        );
    }
}
