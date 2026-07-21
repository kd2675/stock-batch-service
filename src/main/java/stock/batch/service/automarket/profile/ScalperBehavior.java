package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class ScalperBehavior extends AbstractAutoProfileBehavior {

    public ScalperBehavior() {
        super(AutoParticipantProfileType.SCALPER, new ProfilePolicy(0.25, 0.55, 0.00, 0.10, 0.35, 0.00, 0.20, 0.85, 1.15, 1.15, 0.65, 0.22, 0.65, 0.10, 0.00, 0.00, 0.00, BigDecimal.ZERO, 30));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.unrealizedReturn() >= 0.08 && context.isFirstOrder()) {
            return SELL;
        }
        if (context.momentumPressure() > 0.45 && context.isFirstOrder()) {
            return BUY;
        }
        return super.chooseSide(context);
    }
}
