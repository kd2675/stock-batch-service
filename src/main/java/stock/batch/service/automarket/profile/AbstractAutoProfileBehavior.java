package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;

import static stock.batch.service.automarket.support.AutoMarketRandomSupport.chance;

public abstract class AbstractAutoProfileBehavior implements AutoProfileBehavior {

    private final AutoParticipantProfileType type;
    private final ProfilePolicy defaultPolicy;

    protected AbstractAutoProfileBehavior(AutoParticipantProfileType type, ProfilePolicy defaultPolicy) {
        this.type = type;
        this.defaultPolicy = defaultPolicy;
    }

    @Override
    public AutoParticipantProfileType type() {
        return type;
    }

    @Override
    public ProfilePolicy defaultPolicy() {
        return defaultPolicy;
    }

    @Override
    public int orderCount(ProfileSignalContext context) {
        return standardOrderCount(context, false);
    }

    @Override
    public String chooseSide(ProfileSignalContext context) {
        String dominantConfiguredSide = chooseDominantConfiguredSide(context);
        if (dominantConfiguredSide != null) {
            return dominantConfiguredSide;
        }
        String intensitySide = chooseByIntensityPressure(context);
        if (intensitySide != null) {
            return intensitySide;
        }
        return chooseByBuyBias(context);
    }

    @Override
    public double buyBias(ProfileSignalContext context) {
        return weightedBuyBias(context);
    }

    @Override
    public int quantityUpperBound(int maxOrderQuantity, ProfilePolicy policy) {
        int baseQuantity = Math.max(1, maxOrderQuantity);
        double multiplier = Math.clamp(policy.quantityMultiplier(), 0.0, 1.0);
        return Math.max(1, (int) Math.floor(baseQuantity * multiplier));
    }

    @Override
    public int orderTtlSeconds(int baseTtlSeconds, ProfilePolicy policy) {
        return Math.max(1, (int) Math.round(Math.max(1, baseTtlSeconds) * policy.orderTtlMultiplier()));
    }

    protected int standardOrderCount(ProfileSignalContext context, boolean canStayIdle) {
        if (canStayIdle && Math.abs(context.pricePressure()) < 0.35 && Math.abs(context.unrealizedReturn()) < 0.05) {
            return 0;
        }
        int movementStrength = Math.clamp(context.effectiveIntensity(), 1, 10);
        int baseOrderCount = Math.max(1, (int) Math.ceil(movementStrength / 3.5));
        double profitBoost = 1.0;
        if (context.unrealizedReturn() > 0) {
            profitBoost += Math.min(0.75, context.unrealizedReturn() * 4.0 * context.policy().overconfidenceWeight());
        }
        return Math.clamp((int) Math.round(baseOrderCount * context.policy().orderMultiplier() * profitBoost), canStayIdle ? 0 : 1, 8);
    }

    protected double weightedBuyBias(ProfileSignalContext context) {
        ProfilePolicy policy = context.policy();
        double inventoryPenalty = Math.min(context.availableQuantity(), 20) * 0.01 * (1.0 - policy.holdingPatienceWeight() * 0.65);
        double buyBias = 0.5 + context.pricePressure() * 0.35 - inventoryPenalty;
        buyBias += context.assetPreferencePressure() * 0.25;
        buyBias += context.momentumPressure() * policy.momentumWeight() * 0.24;
        buyBias -= context.momentumPressure() * policy.contrarianWeight() * 0.24;
        buyBias += context.herdPressure() * policy.herdingWeight() * 0.22;
        buyBias -= context.herdPressure() * policy.marketMakingWeight() * 0.22;
        if (context.hasHolding()) {
            buyBias += policy.holdingPatienceWeight() * 0.08;
        }
        if (policy.recurringDepositAmount().compareTo(BigDecimal.ZERO) > 0) {
            buyBias += 0.12;
        }
        if (context.unrealizedReturn() < 0) {
            buyBias += policy.lossAversionWeight() * 0.18;
            buyBias += policy.dipBuyWeight() * Math.min(0.25, -context.unrealizedReturn() * 1.6);
            if (context.unrealizedReturn() <= -0.25) {
                buyBias += policy.deepLossHoldWeight() * 0.22;
            }
        } else if (context.unrealizedReturn() > 0) {
            buyBias -= policy.lossAversionWeight() * Math.min(0.12, context.unrealizedReturn() * 0.6);
            buyBias -= policy.profitTakingWeight() * Math.min(0.28, context.unrealizedReturn() * 1.8);
            buyBias += policy.overconfidenceWeight() * Math.min(0.20, context.unrealizedReturn() * 0.8);
        }
        if (context.momentumPressure() < -0.35) {
            buyBias -= policy.panicSellWeight() * 0.24;
            buyBias += policy.dipBuyWeight() * 0.24;
        }
        buyBias += context.noise();
        return Math.clamp(buyBias, 0.08, 0.92);
    }

    protected String chooseByBuyBias(ProfileSignalContext context) {
        return chance(buyBias(context)) ? BUY : SELL;
    }

    protected String chooseDominantConfiguredSide(ProfileSignalContext context) {
        if (context.isAdditionalOrder()) {
            return null;
        }
        ProfilePolicy policy = context.policy();
        if (context.isWinning() && policy.profitTakingWeight() >= 0.90) {
            return SELL;
        }
        if (context.momentumPressure() > 0.35 && policy.momentumWeight() >= 0.90) {
            return BUY;
        }
        if (context.momentumPressure() < -0.35 && policy.momentumWeight() >= 0.90) {
            return SELL;
        }
        if (context.momentumPressure() > 0.35 && policy.contrarianWeight() >= 0.90) {
            return SELL;
        }
        if (context.momentumPressure() < -0.35 && policy.contrarianWeight() >= 0.90) {
            return BUY;
        }
        return null;
    }

    protected String chooseByIntensityPressure(ProfileSignalContext context) {
        ProfilePolicy policy = context.policy();
        double pressure = context.pricePressure() + context.assetPreferencePressure() * 0.5;
        if (context.effectiveIntensity() >= 9
                && pressure > 0.35
                && policy.contrarianWeight() < 0.50) {
            return BUY;
        }
        if (context.effectiveIntensity() >= 9
                && pressure < -0.35
                && policy.contrarianWeight() < 0.35
                && policy.dipBuyWeight() < 0.50
                && policy.lossAversionWeight() < 0.70
                && !shouldHoldInsteadOfSell(context)) {
            return SELL;
        }
        return null;
    }

    protected boolean shouldHoldInsteadOfSell(ProfileSignalContext context) {
        ProfilePolicy policy = context.policy();
        return policy.holdingPatienceWeight() >= 0.85
                || (context.unrealizedReturn() <= -0.05 && policy.lossAversionWeight() >= 0.85)
                || (context.unrealizedReturn() <= -0.25 && policy.deepLossHoldWeight() >= 0.70);
    }

}
