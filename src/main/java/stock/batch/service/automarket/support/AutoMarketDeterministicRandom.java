package stock.batch.service.automarket.support;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.SplittableRandom;

import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;

public final class AutoMarketDeterministicRandom {

    private AutoMarketDeterministicRandom() {
    }

    public static double symmetricNoise(
            AutoParticipantStrategy strategy,
            String symbol,
            LocalDateTime fallbackDecisionSlot,
            double amplitude,
            String policyVersion
    ) {
        if (amplitude <= 0) {
            return 0.0;
        }
        LocalDateTime decisionSlot = strategy.decisionSlotAt() == null
                ? fallbackDecisionSlot
                : strategy.decisionSlotAt();
        long hash = seed(strategy, symbol, decisionSlot, policyVersion);
        return (new SplittableRandom(hash).nextDouble() * 2.0 - 1.0) * amplitude;
    }

    public static long seed(
            AutoParticipantStrategy strategy,
            String symbol,
            LocalDateTime fallbackDecisionSlot,
            String policyVersion
    ) {
        LocalDateTime decisionSlot = strategy.decisionSlotAt() == null
                ? fallbackDecisionSlot
                : strategy.decisionSlotAt();
        long hash = strategy.behaviorSeed();
        hash = mix(hash, strategy.userKey());
        hash = mix(hash, strategy.profileType() == null ? "" : strategy.profileType().name());
        hash = mix(hash, symbol);
        hash = mix(hash, decisionSlot == null ? "" : decisionSlot.toString());
        return mix(hash, policyVersion);
    }

    public static double stableUnitInterval(
            AutoParticipantStrategy strategy,
            String policyTrait
    ) {
        if (strategy == null) {
            return 0.5;
        }
        long hash = strategy.behaviorSeed();
        hash = mix(hash, strategy.userKey());
        hash = mix(hash, strategy.profileType() == null ? "" : strategy.profileType().name());
        hash = mix(hash, policyTrait);
        return new SplittableRandom(hash).nextDouble();
    }

    public static double stableRange(
            AutoParticipantStrategy strategy,
            String policyTrait,
            double minimum,
            double maximum
    ) {
        if (!Double.isFinite(minimum) || !Double.isFinite(maximum) || maximum < minimum) {
            throw new IllegalArgumentException("Invalid stable trait range: " + minimum + ".." + maximum);
        }
        return minimum + (maximum - minimum) * stableUnitInterval(strategy, policyTrait);
    }

    public static long stableLongRange(
            AutoParticipantStrategy strategy,
            String policyTrait,
            long minimum,
            long maximum
    ) {
        if (maximum < minimum) {
            throw new IllegalArgumentException("Invalid stable trait range: " + minimum + ".." + maximum);
        }
        long bound = Math.addExact(Math.subtractExact(maximum, minimum), 1L);
        if (strategy == null) {
            return minimum + Math.floorDiv(bound - 1L, 2L);
        }
        long hash = strategy.behaviorSeed();
        hash = mix(hash, strategy.userKey());
        hash = mix(hash, strategy.profileType() == null ? "" : strategy.profileType().name());
        hash = mix(hash, policyTrait);
        return minimum + new SplittableRandom(hash).nextLong(bound);
    }

    private static long mix(long seed, String value) {
        long hash = seed ^ 0x9e3779b97f4a7c15L;
        if (value == null) {
            return hash;
        }
        for (byte next : value.getBytes(StandardCharsets.UTF_8)) {
            hash ^= next & 0xffL;
            hash *= 0x100000001b3L;
            hash ^= hash >>> 32;
        }
        return hash;
    }
}
