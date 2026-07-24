package stock.batch.service.automarket.profile;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class ObserverBehavior extends AbstractAutoProfileBehavior {

    public ObserverBehavior() {
        super(AutoParticipantProfileType.OBSERVER, new ProfilePolicy(0.15, 0.10, 0.00, 0.20, 0.00, 0.00, 0.00, 0.10, 0.30, 0.40, 2.20, 0.03, 0.40, 0.00, 0.00, 0.10, 0.00).withPricePressureSensitivity(0.30));
    }

    @Override
    public int orderCount(ProfileSignalContext context) {
        return Math.min(1, standardOrderCount(context, true));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (Math.abs(context.pricePressure()) < 0.80 && Math.abs(context.momentumPressure()) < 0.80) {
            return null;
        }
        return super.chooseSide(context);
    }

    @Override
    public ProfileDecision decide(ProfileSignalContext context) {
        double signalStrength = Math.max(Math.abs(context.pricePressure()), Math.abs(context.momentumPressure()));
        if (signalStrength < 0.80) {
            return ProfileDecision.hold(ProfileDecisionReason.INSUFFICIENT_SIGNAL, signalStrength);
        }
        String side = super.chooseSide(context);
        return new ProfileDecision(
                BUY.equals(side) ? ProfileDecisionAction.BUY : ProfileDecisionAction.SELL,
                ProfileDecisionReason.SIGNAL,
                Math.min(1, standardOrderCount(context, false)),
                signalStrength
        );
    }
}
