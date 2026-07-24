package stock.batch.service.automarket.profile;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class WhaleBehavior extends AbstractAutoProfileBehavior {

    public WhaleBehavior() {
        super(AutoParticipantProfileType.WHALE, new ProfilePolicy(0.30, 0.35, 0.00, 0.20, 0.25, 0.00, 0.20, 0.30, 1.20, 0.85, 1.20, 0.10, 1.80, 0.05, 0.00, 0.05, 0.00).withPricePressureSensitivity(0.85));
    }

}
