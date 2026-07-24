package stock.batch.service.automarket.profile;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class NewsReactiveBehavior extends AbstractAutoProfileBehavior {

    private static final long REPORT_HALF_LIFE_SECONDS = 6L * 60L * 60L;

    public NewsReactiveBehavior() {
        super(AutoParticipantProfileType.NEWS_REACTIVE, new ProfilePolicy(0.85, 0.15, 0.00, 0.25, 0.20, 0.00, 0.10, 0.20, 1.10, 1.15, 1.00, 0.10, 1.00, 0.00, 0.05, 0.15, 0.10).withPricePressureSensitivity(1.30));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.config() != null
                && context.config().reportPricePressure() >= 0.5
                && context.isFirstOrder()) {
            return BUY;
        }
        if (context.config() != null
                && context.config().reportPricePressure() <= -0.5
                && context.isFirstOrder()) {
            return SELL;
        }
        return chooseByBuyBias(context);
    }

    @Override
    public ProfileDecision decide(ProfileSignalContext context) {
        double reportSignal = weightedSignal(
                context,
                context.marketSignals().decayedReportPressure(REPORT_HALF_LIFE_SECONDS),
                ProfilePolicy::newsWeight
        );
        if (Math.abs(reportSignal) < 0.15) {
            return ProfileDecision.hold(ProfileDecisionReason.INSUFFICIENT_SIGNAL, Math.abs(reportSignal));
        }
        return signalDecision(
                reportSignal > 0 ? BUY : SELL,
                ProfileDecisionReason.FRESH_REPORT,
                standardOrderCount(context, false),
                Math.abs(reportSignal)
        );
    }
}
