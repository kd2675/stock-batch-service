package stock.batch.service.automarket.profile;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.automarket.support.AutoMarketDeterministicRandom;

public class LongTermHolderBehavior extends AbstractAutoProfileBehavior {

    private static final int REBALANCE_INTERVAL_DAYS = 5;

    public LongTermHolderBehavior() {
        super(AutoParticipantProfileType.LONG_TERM_HOLDER, new ProfilePolicy(0.20, 0.05, 0.20, 0.85, 0.00, 0.00, 0.00, 0.05, 0.45, 0.50, 2.50, 0.05, 0.55, 0.00, 0.45, 0.95, 0.75).withPricePressureSensitivity(0.55));
    }

    @Override
    public int orderCount(ProfileSignalContext context) {
        return standardOrderCount(context, true);
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.isDeepLoss()) {
            return BUY;
        }
        if (context.unrealizedReturn() >= 0.35 && context.isAdditionalOrder()) {
            return SELL;
        }
        if (context.unrealizedReturn() >= 0.15) {
            return null;
        }
        return super.chooseSide(context);
    }

    @Override
    public ProfileDecision decide(ProfileSignalContext context) {
        double concentration = context.symbolConcentrationRatio();
        double longReturn = context.marketSignals().return20Day();
        int holdingDays = context.behavioralMemory().holdingTradingDays();
        int minimumHoldingDays = Math.toIntExact(AutoMarketDeterministicRandom.stableLongRange(
                context.strategy(), "V2:LONG_TERM:MINIMUM_HOLDING_DAYS", 15L, 25L
        ));
        boolean emergencyExit = concentration > 0.55 || context.unrealizedReturn() <= -0.35;
        if (context.hasHolding() && emergencyExit) {
            return signalDecision(SELL, ProfileDecisionReason.RISK_LIMIT, 1, Math.max(concentration, -context.unrealizedReturn()));
        }
        if (context.hasHolding() && holdingDays < minimumHoldingDays) {
            return ProfileDecision.hold(
                    ProfileDecisionReason.HOLDING_PERIOD,
                    1.0 - (double) holdingDays / minimumHoldingDays
            );
        }
        if (context.hasHolding() && (concentration > 0.45 || context.unrealizedReturn() >= 0.50)) {
            return signalDecision(SELL, ProfileDecisionReason.PORTFOLIO_TARGET, 1, Math.max(concentration - 0.45, context.unrealizedReturn()));
        }
        if (!context.marketSignals().hasTrailingReturn(20)) {
            return ProfileDecision.hold(ProfileDecisionReason.HISTORICAL_SIGNAL_UNAVAILABLE, 0.0);
        }
        if (!isRebalanceDay(context)) {
            return ProfileDecision.hold(ProfileDecisionReason.REBALANCE_WINDOW, 0.0);
        }
        if (concentration < 0.30 && longReturn > -0.15 && context.pricePressure() <= 0.25) {
            return signalDecision(BUY, ProfileDecisionReason.MULTI_DAY_SIGNAL, Math.max(1, standardOrderCount(context, true)), Math.max(Math.abs(longReturn), 0.25));
        }
        return ProfileDecision.hold(ProfileDecisionReason.PORTFOLIO_TARGET, Math.abs(concentration - 0.30));
    }

    private boolean isRebalanceDay(ProfileSignalContext context) {
        if (context.businessDate() == null || context.strategy() == null) {
            return true;
        }
        long stableOffset = Math.floorMod(context.strategy().behaviorSeed(), REBALANCE_INTERVAL_DAYS);
        return Math.floorMod(context.businessDate().toEpochDay() + stableOffset, REBALANCE_INTERVAL_DAYS) == 0;
    }
}
