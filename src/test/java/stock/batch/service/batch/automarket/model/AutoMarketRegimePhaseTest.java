package stock.batch.service.batch.automarket.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class AutoMarketRegimePhaseTest {

    @ParameterizedTest
    @CsvSource({
            "06:00, SLOT_0600",
            "08:59, SLOT_0600",
            "09:00, SLOT_0900",
            "11:59, SLOT_0900",
            "12:00, SLOT_1200",
            "14:59, SLOT_1200",
            "15:00, SLOT_1500",
            "17:59, SLOT_1500"
    })
    void from_boundaryTime_resolvesExpectedSlot(LocalTime time, AutoMarketRegimePhase expected) {
        assertThat(AutoMarketRegimePhase.from(time)).isEqualTo(expected);
    }
}
