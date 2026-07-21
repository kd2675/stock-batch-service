package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class StopLossTraderBehavior extends AbstractAutoProfileBehavior {

    private static final double STOP_LOSS_RETURN = -0.05;
    private static final double STRONG_DOWNWARD_MOMENTUM = -0.65;

    public StopLossTraderBehavior() {
        super(AutoParticipantProfileType.STOP_LOSS_TRADER, new ProfilePolicy(0.25, 0.35, 0.00, 0.00, 0.20, 0.00, 0.05, 0.65, 1.20, 1.25, 0.55, 0.18, 0.95, 0.80, 0.00, 0.00, 0.10, BigDecimal.ZERO, 30).withPricePressureSensitivity(1.20));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.unrealizedReturn() <= STOP_LOSS_RETURN
                || context.momentumPressure() <= STRONG_DOWNWARD_MOMENTUM) {
            return SELL;
        }
        if (context.isLosing()) {
            return null;
        }
        return super.chooseSide(context);
    }
}
