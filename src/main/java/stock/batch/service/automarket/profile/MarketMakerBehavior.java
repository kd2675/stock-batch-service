package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class MarketMakerBehavior extends AbstractAutoProfileBehavior {

    private static final double LOWER_STOCK_ALLOCATION = 0.40;
    private static final double UPPER_STOCK_ALLOCATION = 0.60;

    public MarketMakerBehavior() {
        super(AutoParticipantProfileType.MARKET_MAKER, new ProfilePolicy(0.15, 0.05, 0.00, 0.10, 0.10, 0.95, 0.00, 0.45, 1.25, 0.65, 0.60, 0.08, 1.00, 0.00, 0.00, 0.00, 0.00, BigDecimal.ZERO, 30).withPricePressureSensitivity(0.30));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        double stockAllocation = context.stockAllocationRatio();
        if (stockAllocation < LOWER_STOCK_ALLOCATION) {
            return BUY;
        }
        if (stockAllocation > UPPER_STOCK_ALLOCATION) {
            return SELL;
        }
        return context.orderIndex() % 2 == 0 ? BUY : SELL;
    }
}
