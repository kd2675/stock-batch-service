package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class StopLossTraderBehavior extends AbstractAutoProfileBehavior {

    public StopLossTraderBehavior() {
        super(AutoParticipantProfileType.STOP_LOSS_TRADER, new ProfilePolicy(0.25, 0.35, 0.00, 0.00, 0.20, 0.00, 0.05, 0.65, 1.20, 1.25, 0.55, 0.18, 0.95, 0.80, 0.00, 0.00, 0.10, BigDecimal.ZERO, 30));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.isLosing() || context.momentumPressure() < -0.35) {
            return SELL;
        }
        return super.chooseSide(context);
    }
}
