package stock.batch.service.automarket.profile;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.automarket.support.AutoMarketDeterministicRandom;

public class DayTraderBehavior extends AbstractAutoProfileBehavior {

    public DayTraderBehavior() {
        super(AutoParticipantProfileType.DAY_TRADER, new ProfilePolicy(0.25, 0.62, 0.00, 0.08, 0.30, 0.00, 0.20, 0.80, 1.20, 1.15, 0.80, 0.18, 0.85, 0.12, 0.00, 0.00, 0.00).withPricePressureSensitivity(1.10));
    }

    @Override
    public int orderCount(ProfileSignalContext context) {
        int baseCount = standardOrderCount(context, false);
        if (Math.abs(context.momentumPressure()) >= 0.45 || Math.abs(context.unrealizedReturn()) >= 0.08) {
            return Math.clamp(baseCount + 1, 1, 8);
        }
        return baseCount;
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.unrealizedReturn() >= 0.08 && context.isFirstOrder()) {
            return SELL;
        }
        if (context.momentumPressure() > 0.45 && context.isFirstOrder()) {
            return BUY;
        }
        if (context.momentumPressure() < -0.45 && context.isFirstOrder()) {
            return SELL;
        }
        return chooseByBuyBias(context);
    }

    @Override
    public ProfileDecision decide(ProfileSignalContext context) {
        long secondsToClose = context.marketSignals().secondsToClose();
        long liquidationWindowSeconds = AutoMarketDeterministicRandom.stableLongRange(
                context.strategy(), "V2:DAY_TRADER:LIQUIDATION_WINDOW_SECONDS", 2_700L, 4_500L
        );
        long entryStopWindowSeconds = Math.max(
                liquidationWindowSeconds,
                AutoMarketDeterministicRandom.stableLongRange(
                        context.strategy(), "V2:DAY_TRADER:ENTRY_STOP_WINDOW_SECONDS", 5_400L, 9_000L
                )
        );
        if (secondsToClose <= liquidationWindowSeconds) {
            return context.hasHolding()
                    ? signalDecision(SELL, ProfileDecisionReason.SESSION_CLOSE, liquidationOrderCount(context), 1.0)
                    : ProfileDecision.hold(ProfileDecisionReason.SESSION_CLOSE, 1.0);
        }
        if (secondsToClose <= entryStopWindowSeconds) {
            return ProfileDecision.hold(ProfileDecisionReason.SESSION_CLOSE, 0.75);
        }
        String side = chooseSide(context);
        return signalDecision(
                side,
                ProfileDecisionReason.SIGNAL,
                orderCount(context),
                Math.max(Math.abs(context.momentumPressure()), Math.abs(context.unrealizedReturn()))
        );
    }
}
