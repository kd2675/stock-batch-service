package stock.batch.service.automarket.biz;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import stock.batch.service.batch.automarket.reader.AutoMarketOrderReader;

class AutoMarketOrderExpiryServiceVolumeTest {

    @Test
    void validateVolumeConfiguration_expiryChunkAboveSafeMaximum_rejectsStartup() {
        AutoMarketOrderExpiryService service = new AutoMarketOrderExpiryService(
                mock(AutoMarketOrderReader.class),
                mock(AutoMarketOrderExecutor.class),
                mock(AutoProfileBehaviorSupport.class)
        );
        ReflectionTestUtils.setField(service, "expiryChunkLimit", 1_001);

        assertThatThrownBy(service::validateVolumeConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expiry-chunk-limit must be between 1 and 1000");
    }
}
