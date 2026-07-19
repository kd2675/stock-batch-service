package stock.batch.service.automarket.biz;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.batch.automarket.reader.AutoMarketReader;
import stock.batch.service.execution.lock.OrderBookSymbolLock;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;
import stock.batch.service.simulation.SimulationClockService;
import stock.batch.service.simulation.SimulationMarketSessionService;

class AutoMarketMaintenanceVolumeConfigurationTest {

    @Test
    void expirySymbolLimit_aboveMaximum_rejectsStartup() {
        AutoMarketOrderExpiryJobService service = new AutoMarketOrderExpiryJobService(
                mock(AutoMarketReader.class),
                mock(AutoMarketOrderExpiryService.class),
                mock(AutoProfileBehaviorSupport.class),
                mock(SimulationClockService.class),
                mock(SimulationMarketSessionService.class),
                mock(TransactionTemplate.class),
                mock(OrderBookSymbolLock.class),
                mock(MarketSessionFenceService.class),
                new SimpleMeterRegistry()
        );
        ReflectionTestUtils.setField(service, "symbolLimitPerRun", 501);

        assertThatThrownBy(service::validateRetryConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auto-market-order-expiry.symbol-limit-per-run must be between 1 and 500");
    }

    @Test
    void listingSymbolLimit_aboveMaximum_rejectsStartup() {
        ListingAutoMarketJobService service = new ListingAutoMarketJobService(
                mock(AutoMarketReader.class),
                mock(ListingAutoAccountOrderService.class),
                mock(SimulationClockService.class),
                mock(SimulationMarketSessionService.class),
                mock(TransactionTemplate.class),
                mock(OrderBookSymbolLock.class),
                mock(MarketSessionFenceService.class),
                new SimpleMeterRegistry()
        );
        ReflectionTestUtils.setField(service, "symbolLimitPerRun", 501);

        assertThatThrownBy(service::validateRetryConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("listing-auto-market.symbol-limit-per-run must be between 1 and 500");
    }
}
