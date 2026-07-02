package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class PanicSellerBehavior extends AbstractAutoProfileBehavior {

    public PanicSellerBehavior() {
        super(AutoParticipantProfileType.PANIC_SELLER, new ProfilePolicy(0.25, 0.22, 0.00, 0.22, 0.38, 0.00, 0.08, 0.62, 0.95, 1.05, 0.90, 0.16, 0.95, 0.82, 0.00, 0.00, 0.00, BigDecimal.ZERO, 30));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if ((context.momentumPressure() < -0.50 || context.herdPressure() < -0.50) && context.hasHolding() && context.isFirstOrder()) {
            return SELL;
        }
        return super.chooseSide(context);
    }
}
