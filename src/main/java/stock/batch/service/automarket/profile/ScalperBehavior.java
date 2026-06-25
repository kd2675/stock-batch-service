package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class ScalperBehavior extends AbstractAutoProfileBehavior {

    public ScalperBehavior() {
        super(AutoParticipantProfileType.SCALPER, new ProfilePolicy(0.25, 0.60, 0.00, 0.10, 0.40, 0.00, 0.25, 0.90, 2.00, 1.50, 0.25, 0.35, 0.70, 0.10, 0.00, 0.00, 0.00, BigDecimal.ZERO, 30));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.unrealizedReturn() >= 0.08 && context.hasHolding()) {
            return SELL;
        }
        if (context.momentumPressure() > 0.35 && context.canBuyOne()) {
            return BUY;
        }
        return super.chooseSide(context);
    }
}
