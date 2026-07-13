package stock.batch.service.automarket.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

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
    void run_twoSidedAtTarget_placesBothQuoteDeficitsWithinInventoryBand() {
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
        ListingAutoAccountConfig config = listingConfig(
                10L,
                "TWO_SIDED",
                25L,
                12L,
                100L,
                25L,
                "UP",
                "DOWN"
        );
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        when(listingReader.findEnabledListingAutoAccountConfigs(marketConfig)).thenReturn(List.of(config));
        when(clockService.currentSnapshot()).thenReturn(snapshot(now));
        when(orderExecutor.loadOrderBookState("LST001")).thenReturn(new AutoMarketOrderBookState(
                new BigDecimal("100.00"),
                new BigDecimal("110.00"),
                100,
                100
        ));
        when(orderReader.getOpenOrderQuantity(10L, "LST001", "BUY")).thenReturn(20L);
        when(orderReader.getOpenOrderQuantity(10L, "LST001", "SELL")).thenReturn(7L);
        when(listingReader.getCashBalance(10L)).thenReturn(new BigDecimal("100000.00"));
        when(listingReader.getAvailableQuantity(10L, "LST001")).thenReturn(100L);
        when(listingReader.getHoldingQuantity(10L, "LST001")).thenReturn(100L);
        when(orderExecutor.placeOrder(
                eq(10L),
                eq("LST001"),
                eq("BUY"),
                any(BigDecimal.class),
                eq(5L)
        )).thenReturn(true);
        when(orderExecutor.placeOrder(
                eq(10L),
                eq("LST001"),
                eq("SELL"),
                any(BigDecimal.class),
                eq(5L)
        )).thenReturn(true);

        int processed = service.run(marketConfig);

        assertThat(processed).isEqualTo(2);
        verify(orderExecutor).placeOrder(
                eq(10L),
                eq("LST001"),
                eq("BUY"),
                argThat(price -> price.compareTo(new BigDecimal("100.00")) > 0),
                eq(5L)
        );
        verify(orderExecutor).placeOrder(
                eq(10L),
                eq("LST001"),
                eq("SELL"),
                argThat(price -> price.compareTo(new BigDecimal("110.00")) < 0),
                eq(5L)
        );
    }

    @Test
    void run_twoSidedAboveUpperBand_cancelsBuyAndKeepsSellQuote() {
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
        ListingAutoAccountConfig config = listingConfig(
                10L,
                "TWO_SIDED",
                20L,
                20L,
                100L,
                20L,
                "DOWN",
                "UP"
        );
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        AutoOrder buyOrder = new AutoOrder(1L, 10L, "LST001", "BUY", 10L, 0L, new BigDecimal("1000.00"));
        when(listingReader.findEnabledListingAutoAccountConfigs(marketConfig)).thenReturn(List.of(config));
        when(clockService.currentSnapshot()).thenReturn(snapshot(now));
        when(listingReader.getHoldingQuantity(10L, "LST001")).thenReturn(121L);
        when(orderReader.findOpenListingAutoOrders(config, "BUY")).thenReturn(List.of(buyOrder));
        when(orderExecutor.expireOrders(List.of(buyOrder), now)).thenReturn(1);
        when(orderExecutor.loadOrderBookState("LST001"))
                .thenReturn(new AutoMarketOrderBookState(new BigDecimal("100.00"), new BigDecimal("110.00"), 0, 0));
        when(listingReader.getAvailableQuantity(10L, "LST001")).thenReturn(121L);
        when(orderExecutor.placeOrder(10L, "LST001", "SELL", new BigDecimal("111.00"), 10L)).thenReturn(true);

        int processed = service.run(marketConfig);

        assertThat(processed).isEqualTo(2);
        verify(orderExecutor).expireOrders(List.of(buyOrder), now);
        verify(orderExecutor).placeOrder(10L, "LST001", "SELL", new BigDecimal("111.00"), 10L);
    }

    @Test
    void run_twoSidedBelowLowerBand_cancelsSellAndKeepsBuyQuote() {
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
        ListingAutoAccountConfig config = listingConfig(
                10L,
                "TWO_SIDED",
                20L,
                20L,
                100L,
                20L,
                "DOWN",
                "UP"
        );
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        AutoOrder sellOrder = new AutoOrder(2L, 10L, "LST001", "SELL", 10L, 0L, BigDecimal.ZERO);
        when(listingReader.findEnabledListingAutoAccountConfigs(marketConfig)).thenReturn(List.of(config));
        when(clockService.currentSnapshot()).thenReturn(snapshot(now));
        when(listingReader.getHoldingQuantity(10L, "LST001")).thenReturn(79L);
        when(orderReader.findOpenListingAutoOrders(config, "SELL")).thenReturn(List.of(sellOrder));
        when(orderExecutor.expireOrders(List.of(sellOrder), now)).thenReturn(1);
        when(orderExecutor.loadOrderBookState("LST001"))
                .thenReturn(new AutoMarketOrderBookState(new BigDecimal("100.00"), new BigDecimal("110.00"), 0, 0));
        when(listingReader.getCashBalance(10L)).thenReturn(new BigDecimal("100000.00"));
        when(orderExecutor.placeOrder(10L, "LST001", "BUY", new BigDecimal("99.00"), 10L)).thenReturn(true);

        int processed = service.run(marketConfig);

        assertThat(processed).isEqualTo(2);
        verify(orderExecutor).expireOrders(List.of(sellOrder), now);
        verify(orderExecutor).placeOrder(10L, "LST001", "BUY", new BigDecimal("99.00"), 10L);
    }

    @Test
    void run_twoSidedInsideBand_capsBuyByUpperWorstCaseAndKeepsBothSides() {
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
        ListingAutoAccountConfig config = listingConfig(
                10L,
                "TWO_SIDED",
                20L,
                20L,
                100L,
                20L,
                "DOWN",
                "UP"
        );
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        when(listingReader.findEnabledListingAutoAccountConfigs(marketConfig)).thenReturn(List.of(config));
        when(clockService.currentSnapshot()).thenReturn(snapshot(now));
        when(listingReader.getHoldingQuantity(10L, "LST001")).thenReturn(110L);
        when(orderExecutor.loadOrderBookState("LST001"))
                .thenReturn(new AutoMarketOrderBookState(new BigDecimal("100.00"), new BigDecimal("110.00"), 0, 0));
        when(listingReader.getCashBalance(10L)).thenReturn(new BigDecimal("100000.00"));
        when(listingReader.getAvailableQuantity(10L, "LST001")).thenReturn(110L);
        when(orderExecutor.placeOrder(10L, "LST001", "BUY", new BigDecimal("99.00"), 10L)).thenReturn(true);
        when(orderExecutor.placeOrder(10L, "LST001", "SELL", new BigDecimal("111.00"), 10L)).thenReturn(true);

        int processed = service.run(marketConfig);

        assertThat(processed).isEqualTo(2);
        verify(orderExecutor).placeOrder(10L, "LST001", "BUY", new BigDecimal("99.00"), 10L);
        verify(orderExecutor).placeOrder(10L, "LST001", "SELL", new BigDecimal("111.00"), 10L);
    }

    @Test
    void run_targetReduced_cancelsExcessOrderThenRefillsExactDeficit() {
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
        ListingAutoAccountConfig config = listingConfig(10L, "SELL_ONLY", 0L, 10L, "DOWN", "UP");
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        AutoOrder first = new AutoOrder(1L, 10L, "LST001", "SELL", 8L, 0L, BigDecimal.ZERO);
        AutoOrder second = new AutoOrder(2L, 10L, "LST001", "SELL", 7L, 0L, BigDecimal.ZERO);
        when(listingReader.findEnabledListingAutoAccountConfigs(marketConfig)).thenReturn(List.of(config));
        when(clockService.currentSnapshot()).thenReturn(snapshot(now));
        when(orderReader.findOpenListingAutoOrders(config, "SELL")).thenReturn(List.of(first, second));
        when(orderExecutor.expireOrders(List.of(first), now)).thenReturn(1);
        when(orderExecutor.loadOrderBookState("LST001"))
                .thenReturn(new AutoMarketOrderBookState(new BigDecimal("100.00"), new BigDecimal("110.00"), 0, 7));
        when(orderReader.getOpenOrderQuantity(10L, "LST001", "SELL")).thenReturn(7L);
        when(listingReader.getAvailableQuantity(10L, "LST001")).thenReturn(100L);
        when(listingReader.getHoldingQuantity(10L, "LST001")).thenReturn(100L);
        when(orderExecutor.placeOrder(
                eq(10L),
                eq("LST001"),
                eq("SELL"),
                argThat(price -> price.compareTo(new BigDecimal("110.00")) > 0),
                eq(3L)
        )).thenReturn(true);

        int processed = service.run(marketConfig);

        assertThat(processed).isEqualTo(2);
        verify(orderExecutor).expireOrders(List.of(first), now);
        verify(orderExecutor).placeOrder(
                eq(10L),
                eq("LST001"),
                eq("SELL"),
                argThat(price -> price.compareTo(new BigDecimal("110.00")) > 0),
                eq(3L)
        );
    }

    @Test
    void run_holdingAboveTarget_capsTotalSellOrdersAtRemainingTargetGap() {
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
        ListingAutoAccountConfig config = new ListingAutoAccountConfig(
                "LST001",
                10L,
                "listing-user-10",
                "SELL_ONLY",
                300_000,
                90,
                0,
                0L,
                300_000L,
                2_000_000L,
                "DOWN",
                "UP",
                BigDecimal.ONE,
                new BigDecimal("5160.00"),
                new BigDecimal("5160.00"),
                BigDecimal.valueOf(30)
        );
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        when(listingReader.findEnabledListingAutoAccountConfigs(marketConfig)).thenReturn(List.of(config));
        when(clockService.currentSnapshot()).thenReturn(snapshot(now));
        when(listingReader.getHoldingQuantity(10L, "LST001")).thenReturn(2_181_248L);
        when(listingReader.getAvailableQuantity(10L, "LST001")).thenReturn(2_181_248L);
        when(orderExecutor.loadOrderBookState("LST001"))
                .thenReturn(new AutoMarketOrderBookState(new BigDecimal("5150.00"), new BigDecimal("5160.00"), 0L, 0L));
        when(orderExecutor.placeOrder(10L, "LST001", "SELL", new BigDecimal("5160.00"), 181_248L))
                .thenReturn(true);

        int processed = service.run(marketConfig);

        assertThat(processed).isEqualTo(1);
        verify(orderExecutor).placeOrder(10L, "LST001", "SELL", new BigDecimal("5160.00"), 181_248L);
    }

    @Test
    void run_holdingAlreadyAtSellTarget_doesNotPlaceSellOrder() {
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
        ListingAutoAccountConfig config = listingConfig(
                10L,
                "SELL_ONLY",
                0L,
                300_000L,
                2_000_000L,
                "DOWN",
                "UP"
        );
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        when(listingReader.findEnabledListingAutoAccountConfigs(marketConfig)).thenReturn(List.of(config));
        when(clockService.currentSnapshot()).thenReturn(snapshot(now));
        when(listingReader.getHoldingQuantity(10L, "LST001")).thenReturn(2_000_000L);
        when(orderExecutor.loadOrderBookState("LST001"))
                .thenReturn(new AutoMarketOrderBookState(new BigDecimal("5150.00"), new BigDecimal("5160.00"), 0L, 0L));

        int processed = service.run(marketConfig);

        assertThat(processed).isZero();
        verify(orderExecutor, org.mockito.Mockito.never()).placeOrder(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong()
        );
    }

    @Test
    void run_holdingBelowTarget_capsBuyOrdersAtRemainingTargetGap() {
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
        ListingAutoAccountConfig config = new ListingAutoAccountConfig(
                "LST001",
                10L,
                "listing-user-10",
                "BUY_ONLY",
                300_000,
                90,
                0,
                300_000L,
                0L,
                2_000_000L,
                "DOWN",
                "UP",
                BigDecimal.ONE,
                new BigDecimal("5160.00"),
                new BigDecimal("5160.00"),
                BigDecimal.valueOf(30)
        );
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        when(listingReader.findEnabledListingAutoAccountConfigs(marketConfig)).thenReturn(List.of(config));
        when(clockService.currentSnapshot()).thenReturn(snapshot(now));
        when(listingReader.getHoldingQuantity(10L, "LST001")).thenReturn(1_800_000L);
        when(orderReader.getOpenOrderQuantity(10L, "LST001", "BUY")).thenReturn(50_000L);
        when(listingReader.getCashBalance(10L)).thenReturn(new BigDecimal("1000000000.00"));
        when(orderExecutor.loadOrderBookState("LST001"))
                .thenReturn(new AutoMarketOrderBookState(new BigDecimal("5160.00"), new BigDecimal("5170.00"), 50_000L, 0L));
        when(orderExecutor.placeOrder(10L, "LST001", "BUY", new BigDecimal("5160.00"), 150_000L))
                .thenReturn(true);

        int processed = service.run(marketConfig);

        assertThat(processed).isEqualTo(1);
        verify(orderExecutor).placeOrder(10L, "LST001", "BUY", new BigDecimal("5160.00"), 150_000L);
    }

    @Test
    void run_holdingAlreadyAtBuyTarget_doesNotPlaceBuyOrder() {
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
        ListingAutoAccountConfig config = listingConfig(
                10L,
                "BUY_ONLY",
                300_000L,
                0L,
                2_000_000L,
                "DOWN",
                "UP"
        );
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        when(listingReader.findEnabledListingAutoAccountConfigs(marketConfig)).thenReturn(List.of(config));
        when(clockService.currentSnapshot()).thenReturn(snapshot(now));
        when(listingReader.getHoldingQuantity(10L, "LST001")).thenReturn(2_000_000L);
        when(orderExecutor.loadOrderBookState("LST001"))
                .thenReturn(new AutoMarketOrderBookState(new BigDecimal("5150.00"), new BigDecimal("5160.00"), 0L, 0L));

        int processed = service.run(marketConfig);

        assertThat(processed).isZero();
        verify(orderExecutor, org.mockito.Mockito.never()).placeOrder(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong()
        );
    }

    @Test
    void run_holdingReachesBuyTarget_cancelsRemainingBuyOrder() {
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
        ListingAutoAccountConfig config = listingConfig(
                10L,
                "BUY_ONLY",
                300_000L,
                0L,
                2_000_000L,
                "DOWN",
                "UP"
        );
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        AutoOrder remainingBuyOrder = new AutoOrder(
                1L,
                10L,
                "LST001",
                "BUY",
                50_000L,
                0L,
                new BigDecimal("258000000.00")
        );
        when(listingReader.findEnabledListingAutoAccountConfigs(marketConfig)).thenReturn(List.of(config));
        when(clockService.currentSnapshot()).thenReturn(snapshot(now));
        when(listingReader.getHoldingQuantity(10L, "LST001")).thenReturn(2_000_000L);
        when(orderReader.findOpenListingAutoOrders(config, "BUY")).thenReturn(List.of(remainingBuyOrder));
        when(orderExecutor.expireOrders(List.of(remainingBuyOrder), now)).thenReturn(1);
        when(orderExecutor.loadOrderBookState("LST001"))
                .thenReturn(new AutoMarketOrderBookState(new BigDecimal("5150.00"), new BigDecimal("5160.00"), 0L, 0L));

        int processed = service.run(marketConfig);

        assertThat(processed).isEqualTo(1);
        verify(orderExecutor).expireOrders(List.of(remainingBuyOrder), now);
    }

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
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                BigDecimal.valueOf(30)
        );
    }

    private ListingAutoAccountConfig listingConfig(
            long accountId,
            String positionSide,
            long targetBuyQuantity,
            long targetSellQuantity,
            String buyDirection,
            String sellDirection
    ) {
        return listingConfig(
                accountId,
                positionSide,
                targetBuyQuantity,
                targetSellQuantity,
                0L,
                0L,
                buyDirection,
                sellDirection
        );
    }

    private ListingAutoAccountConfig listingConfig(
            long accountId,
            String positionSide,
            long targetBuyQuantity,
            long targetSellQuantity,
            long targetHoldingQuantity,
            String buyDirection,
            String sellDirection
    ) {
        return listingConfig(
                accountId,
                positionSide,
                targetBuyQuantity,
                targetSellQuantity,
                targetHoldingQuantity,
                0L,
                buyDirection,
                sellDirection
        );
    }

    private ListingAutoAccountConfig listingConfig(
            long accountId,
            String positionSide,
            long targetBuyQuantity,
            long targetSellQuantity,
            long targetHoldingQuantity,
            long inventoryBandQuantity,
            String buyDirection,
            String sellDirection
    ) {
        return new ListingAutoAccountConfig(
                "LST001",
                accountId,
                "listing-user-" + accountId,
                positionSide,
                10,
                90,
                1,
                targetBuyQuantity,
                targetSellQuantity,
                targetHoldingQuantity,
                inventoryBandQuantity,
                buyDirection,
                sellDirection,
                BigDecimal.ONE,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
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
