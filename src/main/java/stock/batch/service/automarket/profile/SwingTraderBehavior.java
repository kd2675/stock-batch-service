package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class SwingTraderBehavior extends AbstractAutoProfileBehavior {

    public SwingTraderBehavior() {
        super(AutoParticipantProfileType.SWING_TRADER, new ProfilePolicy(0.30, 0.45, 0.25, 0.25, 0.15, 0.00, 0.15, 0.45, 0.95, 1.05, 1.10, 0.12, 1.05, 0.05, 0.20, 0.20, 0.15, BigDecimal.ZERO, 30));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.unrealizedReturn() >= 0.15 && context.hasHolding() && context.isFirstOrder()) {
            return SELL;
        }
        if (context.momentumPressure() < -0.45 && context.canBuyOne() && context.isFirstOrder()) {
            return BUY;
        }
        return super.chooseSide(context);
    }
}
