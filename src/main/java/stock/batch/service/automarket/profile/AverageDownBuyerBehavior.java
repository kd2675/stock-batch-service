package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class AverageDownBuyerBehavior extends AbstractAutoProfileBehavior {

    public AverageDownBuyerBehavior() {
        super(AutoParticipantProfileType.AVERAGE_DOWN_BUYER, new ProfilePolicy(0.20, 0.00, 0.55, 0.80, 0.05, 0.00, 0.00, 0.05, 1.05, 0.90, 1.80, 0.08, 1.20, 0.00, 0.95, 0.75, 0.35, BigDecimal.ZERO, 30));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.isLosing() && context.canBuyOne()) {
            return BUY;
        }
        return super.chooseSide(context);
    }

    @Override
    public int quantityUpperBound(int maxOrderQuantity, ProfilePolicy policy) {
        return Math.max(1, Math.min(Math.max(1, maxOrderQuantity), (int) Math.ceil(Math.max(1, maxOrderQuantity) * 0.80)));
    }
}
