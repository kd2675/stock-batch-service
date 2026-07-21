package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class ProfitLockerBehavior extends AbstractAutoProfileBehavior {

    public ProfitLockerBehavior() {
        super(AutoParticipantProfileType.PROFIT_LOCKER, new ProfilePolicy(0.20, 0.35, 0.00, 0.10, 0.15, 0.00, 0.05, 1.00, 1.35, 1.25, 0.55, 0.20, 0.85, 0.05, 0.00, 0.00, 0.95, BigDecimal.ZERO, 30).withPricePressureSensitivity(1.00));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.unrealizedReturn() >= 0.05) {
            return SELL;
        }
        return super.chooseSide(context);
    }
}
