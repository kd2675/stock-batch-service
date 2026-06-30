package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class MomentumFollowerBehavior extends AbstractAutoProfileBehavior {

    public MomentumFollowerBehavior() {
        super(AutoParticipantProfileType.MOMENTUM_FOLLOWER, new ProfilePolicy(0.25, 0.85, 0.00, 0.20, 0.35, 0.00, 0.15, 0.25, 1.00, 1.10, 1.00, 0.12, 1.00, 0.05, 0.00, 0.10, 0.05, BigDecimal.ZERO, 30));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (!context.hasHolding()) {
            return BUY;
        }
        if (context.momentumPressure() > 0.35 && context.canBuyOne() && context.orderIndex() == 0) {
            return BUY;
        }
        if (context.momentumPressure() < -0.35 && context.orderIndex() == 0) {
            return SELL;
        }
        return chooseByBuyBias(context);
    }
}
