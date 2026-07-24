package stock.batch.service.automarket.profile;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class CashDefensiveBehavior extends AbstractAutoProfileBehavior {

    public CashDefensiveBehavior() {
        super(AutoParticipantProfileType.CASH_DEFENSIVE, new ProfilePolicy(0.15, 0.05, 0.15, 0.50, 0.00, 0.00, 0.00, 0.20, 0.35, 0.45, 2.20, 0.04, 0.35, 0.10, 0.10, 0.70, 0.20).withPricePressureSensitivity(0.45));
    }

    @Override
    public int orderCount(ProfileSignalContext context) {
        return Math.min(1, standardOrderCount(context, true));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (Math.abs(context.pricePressure()) < 0.35 && Math.abs(context.unrealizedReturn()) < 0.05) {
            return null;
        }
        return super.chooseSide(context);
    }

    @Override
    public ProfileDecision decide(ProfileSignalContext context) {
        double cashRatio = context.cashAllocationRatio();
        if (cashRatio < 0.60 && context.hasHolding()) {
            return signalDecision(SELL, ProfileDecisionReason.PORTFOLIO_TARGET, 1, 0.60 - cashRatio);
        }
        if (cashRatio >= 0.70 && context.pricePressure() >= 0.65) {
            return signalDecision(BUY, ProfileDecisionReason.SIGNAL, 1, context.pricePressure());
        }
        return ProfileDecision.hold(ProfileDecisionReason.PORTFOLIO_TARGET, Math.abs(cashRatio - 0.65));
    }
}
