package stock.batch.service.automarket.support;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SimulationTimeScaleTest {

    @Test
    void projectDurationToRuntime_convertsProjectDayToRealHour() {
        assertThat(SimulationTimeScale.projectDurationToRuntime(Duration.ofDays(1)))
                .isEqualTo(Duration.ofHours(1));
        assertThat(SimulationTimeScale.projectDurationToRuntime(Duration.ofDays(7)))
                .isEqualTo(Duration.ofHours(7));
        assertThat(SimulationTimeScale.projectDurationToRuntime(Duration.ofHours(1)))
                .isEqualTo(Duration.ofMinutes(2).plusSeconds(30));
    }

    @Test
    void projectAutoOrderTtlToRuntimeSeconds_keepsOperationalMinimum() {
        assertThat(SimulationTimeScale.projectAutoOrderTtlToRuntimeSeconds(15)).isEqualTo(3);
        assertThat(SimulationTimeScale.projectAutoOrderTtlToRuntimeSeconds(60)).isEqualTo(3);
        assertThat(SimulationTimeScale.projectAutoOrderTtlToRuntimeSeconds(150)).isEqualTo(7);
    }
}
