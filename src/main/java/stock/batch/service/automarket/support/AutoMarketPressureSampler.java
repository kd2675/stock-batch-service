package stock.batch.service.automarket.support;

import java.util.Random;

public final class AutoMarketPressureSampler {

    private static final double MIN = -100.0;
    private static final double MAX = 100.0;

    private AutoMarketPressureSampler() {
    }

    /** Samples a triangular distribution over [-100, 100] with the configured bias as its mode. */
    public static int sample(Random random, int bias) {
        int mode = Math.clamp(bias, -100, 100);
        double unit = random.nextDouble();
        double modeRatio = (mode - MIN) / (MAX - MIN);
        double sampled;
        if (unit < modeRatio) {
            sampled = MIN + Math.sqrt(unit * (MAX - MIN) * (mode - MIN));
        } else {
            sampled = MAX - Math.sqrt((1.0 - unit) * (MAX - MIN) * (MAX - mode));
        }
        return Math.clamp((int) Math.round(sampled), -100, 100);
    }
}
