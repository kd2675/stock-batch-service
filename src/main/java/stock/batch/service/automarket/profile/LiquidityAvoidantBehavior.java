package stock.batch.service.automarket.profile;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class LiquidityAvoidantBehavior extends AbstractAutoProfileBehavior {

    public LiquidityAvoidantBehavior() {
        super(AutoParticipantProfileType.LIQUIDITY_AVOIDANT, new ProfilePolicy(0.20, 0.10, 0.00, 0.35, 0.00, 0.00, 0.00, 0.35, 0.55, 0.55, 1.80, 0.05, 0.60, 0.10, 0.00, 0.25, 0.10).withPricePressureSensitivity(0.45));
    }

    @Override
    public int orderCount(ProfileSignalContext context) {
        return Math.min(2, standardOrderCount(context, true));
    }

    @Override
    public ProfileDecision decide(ProfileSignalContext context) {
        long minimumDepth = context.config() == null ? 1L : Math.max(1L, context.config().maxOrderQuantity() * 2L);
        long visibleDepth = context.marketSignals().bidDepth() + context.marketSignals().askDepth();
        boolean recentActivityTooThin = context.marketSignals().recentActivityAvailable()
                && (context.marketSignals().recentVolumeRatio() < 0.25
                || context.marketSignals().recentParticipantCount5Minute() < 2);
        if (context.marketSignals().spreadTicks() > 4.0
                || visibleDepth < minimumDepth
                || recentActivityTooThin) {
            return ProfileDecision.hold(ProfileDecisionReason.LIQUIDITY_FILTER, 1.0);
        }
        String side = chooseSide(context);
        return signalDecision(
                side,
                ProfileDecisionReason.SIGNAL,
                orderCount(context),
                Math.max(Math.abs(context.pricePressure()), Math.abs(context.momentumPressure()))
        );
    }

}
