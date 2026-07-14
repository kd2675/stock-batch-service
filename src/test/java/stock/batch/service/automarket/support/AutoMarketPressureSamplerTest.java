package stock.batch.service.automarket.support;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class AutoMarketPressureSamplerTest {

    @Test
    void sample_neutralBias_favorsCenterWithoutLeavingRange() {
        Random random = new Random(42L);
        long centerCount = IntStream.range(0, 100_000)
                .map(ignored -> AutoMarketPressureSampler.sample(random, 0))
                .filter(value -> Math.abs(value) <= 25)
                .count();

        assertThat(centerCount).isBetween(43_000L, 45_000L);
    }

    @Test
    void sample_positiveEndpointBias_clustersMoreValuesNearPositiveEndpoint() {
        long neutralUpperQuartileCount = upperQuartileCount(0, 7L);
        long positiveUpperQuartileCount = upperQuartileCount(100, 7L);

        assertThat(positiveUpperQuartileCount).isGreaterThan(neutralUpperQuartileCount * 3);
    }

    @Test
    void sample_negativeEndpointBias_clustersMoreValuesNearNegativeEndpoint() {
        long neutralLowerQuartileCount = lowerQuartileCount(0, 11L);
        long negativeLowerQuartileCount = lowerQuartileCount(-100, 11L);

        assertThat(negativeLowerQuartileCount).isGreaterThan(neutralLowerQuartileCount * 3);
    }

    @Test
    void sample_fixedSeed_keepsCrossServiceContract() {
        Random random = new Random(20_260_714L);
        var samples = IntStream.of(-100, -50, 0, 50, 100)
                .map(bias -> AutoMarketPressureSampler.sample(random, bias))
                .boxed()
                .collect(Collectors.toList());

        assertThat(samples).containsExactly(-73, -26, 48, 23, 73);
    }

    private long upperQuartileCount(int bias, long seed) {
        Random random = new Random(seed);
        return IntStream.range(0, 100_000)
                .map(ignored -> AutoMarketPressureSampler.sample(random, bias))
                .filter(value -> value >= 75)
                .count();
    }

    private long lowerQuartileCount(int bias, long seed) {
        Random random = new Random(seed);
        return IntStream.range(0, 100_000)
                .map(ignored -> AutoMarketPressureSampler.sample(random, bias))
                .filter(value -> value <= -75)
                .count();
    }
}
