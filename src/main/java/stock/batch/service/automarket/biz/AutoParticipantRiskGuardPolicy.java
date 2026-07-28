package stock.batch.service.automarket.biz;

import stock.batch.service.automarket.v3.AutoParticipantDecisionUrgency;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

final class AutoParticipantRiskGuardPolicy {

    GuardDecision evaluate(AutoParticipantProfileType profileType, GuardSnapshot snapshot) {
        return switch (profileType) {
            case DAY_TRADER -> snapshot.secondsToClose() <= 4_500
                    ? GuardDecision.mandatory("SESSION_CLOSE")
                    : GuardDecision.none();
            case SCALPER -> snapshot.positionAgeSeconds() >= 300
                    || snapshot.secondsToClose() <= 1_200
                    ? GuardDecision.risk("SCALPER_MAX_HOLD")
                    : GuardDecision.none();
            case STOP_LOSS_TRADER -> snapshot.unrealizedReturn() <= -0.06
                    ? GuardDecision.risk("STOP_LOSS")
                    : GuardDecision.none();
            case NEWS_REACTIVE, MOMENTUM_FOLLOWER, CONTRARIAN, LOSS_AVERSE,
                    OVERCONFIDENT, HERD_FOLLOWER, PASSIVE_LIMIT_TRADER, NOISE_TRADER,
                    VALUE_ANCHOR, SWING_TRADER, LONG_TERM_HOLDER, PAYDAY_ACCUMULATOR,
                    DIVIDEND_REINVESTOR, LIMIT_DOWN_TRAPPED, AVERAGE_DOWN_BUYER,
                    FOMO_BUYER, PANIC_SELLER, DIP_BUYER, PROFIT_LOCKER,
                    LIQUIDITY_AVOIDANT, CASH_DEFENSIVE, WHALE, SMALL_DIVERSIFIER,
                    OBSERVER -> GuardDecision.none();
        };
    }

    record GuardSnapshot(
            long secondsToClose,
            long positionAgeSeconds,
            double unrealizedReturn
    ) {
    }

    record GuardDecision(
            boolean triggered,
            AutoParticipantDecisionUrgency urgency,
            String reason
    ) {
        static GuardDecision none() {
            return new GuardDecision(false, null, null);
        }

        static GuardDecision risk(String reason) {
            return new GuardDecision(true, AutoParticipantDecisionUrgency.RISK_REDUCTION, reason);
        }

        static GuardDecision mandatory(String reason) {
            return new GuardDecision(true, AutoParticipantDecisionUrgency.MANDATORY_CLOSE, reason);
        }
    }
}
