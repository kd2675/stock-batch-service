package stock.batch.service.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import stock.batch.service.batch.common.support.StockBatchJobLauncher;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;
import stock.batch.service.simulation.SimulationMarketSessionService;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class OrderBookExecutionSchedulerConfigurationTest {

    @Test
    void validateFallbackConfiguration_aggressiveDatabasePolling_rejectsStartup() {
        OrderBookExecutionScheduler scheduler = new OrderBookExecutionScheduler(
                mock(StockBatchJobLauncher.class),
                mock(StockBatchScheduledJobGuard.class),
                mock(SimulationMarketSessionService.class),
                mock(MarketSessionFenceService.class)
        );
        ReflectionTestUtils.setField(scheduler, "fallbackFixedDelayMillis", 9_999L);

        assertThatThrownBy(scheduler::validateFallbackConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fixed-delay-ms must be between 10000 and 300000");
    }
}
