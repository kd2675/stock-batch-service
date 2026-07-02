package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class ValueAnchorBehavior extends AbstractAutoProfileBehavior {

    public ValueAnchorBehavior() {
        super(AutoParticipantProfileType.VALUE_ANCHOR, new ProfilePolicy(0.20, 0.00, 0.45, 0.55, 0.00, 0.10, 0.00, 0.15, 0.80, 0.75, 1.60, 0.08, 0.80, 0.00, 0.25, 0.50, 0.35, BigDecimal.ZERO, 30));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.momentumPressure() > 0.35 && context.hasHolding() && context.isFirstOrder()) {
            return SELL;
        }
        if (context.momentumPressure() < -0.35 && context.canBuyOne() && context.isFirstOrder()) {
            return BUY;
        }
        return super.chooseSide(context);
    }
}
