package stock.batch.service.automarket.profile;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.automarket.support.AutoMarketDeterministicRandom;

public class AverageDownBuyerBehavior extends AbstractAutoProfileBehavior {

    private static final int MAX_AVERAGE_DOWN_ROUNDS = 3;
    private static final double MAX_PROJECTED_STOCK_ALLOCATION = 0.25;

    public AverageDownBuyerBehavior() {
        super(AutoParticipantProfileType.AVERAGE_DOWN_BUYER, new ProfilePolicy(0.20, 0.00, 0.55, 0.80, 0.05, 0.00, 0.00, 0.05, 1.05, 0.90, 1.80, 0.08, 1.20, 0.00, 0.95, 0.75, 0.35).withPricePressureSensitivity(0.85));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.isLosing() && context.isFirstOrder()) {
            return BUY;
        }
        return super.chooseSide(context);
    }

    @Override
    public ProfileDecision decide(ProfileSignalContext context) {
        if (!context.hasHolding()) {
            return super.decide(context);
        }
        double lossSignal = weightedSignal(
                context,
                -context.unrealizedReturn(),
                ProfilePolicy::dipBuyWeight
        );
        double averageDownThreshold = AutoMarketDeterministicRandom.stableRange(
                context.strategy(), "V2:AVERAGE_DOWN:RETURN", 0.045, 0.06
        );
        if (lossSignal < averageDownThreshold) {
            return ProfileDecision.hold(ProfileDecisionReason.INSUFFICIENT_SIGNAL, Math.abs(context.unrealizedReturn()));
        }
        BehavioralMemory memory = context.behavioralMemory();
        if (memory.averageDownRounds() >= MAX_AVERAGE_DOWN_ROUNDS
                || context.projectedStockAllocationRatio() >= MAX_PROJECTED_STOCK_ALLOCATION) {
            return ProfileDecision.hold(ProfileDecisionReason.AVERAGE_DOWN_LIMIT, 1.0);
        }
        if (context.businessDate() != null
                && memory.lastAverageDownBusinessDate() != null
                && !memory.lastAverageDownBusinessDate().isBefore(context.businessDate())) {
            return ProfileDecision.hold(ProfileDecisionReason.AVERAGE_DOWN_COOLDOWN, 1.0);
        }
        if (lossSignal >= averageDownThreshold) {
            return signalDecision(
                    BUY,
                    ProfileDecisionReason.AVERAGE_DOWN,
                    1,
                    Math.min(1.0, lossSignal)
            );
        }
        return ProfileDecision.hold(ProfileDecisionReason.INSUFFICIENT_SIGNAL, Math.abs(context.unrealizedReturn()));
    }

}
