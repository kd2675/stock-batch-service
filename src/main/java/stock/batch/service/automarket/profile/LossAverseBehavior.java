package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class LossAverseBehavior extends AbstractAutoProfileBehavior {

    public LossAverseBehavior() {
        super(AutoParticipantProfileType.LOSS_AVERSE, new ProfilePolicy(0.25, 0.10, 0.00, 0.95, 0.10, 0.00, 0.05, 0.05, 0.85, 0.80, 1.80, 0.08, 0.80, 0.05, 0.00, 0.75, 0.60, BigDecimal.ZERO, 30));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.isLosing()) {
            return BUY;
        }
        return super.chooseSide(context);
    }
}
