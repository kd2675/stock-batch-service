package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class ContrarianBehavior extends AbstractAutoProfileBehavior {

    public ContrarianBehavior() {
        super(AutoParticipantProfileType.CONTRARIAN, new ProfilePolicy(0.20, 0.00, 0.85, 0.25, 0.00, 0.10, 0.05, 0.35, 1.00, 0.90, 1.20, 0.12, 1.00, 0.00, 0.35, 0.20, 0.15, BigDecimal.ZERO, 30).withPricePressureSensitivity(0.95));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.momentumPressure() > 0.35 && context.isFirstOrder()) {
            return SELL;
        }
        if (context.momentumPressure() < -0.35 && context.isFirstOrder()) {
            return BUY;
        }
        return super.chooseSide(context);
    }
}
