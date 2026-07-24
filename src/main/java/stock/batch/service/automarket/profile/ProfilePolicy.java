package stock.batch.service.automarket.profile;

import java.math.BigDecimal;

import stock.batch.service.batch.automarket.model.AutoParticipantProfileConfig;

public final class ProfilePolicy {

    private final double newsWeight;
    private final double momentumWeight;
    private final double contrarianWeight;
    private final double lossAversionWeight;
    private final double herdingWeight;
    private final double marketMakingWeight;
    private final double overconfidenceWeight;
    private final double profitTakingWeight;
    private final double orderMultiplier;
    private final double aggressionMultiplier;
    private final double pricePressureSensitivity;
    private final double orderTtlMultiplier;
    private final double noiseWeight;
    private final double quantityMultiplier;
    private final double panicSellWeight;
    private final double dipBuyWeight;
    private final double holdingPatienceWeight;
    private final double deepLossHoldWeight;
    private final ProfileExecutionPolicy executionPolicy;
    private final String behaviorSeedVersion;

    public ProfilePolicy(
            double newsWeight,
            double momentumWeight,
            double contrarianWeight,
            double lossAversionWeight,
            double herdingWeight,
            double marketMakingWeight,
            double overconfidenceWeight,
            double profitTakingWeight,
            double orderMultiplier,
            double aggressionMultiplier,
            double pricePressureSensitivity,
            double orderTtlMultiplier,
            double noiseWeight,
            double quantityMultiplier,
            double panicSellWeight,
            double dipBuyWeight,
            double holdingPatienceWeight,
            double deepLossHoldWeight
    ) {
        this(
                newsWeight,
                momentumWeight,
                contrarianWeight,
                lossAversionWeight,
                herdingWeight,
                marketMakingWeight,
                overconfidenceWeight,
                profitTakingWeight,
                orderMultiplier,
                aggressionMultiplier,
                pricePressureSensitivity,
                orderTtlMultiplier,
                noiseWeight,
                quantityMultiplier,
                panicSellWeight,
                dipBuyWeight,
                holdingPatienceWeight,
                deepLossHoldWeight,
                ProfileExecutionPolicy.legacy(
                        orderMultiplier,
                        orderTtlMultiplier,
                        marketMakingWeight,
                        profitTakingWeight,
                        holdingPatienceWeight
                )
        );
    }

    public ProfilePolicy(
            double newsWeight,
            double momentumWeight,
            double contrarianWeight,
            double lossAversionWeight,
            double herdingWeight,
            double marketMakingWeight,
            double overconfidenceWeight,
            double profitTakingWeight,
            double orderMultiplier,
            double aggressionMultiplier,
            double orderTtlMultiplier,
            double noiseWeight,
            double quantityMultiplier,
            double panicSellWeight,
            double dipBuyWeight,
            double holdingPatienceWeight,
            double deepLossHoldWeight
    ) {
        this(
                newsWeight,
                momentumWeight,
                contrarianWeight,
                lossAversionWeight,
                herdingWeight,
                marketMakingWeight,
                overconfidenceWeight,
                profitTakingWeight,
                orderMultiplier,
                aggressionMultiplier,
                1.0,
                orderTtlMultiplier,
                noiseWeight,
                quantityMultiplier,
                panicSellWeight,
                dipBuyWeight,
                holdingPatienceWeight,
                deepLossHoldWeight
        );
    }

    private ProfilePolicy(
            double newsWeight,
            double momentumWeight,
            double contrarianWeight,
            double lossAversionWeight,
            double herdingWeight,
            double marketMakingWeight,
            double overconfidenceWeight,
            double profitTakingWeight,
            double orderMultiplier,
            double aggressionMultiplier,
            double pricePressureSensitivity,
            double orderTtlMultiplier,
            double noiseWeight,
            double quantityMultiplier,
            double panicSellWeight,
            double dipBuyWeight,
            double holdingPatienceWeight,
            double deepLossHoldWeight,
            ProfileExecutionPolicy executionPolicy
    ) {
        this.newsWeight = newsWeight;
        this.momentumWeight = momentumWeight;
        this.contrarianWeight = contrarianWeight;
        this.lossAversionWeight = lossAversionWeight;
        this.herdingWeight = herdingWeight;
        this.marketMakingWeight = marketMakingWeight;
        this.overconfidenceWeight = overconfidenceWeight;
        this.profitTakingWeight = profitTakingWeight;
        this.orderMultiplier = orderMultiplier;
        this.aggressionMultiplier = aggressionMultiplier;
        this.pricePressureSensitivity = pricePressureSensitivity;
        this.orderTtlMultiplier = orderTtlMultiplier;
        this.noiseWeight = noiseWeight;
        this.quantityMultiplier = quantityMultiplier;
        this.panicSellWeight = panicSellWeight;
        this.dipBuyWeight = dipBuyWeight;
        this.holdingPatienceWeight = holdingPatienceWeight;
        this.deepLossHoldWeight = deepLossHoldWeight;
        this.executionPolicy = executionPolicy;
        this.behaviorSeedVersion = createBehaviorSeedVersion();
    }

