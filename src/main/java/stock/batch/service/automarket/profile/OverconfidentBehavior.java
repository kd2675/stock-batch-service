package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class OverconfidentBehavior extends AbstractAutoProfileBehavior {

    public OverconfidentBehavior() {
        super(AutoParticipantProfileType.OVERCONFIDENT, new ProfilePolicy(0.35, 0.45, 0.00, 0.20, 0.25, 0.00, 0.95, 0.10, 1.60, 1.35, 0.70, 0.20, 1.25, 0.05, 0.05, 0.10, 0.05, BigDecimal.ZERO, 30));
    }

    @Override
    public int orderCount(ProfileSignalContext context) {
        int base = standardOrderCount(context, false);
        if (context.isWinning()) {
            return clamp(base + 1, 1, 8);
        }
        return base;
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.isWinning() && context.canBuyOne()) {
            return BUY;
        }
        return super.chooseSide(context);
    }
}
