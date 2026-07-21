package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class LimitDownTrappedBehavior extends AbstractAutoProfileBehavior {

    public LimitDownTrappedBehavior() {
        super(AutoParticipantProfileType.LIMIT_DOWN_TRAPPED, new ProfilePolicy(0.20, 0.00, 0.20, 1.00, 0.05, 0.00, 0.00, 0.00, 0.55, 0.55, 2.50, 0.08, 0.50, 0.00, 0.25, 1.00, 1.00, BigDecimal.ZERO, 30));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.atLowerPriceLimit() || context.isDeepLoss()) {
            return BUY;
        }
        return super.chooseSide(context);
    }
}
