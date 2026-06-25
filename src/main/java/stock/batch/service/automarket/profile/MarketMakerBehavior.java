package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class MarketMakerBehavior extends AbstractAutoProfileBehavior {

    public MarketMakerBehavior() {
        super(AutoParticipantProfileType.MARKET_MAKER, new ProfilePolicy(0.15, 0.05, 0.00, 0.10, 0.10, 0.95, 0.00, 0.45, 1.25, 0.65, 0.60, 0.08, 1.00, 0.00, 0.00, 0.00, 0.00, BigDecimal.ZERO, 30));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (!context.canBuyOne() && !context.hasHolding()) {
            return null;
        }
        if (!context.canBuyOne()) {
            return SELL;
        }
        if (!context.hasHolding()) {
            return BUY;
        }
        return context.orderIndex() % 2 == 0 ? BUY : SELL;
    }
}