    public ProfilePolicy overrideWith(AutoParticipantProfileConfig config) {
        double overriddenMarketMakingWeight = ratioOrDefault(config.marketMakingWeight(), marketMakingWeight);
        double overriddenProfitTakingWeight = ratioOrDefault(config.profitTakingWeight(), profitTakingWeight);
        double overriddenHoldingPatienceWeight = ratioOrDefault(config.holdingPatienceWeight(), holdingPatienceWeight);
        double overriddenOrderMultiplier = nonNegativeOrDefault(config.orderMultiplier(), orderMultiplier);
        double overriddenTtlMultiplier = positiveOrDefault(config.orderTtlMultiplier(), orderTtlMultiplier);
        ProfileExecutionPolicy overriddenExecutionPolicy = new ProfileExecutionPolicy(
                nonNegativeOrDefault(
                        config.decisionFrequencyMultiplier(),
                        executionPolicy.decisionFrequencyMultiplier()
                ),
                nonNegativeOrDefault(
                        config.ordersPerDecisionMultiplier(),
                        executionPolicy.ordersPerDecisionMultiplier()
                ),
                ProfilePricingMode.parseOrDefault(config.pricingMode(), executionPolicy.pricingMode()),
                ProfileExitMode.parseOrDefault(config.exitMode(), executionPolicy.exitMode()),
                ProfileInventoryMode.parseOrDefault(config.inventoryMode(), executionPolicy.inventoryMode())
        );
        return new ProfilePolicy(
                ratioOrDefault(config.newsWeight(), newsWeight),
                ratioOrDefault(config.momentumWeight(), momentumWeight),
                ratioOrDefault(config.contrarianWeight(), contrarianWeight),
                ratioOrDefault(config.lossAversionWeight(), lossAversionWeight),
                ratioOrDefault(config.herdingWeight(), herdingWeight),
                overriddenMarketMakingWeight,
                ratioOrDefault(config.overconfidenceWeight(), overconfidenceWeight),
                overriddenProfitTakingWeight,
                overriddenOrderMultiplier,
                nonNegativeOrDefault(config.aggressionMultiplier(), aggressionMultiplier),
                boundedOrDefault(config.pricePressureSensitivity(), pricePressureSensitivity, 0.0, 2.0),
                overriddenTtlMultiplier,
                ratioOrDefault(config.noiseWeight(), noiseWeight),
                nonNegativeOrDefault(config.quantityMultiplier(), quantityMultiplier),
                ratioOrDefault(config.panicSellWeight(), panicSellWeight),
                ratioOrDefault(config.dipBuyWeight(), dipBuyWeight),
                overriddenHoldingPatienceWeight,
                ratioOrDefault(config.deepLossHoldWeight(), deepLossHoldWeight),
                overriddenExecutionPolicy
        );
    }

    public ProfilePolicy withPricePressureSensitivity(double sensitivity) {
        return copy(Math.clamp(sensitivity, 0.0, 2.0), executionPolicy);
    }

    public ProfilePolicy withExecutionPolicy(ProfileExecutionPolicy value) {
        return copy(pricePressureSensitivity, value == null ? executionPolicy : value);
    }

    public ProfileExecutionPolicy legacyExecutionPolicy() {
        return ProfileExecutionPolicy.legacy(
                orderMultiplier,
                orderTtlMultiplier,
                marketMakingWeight,
                profitTakingWeight,
                holdingPatienceWeight
        );
    }

