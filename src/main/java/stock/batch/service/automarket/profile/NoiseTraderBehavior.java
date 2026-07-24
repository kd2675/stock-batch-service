package stock.batch.service.automarket.profile;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class NoiseTraderBehavior extends AbstractAutoProfileBehavior {

    public NoiseTraderBehavior() {
        super(AutoParticipantProfileType.NOISE_TRADER, new ProfilePolicy(0.35, 0.20, 0.10, 0.20, 0.15, 0.00, 0.10, 0.20, 1.00, 1.00, 1.00, 0.45, 1.00, 0.05, 0.05, 0.10, 0.05).withPricePressureSensitivity(0.60));
    }

}
