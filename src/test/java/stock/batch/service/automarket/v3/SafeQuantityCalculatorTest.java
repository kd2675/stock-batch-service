package stock.batch.service.automarket.v3;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class SafeQuantityCalculatorTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2027, 1, 18);
    private static final AutoParticipantV3Policy POLICY = AutoParticipantV3Policy.defaults(3L);

    private final SafeQuantityCalculator calculator = new SafeQuantityCalculator();

    @Test
    void calculate_buyAffordability_includesFeeBeforeSampling() {
        SafeQuantityLimit limit = calculator.calculate(input(
                true,
                true,
                new BigDecimal("1000.00"),
                new BigDecimal("100.00"),
                new BigDecimal("0.0100"),
                1_000L,
                1_000L,
                0L,
                1_000L,
                1_000L,
                1_000L,
                1_000L,
                1_000L
        ));

        assertThat(limit.safeMaximum()).isEqualTo(9L);
    }

    @Test
    void calculate_passiveOrder_doesNotRequireOppositeDepth() {
        SafeQuantityLimit limit = calculator.calculate(input(
                true,
                false,
                new BigDecimal("100000.00"),
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                500L,
                500L,
                0L,
                500L,
                500L,
                0L,
                500L,
                500L
        ));

        assertThat(limit.safeMaximum()).isEqualTo(500L);
    }

    @Test
    void calculate_aggressiveOrder_appliesOppositeDepthAsSafetyLimit() {
        SafeQuantityLimit limit = calculator.calculate(input(
                true,
                true,
                new BigDecimal("100000.00"),
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                500L,
                500L,
                0L,
                500L,
                500L,
                17L,
                500L,
                500L
        ));

        assertThat(limit.bindingReason()).isEqualTo(SafeQuantityBindingReason.OPPOSITE_DEPTH);
    }

    @Test
    void sample_ordinaryDistribution_isSmallOrderBiasedWithoutMaximumClampSpike() {
        SafeQuantityLimit limit = calculator.calculate(input(
                true,
                false,
                new BigDecimal("1000000.00"),
                BigDecimal.ONE,
                BigDecimal.ZERO,
                1_000L,
                1_000L,
                0L,
                1_000L,
                1_000L,
                0L,
                1_000L,
                1_000L
        ));
        List<Long> quantities = new ArrayList<>();
        for (long eventSequence = 1L; eventSequence <= 2_000L; eventSequence++) {
            SafeQuantityCalculator.QuantitySample sample = calculator.sample(
                    limit,
                    AutoParticipantDecisionUrgency.VOLUNTARY,
                    87_341L,
                    TRADE_DATE,
                    eventSequence,
                    new AutoParticipantV3Policy(3L, -0.35, 1.7, 1.15, 2_700, 180, 3.0, 0.0)
            );
            quantities.add(sample.quantity());
        }
        Collections.sort(quantities);
        long median = quantities.get(quantities.size() / 2);
        long maximumCount = quantities.stream().filter(quantity -> quantity == 1_000L).count();

        assertThat(median).isLessThan(200L);
        assertThat(maximumCount).isZero();
    }

    @Test
    void sample_mandatoryClose_stillCannotExceedSafetyMaximum() {
        SafeQuantityLimit limit = calculator.calculate(input(
                false,
                true,
                BigDecimal.ZERO,
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                10_000L,
                10_000L,
                37L,
                10_000L,
                10_000L,
                10_000L,
                10_000L,
                10_000L
        ));

        long quantity = calculator.sample(
                limit,
                AutoParticipantDecisionUrgency.MANDATORY_CLOSE,
                42L,
                TRADE_DATE,
                3L,
                POLICY
        ).quantity();

        assertThat(quantity).isBetween(1L, 37L);
    }

    private SafeQuantityCalculator.LimitInput input(
            boolean buy,
            boolean aggressive,
            BigDecimal cash,
            BigDecimal price,
            BigDecimal feeRate,
            long symbolMaximum,
            long openAllowance,
            long sellable,
            long allocation,
            long budget,
            long oppositeDepth,
            long averageDailyVolume,
            long turnover
    ) {
        return new SafeQuantityCalculator.LimitInput(
                buy,
                aggressive,
                cash,
                price,
                feeRate,
                symbolMaximum,
                openAllowance,
                sellable,
                allocation,
                budget,
                oppositeDepth,
                averageDailyVolume,
                turnover
        );
    }
}
