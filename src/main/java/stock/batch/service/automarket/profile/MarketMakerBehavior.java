package stock.batch.service.automarket.profile;

import java.math.BigDecimal;
import java.math.RoundingMode;

import stock.batch.service.batch.automarket.model.AutoParticipantBehaviorModelVersion;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

public class MarketMakerBehavior extends AbstractAutoProfileBehavior {

    private static final double LEGACY_LOWER_STOCK_ALLOCATION = 0.40;
    private static final double LEGACY_UPPER_STOCK_ALLOCATION = 0.60;

    public MarketMakerBehavior() {
        super(AutoParticipantProfileType.MARKET_MAKER, new ProfilePolicy(0.15, 0.05, 0.00, 0.10, 0.10, 0.95, 0.00, 0.45, 1.25, 0.65, 0.60, 0.08, 1.00, 0.00, 0.00, 0.00, 0.00).withPricePressureSensitivity(0.30));
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        if (context.strategy() == null
                || context.strategy().behaviorModelVersion() == AutoParticipantBehaviorModelVersion.V1) {
            double stockAllocation = legacyStockAllocationRatio(context);
            if (stockAllocation < LEGACY_LOWER_STOCK_ALLOCATION) {
                return BUY;
            }
            if (stockAllocation > LEGACY_UPPER_STOCK_ALLOCATION) {
                return SELL;
            }
            return context.orderIndex() % 2 == 0 ? BUY : SELL;
        }
        if (!usesMarketMakingPolicy(context)) {
            return super.chooseSide(context);
        }
        double stockAllocation = context.stockAllocationRatio();
        if (stockAllocation < context.marketMakerLowerAllocationRatio()) {
            return BUY;
        }
        if (stockAllocation > context.marketMakerUpperAllocationRatio()) {
            return SELL;
        }
        return context.orderIndex() % 2 == 0 ? BUY : SELL;
    }

    @Override
    public ProfileDecision decide(ProfileSignalContext context) {
        if (!usesMarketMakingPolicy(context)) {
            return super.decide(context);
        }
        int desiredOrderCount = standardOrderCount(context, false);
        double stockAllocation = context.stockAllocationRatio();
        double lowerAllocation = context.marketMakerLowerAllocationRatio();
        double upperAllocation = context.marketMakerUpperAllocationRatio();
        if (stockAllocation < lowerAllocation) {
            double deficitStrength = Math.clamp(
                    (lowerAllocation - stockAllocation) / Math.max(lowerAllocation, 0.0001),
                    0.0,
                    1.0
            );
            return new ProfileDecision(
                    ProfileDecisionAction.BUY,
                    ProfileDecisionReason.INVENTORY_BELOW_TARGET,
                    desiredOrderCount,
                    deficitStrength
            );
        }
        if (stockAllocation > upperAllocation) {
            double excessStrength = Math.clamp(
                    (stockAllocation - upperAllocation) / Math.max(1.0 - upperAllocation, 0.0001),
                    0.0,
                    1.0
            );
            return new ProfileDecision(
                    ProfileDecisionAction.SELL,
                    ProfileDecisionReason.INVENTORY_ABOVE_TARGET,
                    desiredOrderCount,
                    excessStrength
            );
        }
        int pairedOrderCount = Math.max(2, desiredOrderCount + desiredOrderCount % 2);
        double normalizedDeviation = context.marketMakerNormalizedInventoryDeviation();
        return new ProfileDecision(
                ProfileDecisionAction.BUY,
                ProfileDecisionReason.INVENTORY_BALANCED,
                pairedOrderCount,
                Math.abs(normalizedDeviation)
        );
    }

    private boolean usesMarketMakingPolicy(ProfileSignalContext context) {
        ProfileExecutionPolicy executionPolicy = context.policy().executionPolicy();
        return executionPolicy.inventoryMode() == ProfileInventoryMode.TARGET_ALLOCATION
                && executionPolicy.pricingMode() == ProfilePricingMode.MARKET_MAKING;
    }

    private double legacyStockAllocationRatio(ProfileSignalContext context) {
        if (context.availableQuantity() <= 0
                || context.config() == null
                || context.config().currentPrice() == null) {
            return 0.0;
        }
        BigDecimal stockValue = context.config().currentPrice()
                .max(BigDecimal.ZERO)
                .multiply(BigDecimal.valueOf(context.availableQuantity()));
        BigDecimal availableCash = context.cashBalance() == null
                ? BigDecimal.ZERO
                : context.cashBalance().max(BigDecimal.ZERO);
        BigDecimal total = stockValue.add(availableCash);
        if (total.signum() <= 0) {
            return 0.0;
        }
        return Math.clamp(stockValue.divide(total, 8, RoundingMode.HALF_UP).doubleValue(), 0.0, 1.0);
    }
}
