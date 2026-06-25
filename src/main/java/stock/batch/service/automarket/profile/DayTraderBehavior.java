package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class DayTraderBehavior extends AbstractAutoProfileBehavior {

    public DayTraderBehavior() {
        super(AutoParticipantProfileType.DAY_TRADER, new ProfilePolicy(0.25, 0.70, 0.00, 0.08, 0.35, 0.00, 0.25, 0.85, 2.30, 1.60, 0.35, 0.30, 0.90, 0.15, 0.00, 0.00, 0.00, BigDecimal.ZERO, 30));
    }

    @Override
    public int orderCount(ProfileSignalContext context) {
        return clamp(standardOrderCount(context, false) + 1, 1, 8);
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.unrealizedReturn() >= 0.08 && context.hasHolding()) {
            return SELL;
        }
        if (context.momentumPressure() > 0.30 && context.canBuyOne()) {
            return BUY;
        }
        if (context.momentumPressure() < -0.30 && context.hasHolding()) {
            return SELL;
        }
        return chooseByBuyBias(context);
    }
}
