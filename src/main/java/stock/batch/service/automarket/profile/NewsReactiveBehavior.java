package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class NewsReactiveBehavior extends AbstractAutoProfileBehavior {

    public NewsReactiveBehavior() {
        super(AutoParticipantProfileType.NEWS_REACTIVE, new ProfilePolicy(0.85, 0.15, 0.00, 0.25, 0.20, 0.00, 0.10, 0.20, 1.10, 1.15, 1.00, 0.10, 1.00, 0.00, 0.05, 0.15, 0.10, BigDecimal.ZERO, 30));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (!context.hasHolding()) {
            return BUY;
        }
        if (context.effectiveIntensity() >= 8 && context.canBuyOne() && context.orderIndex() == 0) {
            return BUY;
        }
        if (context.effectiveIntensity() <= 3 && context.orderIndex() == 0) {
            return SELL;
        }
        return chooseByBuyBias(context);
    }
}
