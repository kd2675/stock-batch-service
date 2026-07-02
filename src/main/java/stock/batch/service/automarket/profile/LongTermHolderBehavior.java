package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class LongTermHolderBehavior extends AbstractAutoProfileBehavior {

    public LongTermHolderBehavior() {
        super(AutoParticipantProfileType.LONG_TERM_HOLDER, new ProfilePolicy(0.20, 0.05, 0.20, 0.85, 0.00, 0.00, 0.00, 0.05, 0.45, 0.50, 2.50, 0.05, 0.55, 0.00, 0.45, 0.95, 0.75, BigDecimal.ZERO, 30));
    }

    @Override
    public int orderCount(ProfileSignalContext context) {
        return standardOrderCount(context, true);
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.isDeepLoss()) {
            return context.canBuyOne() ? BUY : null;
        }
        if (context.unrealizedReturn() >= 0.35 && context.hasHolding() && context.isAdditionalOrder()) {
            return SELL;
        }
        if (context.unrealizedReturn() >= 0.15 && context.hasHolding()) {
            return null;
        }
        return super.chooseSide(context);
    }
}
