package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class FomoBuyerBehavior extends AbstractAutoProfileBehavior {

    public FomoBuyerBehavior() {
        super(AutoParticipantProfileType.FOMO_BUYER, new ProfilePolicy(0.35, 0.85, 0.00, 0.05, 0.65, 0.00, 0.35, 0.25, 1.00, 1.10, 0.90, 0.18, 1.00, 0.05, 0.00, 0.05, 0.00, BigDecimal.ZERO, 30));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if ((context.momentumPressure() > 0.50 || context.herdPressure() > 0.50) && context.canBuyOne() && context.isFirstOrder()) {
            return BUY;
        }
        return super.chooseSide(context);
    }
}