    public ProfilePolicy forLegacyExecution() {
        ProfileExecutionPolicy legacyPolicy = legacyExecutionPolicy();
        return executionPolicy.equals(legacyPolicy) ? this : copy(pricePressureSensitivity, legacyPolicy);
    }

    private ProfilePolicy copy(double sensitivity, ProfileExecutionPolicy value) {
        return new ProfilePolicy(
                newsWeight,
                momentumWeight,
                contrarianWeight,
                lossAversionWeight,
                herdingWeight,
                marketMakingWeight,
                overconfidenceWeight,
                profitTakingWeight,
                orderMultiplier,
                aggressionMultiplier,
                sensitivity,
                orderTtlMultiplier,
                noiseWeight,
                quantityMultiplier,
                panicSellWeight,
                dipBuyWeight,
                holdingPatienceWeight,
                deepLossHoldWeight,
                value
        );
    }

    public double newsWeight() { return newsWeight; }
    public double momentumWeight() { return momentumWeight; }
    public double contrarianWeight() { return contrarianWeight; }
    public double lossAversionWeight() { return lossAversionWeight; }
    public double herdingWeight() { return herdingWeight; }
    public double marketMakingWeight() { return marketMakingWeight; }
    public double overconfidenceWeight() { return overconfidenceWeight; }
    public double profitTakingWeight() { return profitTakingWeight; }
    public double orderMultiplier() { return orderMultiplier; }
    public double aggressionMultiplier() { return aggressionMultiplier; }
    public double pricePressureSensitivity() { return pricePressureSensitivity; }
    public double orderTtlMultiplier() { return orderTtlMultiplier; }
    public double noiseWeight() { return noiseWeight; }
    public double quantityMultiplier() { return quantityMultiplier; }
    public double panicSellWeight() { return panicSellWeight; }
    public double dipBuyWeight() { return dipBuyWeight; }
    public double holdingPatienceWeight() { return holdingPatienceWeight; }
    public double deepLossHoldWeight() { return deepLossHoldWeight; }
    public ProfileExecutionPolicy executionPolicy() { return executionPolicy; }
    public String behaviorSeedVersion() { return behaviorSeedVersion; }

    private static double positiveOrDefault(BigDecimal value, double defaultValue) {
        return value == null || value.signum() <= 0 ? defaultValue : value.doubleValue();
    }

    private static double nonNegativeOrDefault(BigDecimal value, double defaultValue) {
        return value == null || value.signum() < 0 ? defaultValue : value.doubleValue();
    }

    private static double boundedOrDefault(BigDecimal value, double defaultValue, double minimum, double maximum) {
        if (value == null) {
            return defaultValue;
        }
        double candidate = value.doubleValue();
        return candidate < minimum || candidate > maximum ? defaultValue : candidate;
    }

    private static double ratioOrDefault(BigDecimal value, double defaultValue) {
        return boundedOrDefault(value, defaultValue, 0.0, 1.0);
    }

    private String createBehaviorSeedVersion() {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, newsWeight);
        hash = mix(hash, momentumWeight);
        hash = mix(hash, contrarianWeight);
        hash = mix(hash, lossAversionWeight);
        hash = mix(hash, herdingWeight);
        hash = mix(hash, marketMakingWeight);
        hash = mix(hash, overconfidenceWeight);
        hash = mix(hash, profitTakingWeight);
        hash = mix(hash, orderMultiplier);
        hash = mix(hash, aggressionMultiplier);
        hash = mix(hash, pricePressureSensitivity);
        hash = mix(hash, orderTtlMultiplier);
        hash = mix(hash, noiseWeight);
        hash = mix(hash, quantityMultiplier);
        hash = mix(hash, panicSellWeight);
        hash = mix(hash, dipBuyWeight);
        hash = mix(hash, holdingPatienceWeight);
        hash = mix(hash, deepLossHoldWeight);
        hash = mix(hash, executionPolicy.decisionFrequencyMultiplier());
        hash = mix(hash, executionPolicy.ordersPerDecisionMultiplier());
        hash = mix(hash, executionPolicy.pricingMode().name());
        hash = mix(hash, executionPolicy.exitMode().name());
        hash = mix(hash, executionPolicy.inventoryMode().name());
        return Long.toUnsignedString(hash, 16);
    }

    private static long mix(long hash, double value) {
        long bits = Double.doubleToLongBits(value);
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            hash ^= bits >>> shift & 0xffL;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long mix(long hash, String value) {
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
