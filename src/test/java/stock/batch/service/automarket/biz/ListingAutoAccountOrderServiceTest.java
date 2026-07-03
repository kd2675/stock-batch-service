package stock.batch.service.automarket.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoOrder;
import stock.batch.service.batch.automarket.model.ListingAutoAccountConfig;
import stock.batch.service.batch.automarket.reader.AutoMarketOrderReader;
import stock.batch.service.batch.automarket.reader.ListingAutoAccountReader;
import stock.batch.service.simulation.SimulationClockService;
import web.common.core.simulation.SimulationClockSnapshot;

class ListingAutoAccountOrderServiceTest {

    @Test
    void run_collectsExpiredOrdersAndExpiresThemInOneBatch() {
        ListingAutoAccountReader listingReader = mock(ListingAutoAccountReader.class);
        AutoMarketOrderReader orderReader = mock(AutoMarketOrderReader.class);
        AutoMarketOrderExecutor orderExecutor = mock(AutoMarketOrderExecutor.class);
        SimulationClockService clockService = mock(SimulationClockService.class);
        ListingAutoAccountOrderService service = new ListingAutoAccountOrderService(
                listingReader,
                orderReader,
                orderExecutor,
                clockService
        );
        AutoMarketConfig marketConfig = marketConfig("LST001");
        ListingAutoAccountConfig firstConfig = listingConfig(10L);
        ListingAutoAccountConfig secondConfig = listingConfig(20L);
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        AutoOrder firstOrder = new AutoOrder(1L, 10L, "LST001", "BUY", 1, 0, new BigDecimal("1000.00"));
        AutoOrder secondOrder = new AutoOrder(2L, 20L, "LST001", "SELL", 2, 0, BigDecimal.ZERO);
        when(listingReader.findEnabledListingAutoAccountConfigs(marketConfig))
                .thenReturn(List.of(firstConfig, secondConfig));
        when(clockService.currentSnapshot()).thenReturn(snapshot(now));
        when(clockService.currentMarketDateTime()).thenReturn(now);
        when(orderReader.findExpiredListingAutoOrders(firstConfig, now.minusSeconds(90)))
                .thenReturn(List.of(firstOrder));
        when(orderReader.findExpiredListingAutoOrders(secondConfig, now.minusSeconds(90)))
                .thenReturn(List.of(secondOrder));
        when(orderExecutor.expireOrders(List.of(firstOrder, secondOrder), now)).thenReturn(2);
        when(orderExecutor.loadOrderBookState("LST001")).thenReturn(new AutoMarketOrderBookState(null, null, 0, 0));

        int processed = service.run(marketConfig);

        assertThat(processed).isEqualTo(2);
        verify(orderExecutor).expireOrders(List.of(firstOrder, secondOrder), now);
    }

    private AutoMarketConfig marketConfig(String symbol) {
        return new AutoMarketConfig(
                symbol,
                100,
                10,
                90,
                100_000L,
                BigDecimal.ONE,
                new BigDecimal("1000.00"),
                new BigDecimal("1000.00"),
                BigDecimal.valueOf(30),
                null
        );
    }

    private ListingAutoAccountConfig listingConfig(long accountId) {
        return new ListingAutoAccountConfig(
                "LST001",
                accountId,
                "listing-user-" + accountId,
                "NONE",
                10,
                90,
                1,
                BigDecimal.ONE,
                new BigDecimal("1000.00"),
                new BigDecimal("1000.00"),
                BigDecimal.valueOf(30)
        );
    }

    private SimulationClockSnapshot snapshot(LocalDateTime now) {
        return new SimulationClockSnapshot(
                LocalDate.of(2026, 7, 3),
                now,
                now.toLocalDate().atStartOfDay(),
                now,
                now.toLocalDate().atStartOfDay(),
                7200,
                true,
                false,
                0,
                now,
                now
        );
    }
}
