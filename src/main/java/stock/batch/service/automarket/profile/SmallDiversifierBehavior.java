package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class SmallDiversifierBehavior extends AbstractAutoProfileBehavior {

    public SmallDiversifierBehavior() {
        super(AutoParticipantProfileType.SMALL_DIVERSIFIER, new ProfilePolicy(0.25, 0.20, 0.10, 0.30, 0.10, 0.00, 0.05, 0.25, 1.45, 0.70, 1.00, 0.12, 0.45, 0.00, 0.05, 0.25, 0.15, BigDecimal.ZERO, 30));
    }

    @Override
    public int orderCount(ProfileSignalContext context) {
        return clamp(standardOrderCount(context, false) + 1, 1, 8);
    }

    @Override
    public int quantityUpperBound(int maxOrderQuantity, ProfilePolicy policy) {
        return Math.max(1, (int) Math.floor(Math.max(1, maxOrderQuantity) * 0.45));
    }
}
