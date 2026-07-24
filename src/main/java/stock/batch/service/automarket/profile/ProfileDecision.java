package stock.batch.service.automarket.profile;

public record ProfileDecision(
        ProfileDecisionAction action,
        ProfileDecisionReason reason,
        int desiredOrderCount,
        double signalStrength
) {
    public ProfileDecision {
        action = action == null ? ProfileDecisionAction.HOLD : action;
        reason = reason == null ? ProfileDecisionReason.INSUFFICIENT_SIGNAL : reason;
        desiredOrderCount = Math.max(0, desiredOrderCount);
        signalStrength = Math.clamp(signalStrength, 0.0, 1.0);
        if (action == ProfileDecisionAction.HOLD) {
            desiredOrderCount = 0;
        }
    }

    public static ProfileDecision hold(ProfileDecisionReason reason, double signalStrength) {
        return new ProfileDecision(ProfileDecisionAction.HOLD, reason, 0, signalStrength);
    }
}
