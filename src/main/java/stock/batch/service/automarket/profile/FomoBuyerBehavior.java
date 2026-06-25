package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class FomoBuyerBehavior extends AbstractAutoProfileBehavior {

    public FomoBuyerBehavior() {
        super(AutoParticipantProfileType.FOMO_BUYER, new ProfilePolicy(0.35, 0.95, 0.00, 0.05, 0.75, 0.00, 0.45, 0.30, 1.65, 1.55, 0.45, 0.28, 1.15, 0.05, 0.00, 0.05, 0.00, BigDecimal.ZERO, 30));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if ((context.momentumPressure() > 0.35 || context.herdPressure() > 0.35) && context.canBuyOne()) {
            return BUY;
        }
        return super.chooseSide(context);
    }
}
