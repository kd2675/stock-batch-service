package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class SmallDiversifierBehavior extends AbstractAutoProfileBehavior {

    public SmallDiversifierBehavior() {
        super(AutoParticipantProfileType.SMALL_DIVERSIFIER, new ProfilePolicy(0.25, 0.20, 0.10, 0.30, 0.10, 0.00, 0.05, 0.25, 1.20, 0.70, 1.20, 0.10, 0.45, 0.00, 0.05, 0.25, 0.15, BigDecimal.ZERO, 30).withPricePressureSensitivity(0.65));
    }

    @Override
    public int orderCount(ProfileSignalContext context) {
        return Math.clamp(standardOrderCount(context, false) + 1, 1, 8);
    }

}
