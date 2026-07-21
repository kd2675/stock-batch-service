package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class LiquidityAvoidantBehavior extends AbstractAutoProfileBehavior {

    public LiquidityAvoidantBehavior() {
        super(AutoParticipantProfileType.LIQUIDITY_AVOIDANT, new ProfilePolicy(0.20, 0.10, 0.00, 0.35, 0.00, 0.00, 0.00, 0.35, 0.55, 0.55, 1.80, 0.05, 0.60, 0.10, 0.00, 0.25, 0.10, BigDecimal.ZERO, 30).withPricePressureSensitivity(0.45));
    }

    @Override
    public int orderCount(ProfileSignalContext context) {
        return Math.min(2, standardOrderCount(context, true));
    }

}
