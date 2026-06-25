package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class HerdFollowerBehavior extends AbstractAutoProfileBehavior {

    public HerdFollowerBehavior() {
        super(AutoParticipantProfileType.HERD_FOLLOWER, new ProfilePolicy(0.25, 0.25, 0.00, 0.15, 0.90, 0.00, 0.15, 0.20, 1.25, 1.20, 0.80, 0.15, 1.00, 0.15, 0.00, 0.05, 0.00, BigDecimal.ZERO, 30));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.herdPressure() > 0.50 && context.canBuyOne()) {
            return BUY;
        }
        if (context.herdPressure() < -0.50 && context.hasHolding()) {
            return SELL;
        }
        return chooseByBuyBias(context);
    }
}
