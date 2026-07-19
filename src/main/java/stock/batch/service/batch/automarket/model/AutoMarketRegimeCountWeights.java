package stock.batch.service.batch.automarket.model;

import java.util.Random;

public record AutoMarketRegimeCountWeights(
        int oneTime,
        int twoTimes,
        int threeTimes,
        int fourTimes
) {
    public static final AutoMarketRegimeCountWeights ALWAYS_FOUR = new AutoMarketRegimeCountWeights(0, 0, 0, 100);

    public AutoMarketRegimeCountWeights {
        oneTime = normalize(oneTime);
        twoTimes = normalize(twoTimes);
        threeTimes = normalize(threeTimes);
        fourTimes = normalize(fourTimes);
    }

    public int selectCount(Random random) {
        int total = total();
        if (total <= 0) {
            return 4;
        }
        int draw = random.nextInt(total);
        if (draw < oneTime) {
            return 1;
        }
        draw -= oneTime;
        if (draw < twoTimes) {
            return 2;
        }
        draw -= twoTimes;
        if (draw < threeTimes) {
            return 3;
        }
        return 4;
    }

    private int total() {
        return oneTime + twoTimes + threeTimes + fourTimes;
    }

    private static int normalize(int value) {
        return Math.clamp(value, 0, 100);
    }
}
