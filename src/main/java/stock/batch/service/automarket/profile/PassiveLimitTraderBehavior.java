package stock.batch.service.automarket.profile;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

/**
 * Human-like participant that prefers resting limit orders. It has no quoting
 * obligation, paired-order behavior, inventory mandate, or automatic repricing.
 */
public class PassiveLimitTraderBehavior extends AbstractAutoProfileBehavior {

    public PassiveLimitTraderBehavior() {
        super(
                AutoParticipantProfileType.PASSIVE_LIMIT_TRADER,
                new ProfilePolicy(
                        0.15, 0.05, 0.10, 0.15, 0.05, 0.45, 0.05, 0.15,
                        0.70, 0.25, 1.40, 0.10, 0.65, 0.00, 0.10,
                        0.45, 0.20
                ).withPricePressureSensitivity(0.30)
        );
    }
}
