package stock.batch.service.batch.automarket.model;

import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AutoMarketRegimeCountWeightsTest {

    @Test
    void selectCount_oneTimeOnly_alwaysReturnsOne() {
        AutoMarketRegimeCountWeights weights = new AutoMarketRegimeCountWeights(100, 0, 0, 0);

        assertThat(weights.selectCount(new Random(1L))).isEqualTo(1);
    }

    @Test
    void selectCount_fourTimesOnly_alwaysReturnsFour() {
        AutoMarketRegimeCountWeights weights = new AutoMarketRegimeCountWeights(0, 0, 0, 100);

        assertThat(weights.selectCount(new Random(1L))).isEqualTo(4);
    }

    @Test
    void selectCount_twoTimesOnly_alwaysReturnsTwo() {
        AutoMarketRegimeCountWeights weights = new AutoMarketRegimeCountWeights(0, 100, 0, 0);

        assertThat(weights.selectCount(new Random(1L))).isEqualTo(2);
    }

    @Test
    void selectCount_threeTimesOnly_alwaysReturnsThree() {
        AutoMarketRegimeCountWeights weights = new AutoMarketRegimeCountWeights(0, 0, 100, 0);

        assertThat(weights.selectCount(new Random(1L))).isEqualTo(3);
    }

    @Test
    void selectCount_allZero_fallsBackToFour() {
        AutoMarketRegimeCountWeights weights = new AutoMarketRegimeCountWeights(0, 0, 0, 0);

        assertThat(weights.selectCount(new Random(1L))).isEqualTo(4);
    }

    @Test
    void selectCount_mixedWeights_convergesToConfiguredRatio() {
        AutoMarketRegimeCountWeights weights = new AutoMarketRegimeCountWeights(10, 20, 30, 40);
        Random random = new Random(20260719L);
        int sampleCount = 100_000;
        int[] counts = new int[4];
        for (int index = 0; index < sampleCount; index++) {
            counts[weights.selectCount(random) - 1]++;
        }

        double[] expectedRatios = {0.1, 0.2, 0.3, 0.4};
        double maxDeviation = 0.0;
        for (int index = 0; index < counts.length; index++) {
            double actualRatio = (double) counts[index] / sampleCount;
            maxDeviation = Math.max(maxDeviation, Math.abs(actualRatio - expectedRatios[index]));
        }

        assertThat(maxDeviation).isLessThan(0.01);
    }
}
