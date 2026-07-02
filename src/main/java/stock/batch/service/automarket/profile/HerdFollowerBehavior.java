package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class HerdFollowerBehavior extends AbstractAutoProfileBehavior {

    public HerdFollowerBehavior() {
        super(AutoParticipantProfileType.HERD_FOLLOWER, new ProfilePolicy(0.25, 0.25, 0.00, 0.15, 0.90, 0.00, 0.15, 0.20, 1.05, 1.05, 1.00, 0.12, 1.00, 0.15, 0.00, 0.05, 0.00, BigDecimal.ZERO, 30));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.herdPressure() > 0.50 && context.canBuyOne() && context.isFirstOrder()) {
            return BUY;
        }
        if (context.herdPressure() < -0.50 && context.hasHolding() && context.isFirstOrder()) {
            return SELL;
        }
        return chooseByBuyBias(context);
    }
}
