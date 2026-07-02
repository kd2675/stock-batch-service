package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class PaydayAccumulatorBehavior extends AbstractAutoProfileBehavior {

    public PaydayAccumulatorBehavior() {
        super(AutoParticipantProfileType.PAYDAY_ACCUMULATOR, new ProfilePolicy(0.20, 0.10, 0.15, 0.65, 0.05, 0.00, 0.00, 0.05, 0.90, 0.80, 2.00, 0.06, 0.70, 0.00, 0.55, 0.90, 0.55, BigDecimal.ZERO, 30));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.isLosing()) {
            return context.canBuyOne() && context.isFirstOrder() ? BUY : null;
        }
        if (context.unrealizedReturn() >= 0.25 && context.hasHolding() && context.isAdditionalOrder()) {
            return SELL;
        }
        if (context.unrealizedReturn() >= 0.08 && context.hasHolding() && context.isThirdOrLaterOrder()) {
            return chooseByBuyBias(context);
        }
        if (context.canBuyOne() && context.isFirstOrder()) {
            return BUY;
        }
        return super.chooseSide(context);
    }
}
