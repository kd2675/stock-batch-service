package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class DividendReinvestorBehavior extends AbstractAutoProfileBehavior {

    public DividendReinvestorBehavior() {
        super(AutoParticipantProfileType.DIVIDEND_REINVESTOR, new ProfilePolicy(0.20, 0.08, 0.20, 0.70, 0.05, 0.00, 0.00, 0.08, 0.80, 0.75, 2.20, 0.05, 0.65, 0.00, 0.50, 0.90, 0.65, BigDecimal.ZERO, 30));
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
}
