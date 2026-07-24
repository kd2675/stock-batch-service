package stock.batch.service.automarket.profile;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;

public interface AutoProfileBehavior {

    String BUY = "BUY";
    String SELL = "SELL";

    AutoParticipantProfileType type();

    ProfilePolicy defaultPolicy();

    default int activityLevel(AutoParticipantStrategy strategy) {
        return Math.clamp(strategy.intensity(), 1, 10);
    }

    int orderCount(ProfileSignalContext context);

    default ProfileDecision decide(ProfileSignalContext context) {
        int desiredOrderCount = orderCount(context);
        double signalStrength = Math.clamp(
                Math.max(Math.abs(context.pricePressure()), Math.abs(context.momentumPressure())),
                0.0,
                1.0
        );
        if (desiredOrderCount <= 0) {
            return ProfileDecision.hold(ProfileDecisionReason.INSUFFICIENT_SIGNAL, signalStrength);
        }
        String side = chooseSide(context);
        if (BUY.equals(side)) {
            return new ProfileDecision(ProfileDecisionAction.BUY, ProfileDecisionReason.SIGNAL, desiredOrderCount, signalStrength);
        }
        if (SELL.equals(side)) {
            return new ProfileDecision(ProfileDecisionAction.SELL, ProfileDecisionReason.SIGNAL, desiredOrderCount, signalStrength);
        }
        return ProfileDecision.hold(ProfileDecisionReason.INSUFFICIENT_SIGNAL, signalStrength);
    }

    String chooseSide(ProfileSignalContext context);

    double buyBias(ProfileSignalContext context);

    int orderTtlSeconds(int baseTtlSeconds, ProfilePolicy policy);
}
