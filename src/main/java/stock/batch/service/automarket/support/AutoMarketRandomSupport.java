package stock.batch.service.automarket.support;

import java.util.concurrent.ThreadLocalRandom;
import java.util.SplittableRandom;
import java.util.function.Supplier;

public final class AutoMarketRandomSupport {

    private static final ThreadLocal<SplittableRandom> SCOPED_RANDOM = new ThreadLocal<>();

    private AutoMarketRandomSupport() {
    }

    public static boolean chance(double probability) {
        SplittableRandom scoped = SCOPED_RANDOM.get();
        return (scoped == null ? ThreadLocalRandom.current().nextDouble() : scoped.nextDouble()) < probability;
    }

    public static double noise(double weight, double scale) {
        if (weight <= 0) {
            return 0;
        }
        SplittableRandom scoped = SCOPED_RANDOM.get();
        double sampled = scoped == null
                ? ThreadLocalRandom.current().nextDouble(-1, 1)
                : scoped.nextDouble(-1, 1);
        return sampled * weight * scale;
    }

    public static int nextInt(int minInclusive, int maxInclusive) {
        SplittableRandom scoped = SCOPED_RANDOM.get();
        return scoped == null
                ? ThreadLocalRandom.current().nextInt(minInclusive, maxInclusive + 1)
                : scoped.nextInt(minInclusive, maxInclusive + 1);
    }

    public static <T> T withSeed(long seed, Supplier<T> action) {
        SplittableRandom previous = SCOPED_RANDOM.get();
        SCOPED_RANDOM.set(new SplittableRandom(seed));
        try {
            return action.get();
        } finally {
            if (previous == null) {
                SCOPED_RANDOM.remove();
            } else {
                SCOPED_RANDOM.set(previous);
            }
        }
    }
}
