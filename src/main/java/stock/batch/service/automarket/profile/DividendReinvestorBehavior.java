package stock.batch.service.automarket.profile;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class DividendReinvestorBehavior extends AbstractAutoProfileBehavior {

    public DividendReinvestorBehavior() {
        super(AutoParticipantProfileType.DIVIDEND_REINVESTOR, new ProfilePolicy(0.20, 0.08, 0.20, 0.70, 0.05, 0.00, 0.00, 0.08, 0.80, 0.75, 2.20, 0.05, 0.65, 0.00, 0.50, 0.90, 0.65).withPricePressureSensitivity(0.60));
    }

    @Override
    public int orderCount(ProfileSignalContext context) {
        int baseCount = standardOrderCount(context, false);
        if (context.hasRecentDividendPayment()) {
            return Math.clamp(baseCount + 1, 1, 8);
        }
        return baseCount;
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.hasRecentDividendPayment()) {
            return BUY;
        }
        if (context.isLosing() || context.momentumPressure() < -0.30) {
            return BUY;
        }
        if (shouldHoldInsteadOfSell(context)) {
            return null;
        }
        return chooseByBuyBias(context);
    }

    @Override
    public double buyBias(ProfileSignalContext context) {
        double bias = weightedBuyBias(context);
        if (context.hasRecentDividendPayment()) {
            bias += 0.18;
        }
        return Math.clamp(bias, 0.08, 0.95);
    }

    @Override
    public ProfileDecision decide(ProfileSignalContext context) {
        if (context.fundingBudgets().dividendAvailable().signum() <= 0) {
            return ProfileDecision.hold(ProfileDecisionReason.DIVIDEND_BUDGET_EMPTY, 0.0);
        }
        return signalDecision(BUY, ProfileDecisionReason.PORTFOLIO_TARGET, 1, 1.0);
    }
}
