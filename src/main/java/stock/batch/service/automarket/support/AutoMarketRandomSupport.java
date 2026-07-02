package stock.batch.service.automarket.support;

import java.util.concurrent.ThreadLocalRandom;

public final class AutoMarketRandomSupport {

    private AutoMarketRandomSupport() {
    }

    public static boolean chance(double probability) {
        return ThreadLocalRandom.current().nextDouble() < probability;
    }

    public static double noise(double weight, double scale) {
        if (weight <= 0) {
            return 0;
        }
        return ThreadLocalRandom.current().nextDouble(-1, 1) * weight * scale;
    }

    public static int nextInt(int minInclusive, int maxInclusive) {
        return ThreadLocalRandom.current().nextInt(minInclusive, maxInclusive + 1);
    }
}
