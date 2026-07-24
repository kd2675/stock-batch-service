package stock.batch.service.automarket.profile;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.automarket.support.AutoMarketDeterministicRandom;

public class ProfitLockerBehavior extends AbstractAutoProfileBehavior {

    public ProfitLockerBehavior() {
        super(AutoParticipantProfileType.PROFIT_LOCKER, new ProfilePolicy(0.20, 0.35, 0.00, 0.10, 0.15, 0.00, 0.05, 1.00, 1.35, 1.25, 0.55, 0.20, 0.85, 0.05, 0.00, 0.00, 0.95).withPricePressureSensitivity(1.00));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.unrealizedReturn() >= 0.05) {
            return SELL;
        }
        return super.chooseSide(context);
    }

    @Override
    public ProfileDecision decide(ProfileSignalContext context) {
        if (!context.hasHolding()) {
            return super.decide(context);
        }
        double profitSignal = weightedSignal(
                context,
                context.unrealizedReturn(),
                ProfilePolicy::profitTakingWeight
        );
        double takeProfitReturn = AutoMarketDeterministicRandom.stableRange(
                context.strategy(), "V2:PROFIT_LOCKER:RETURN", 0.04, 0.06
        );
        if (profitSignal < takeProfitReturn) {
            return ProfileDecision.hold(
                    ProfileDecisionReason.INSUFFICIENT_SIGNAL,
                    Math.max(0.0, profitSignal)
            );
        }
        return new ProfileDecision(
                ProfileDecisionAction.SELL,
                ProfileDecisionReason.EXIT_THRESHOLD,
                1,
                Math.min(1.0, profitSignal)
        );
    }
}
