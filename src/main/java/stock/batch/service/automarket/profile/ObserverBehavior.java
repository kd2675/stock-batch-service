package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class ObserverBehavior extends AbstractAutoProfileBehavior {

    public ObserverBehavior() {
        super(AutoParticipantProfileType.OBSERVER, new ProfilePolicy(0.15, 0.10, 0.00, 0.20, 0.00, 0.00, 0.00, 0.10, 0.30, 0.40, 2.20, 0.03, 0.40, 0.00, 0.00, 0.10, 0.00, BigDecimal.ZERO, 30).withPricePressureSensitivity(0.30));
    }

    @Override
    public int orderCount(ProfileSignalContext context) {
        return Math.min(1, standardOrderCount(context, true));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (Math.abs(context.pricePressure()) < 0.80 && Math.abs(context.momentumPressure()) < 0.80) {
            return null;
        }
        return super.chooseSide(context);
    }
}
