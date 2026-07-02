package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class DipBuyerBehavior extends AbstractAutoProfileBehavior {

    public DipBuyerBehavior() {
        super(AutoParticipantProfileType.DIP_BUYER, new ProfilePolicy(0.25, 0.00, 0.65, 0.35, 0.10, 0.00, 0.05, 0.20, 1.15, 1.05, 0.80, 0.15, 1.00, 0.00, 0.90, 0.25, 0.25, BigDecimal.ZERO, 30));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if ((context.momentumPressure() < -0.35 || context.isLosing()) && context.canBuyOne() && context.isFirstOrder()) {
            return BUY;
        }
        return super.chooseSide(context);
    }
}
