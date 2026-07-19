package stock.batch.service.batch.settlement.job;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PortfolioSettlementJobVolumeConfigurationTest {

    @Test
    void validateChunkSize_aboveMaximum_throws() {
        assertThatThrownBy(() -> PortfolioSettlementJob.validateChunkSize(2_001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 2000");
    }

    @Test
    void validateChunkSize_atMaximum_doesNotThrow() {
        assertThatCode(() -> PortfolioSettlementJob.validateChunkSize(2_000))
                .doesNotThrowAnyException();
    }
}
