package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class DayTraderBehavior extends AbstractAutoProfileBehavior {

    public DayTraderBehavior() {
        super(AutoParticipantProfileType.DAY_TRADER, new ProfilePolicy(0.25, 0.62, 0.00, 0.08, 0.30, 0.00, 0.20, 0.80, 1.20, 1.15, 0.80, 0.18, 0.85, 0.12, 0.00, 0.00, 0.00, BigDecimal.ZERO, 30));
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
        if (context.unrealizedReturn() >= 0.08 && context.hasHolding() && context.isFirstOrder()) {
            return SELL;
        }
        if (context.momentumPressure() > 0.45 && context.canBuyOne() && context.isFirstOrder()) {
            return BUY;
        }
        if (context.momentumPressure() < -0.45 && context.hasHolding() && context.isFirstOrder()) {
            return SELL;
        }
        return chooseByBuyBias(context);
    }
}
