package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class PanicSellerBehavior extends AbstractAutoProfileBehavior {

    public PanicSellerBehavior() {
        super(AutoParticipantProfileType.PANIC_SELLER, new ProfilePolicy(0.25, 0.25, 0.00, 0.20, 0.45, 0.00, 0.10, 0.70, 1.40, 1.40, 0.45, 0.25, 1.10, 0.90, 0.00, 0.00, 0.00, BigDecimal.ZERO, 30));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if ((context.momentumPressure() < -0.35 || context.herdPressure() < -0.35) && context.hasHolding()) {
            return SELL;
        }
        return super.chooseSide(context);
    }
}
