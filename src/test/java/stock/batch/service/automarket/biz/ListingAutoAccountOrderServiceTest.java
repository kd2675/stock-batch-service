package stock.batch.service.automarket.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoOrder;
import stock.batch.service.batch.automarket.model.ListingAutoAccountConfig;
import stock.batch.service.batch.automarket.reader.AutoMarketOrderReader;
import stock.batch.service.batch.automarket.reader.ListingAutoAccountReader;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;

class ListingAutoAccountOrderServiceTest {

    private ListingAutoAccountReader listingReader;
    private AutoMarketOrderReader orderReader;
    private AutoMarketOrderExecutor orderExecutor;
    private ListingAutoAccountOrderService service;

    @BeforeEach
    void setUp() {
        listingReader = mock(ListingAutoAccountReader.class);
        orderReader = mock(AutoMarketOrderReader.class);
        orderExecutor = mock(AutoMarketOrderExecutor.class);
        service = new ListingAutoAccountOrderService(listingReader, orderReader, orderExecutor);
    }

    @Test
    void run_twoSidedAtTarget_placesBothQuoteDeficitsWithinInventoryBand() {
        ListingAutoAccountConfig config = listingConfig(10L, "TWO_SIDED", 10, 25L, 12L, 100L, 25L, "UP", "DOWN");
        prepare(config, 100L, 100L, new BigDecimal("100000.00"), new BigDecimal("100.00"), new BigDecimal("110.00"));
        when(orderReader.getOpenOrderQuantity(10L, "LST001", "BUY")).thenReturn(20L);
        when(orderReader.getOpenOrderQuantity(10L, "LST001", "SELL")).thenReturn(7L);
        acceptAllPlannedOrders();

        int processed = service.run(marketConfig(), sessionApproval());

        List<AutoMarketPlannedOrder> orders = capturePlannedOrders();
        assertThat(processed).isEqualTo(2);
        assertThat(orders)
                .extracting(AutoMarketPlannedOrder::side, AutoMarketPlannedOrder::quantity)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("BUY", 5L),
                        org.assertj.core.groups.Tuple.tuple("SELL", 5L)
                );
        assertThat(orders.stream().filter(order -> "BUY".equals(order.side())).findFirst().orElseThrow().price())
                .isBetween(new BigDecimal("100.01"), new BigDecimal("109.99"));
        assertThat(orders.stream().filter(order -> "SELL".equals(order.side())).findFirst().orElseThrow().price())
                .isBetween(new BigDecimal("100.01"), new BigDecimal("109.99"));
    }

    @Test
    void run_twoSidedAboveUpperBand_cancelsBuyAndRefillsSellTarget() {
        ListingAutoAccountConfig config = listingConfig(10L, "TWO_SIDED", 10, 20L, 20L, 100L, 20L, "DOWN", "UP");
        AutoOrder buyOrder = new AutoOrder(1L, 10L, "LST001", "BUY", 10L, 0L, new BigDecimal("1000.00"));
        prepare(config, 121L, 121L, BigDecimal.ZERO, new BigDecimal("100.00"), new BigDecimal("110.00"));
        when(orderReader.findOpenListingAutoOrders(config, "BUY")).thenReturn(List.of(buyOrder));
        when(orderExecutor.expireOrders(List.of(buyOrder), now())).thenReturn(1);
        acceptAllPlannedOrders();

        int processed = service.run(marketConfig(), sessionApproval());

        List<AutoMarketPlannedOrder> orders = capturePlannedOrders();
        assertThat(processed).isEqualTo(3);
        assertThat(orders).hasSize(2).allSatisfy(order -> {
            assertThat(order.side()).isEqualTo("SELL");
            assertThat(order.quantity()).isEqualTo(10L);
        });
        verify(orderExecutor).expireOrders(List.of(buyOrder), now());
    }

    @Test
    void run_twoSidedBelowLowerBand_cancelsSellAndRefillsBuyTarget() {
        ListingAutoAccountConfig config = listingConfig(10L, "TWO_SIDED", 10, 20L, 20L, 100L, 20L, "DOWN", "UP");
        AutoOrder sellOrder = new AutoOrder(2L, 10L, "LST001", "SELL", 10L, 0L, BigDecimal.ZERO);
        prepare(config, 79L, 79L, new BigDecimal("100000.00"), new BigDecimal("100.00"), new BigDecimal("110.00"));
        when(orderReader.findOpenListingAutoOrders(config, "SELL")).thenReturn(List.of(sellOrder));
        when(orderExecutor.expireOrders(List.of(sellOrder), now())).thenReturn(1);
        acceptAllPlannedOrders();

        int processed = service.run(marketConfig(), sessionApproval());

        List<AutoMarketPlannedOrder> orders = capturePlannedOrders();
        assertThat(processed).isEqualTo(3);
        assertThat(orders).hasSize(2).allSatisfy(order -> {
            assertThat(order.side()).isEqualTo("BUY");
            assertThat(order.quantity()).isEqualTo(10L);
        });
        verify(orderExecutor).expireOrders(List.of(sellOrder), now());
    }

    @Test
    void run_twoSidedInsideBand_capsEachSideByWorstCaseInventoryLimit() {
        ListingAutoAccountConfig config = listingConfig(10L, "TWO_SIDED", 10, 20L, 20L, 100L, 20L, "DOWN", "UP");
        prepare(config, 110L, 110L, new BigDecimal("100000.00"), new BigDecimal("100.00"), new BigDecimal("110.00"));
        acceptAllPlannedOrders();

        int processed = service.run(marketConfig(), sessionApproval());

        List<AutoMarketPlannedOrder> orders = capturePlannedOrders();
        assertThat(processed).isEqualTo(3);
        assertThat(orders.stream().filter(order -> "BUY".equals(order.side())).mapToLong(AutoMarketPlannedOrder::quantity).sum())
                .isEqualTo(10L);
        assertThat(orders.stream().filter(order -> "SELL".equals(order.side())).mapToLong(AutoMarketPlannedOrder::quantity).sum())
                .isEqualTo(20L);
    }

    @Test
    void run_targetReduced_cancelsExcessOrderThenRefillsExactDeficit() {
        ListingAutoAccountConfig config = listingConfig(10L, "SELL_ONLY", 10, 0L, 10L, 0L, 0L, "DOWN", "UP");
        AutoOrder first = new AutoOrder(1L, 10L, "LST001", "SELL", 8L, 0L, BigDecimal.ZERO);
        AutoOrder second = new AutoOrder(2L, 10L, "LST001", "SELL", 7L, 0L, BigDecimal.ZERO);
        prepare(config, 100L, 100L, BigDecimal.ZERO, new BigDecimal("100.00"), new BigDecimal("110.00"));
        when(orderReader.findOpenListingAutoOrders(config, "SELL")).thenReturn(List.of(first, second));
        when(orderReader.getOpenOrderQuantity(10L, "LST001", "SELL")).thenReturn(7L);
        when(orderExecutor.expireOrders(List.of(first), now())).thenReturn(1);
        acceptAllPlannedOrders();

        int processed = service.run(marketConfig(), sessionApproval());

        assertThat(processed).isEqualTo(2);
        assertThat(capturePlannedOrders()).singleElement().satisfies(order -> assertThat(order.quantity()).isEqualTo(3L));
        verify(orderExecutor).expireOrders(List.of(first), now());
    }

    @Test
    void run_holdingAboveTarget_capsTotalSellOrdersAtRemainingTargetGap() {
        ListingAutoAccountConfig config = listingConfig(
                10L, "SELL_ONLY", 300_000, 0L, 300_000L, 2_000_000L, 0L, "DOWN", "UP"
        );
        prepare(config, 2_181_248L, 2_181_248L, BigDecimal.ZERO, new BigDecimal("100.00"), new BigDecimal("110.00"));
        acceptAllPlannedOrders();

        int processed = service.run(marketConfig(), sessionApproval());

        assertThat(processed).isEqualTo(1);
        assertThat(capturePlannedOrders()).singleElement().satisfies(order -> assertThat(order.quantity()).isEqualTo(181_248L));
    }

    @Test
    void run_holdingAlreadyAtDirectionalTarget_doesNotPlaceOrder() {
        ListingAutoAccountConfig config = listingConfig(
                10L, "SELL_ONLY", 300_000, 0L, 300_000L, 2_000_000L, 0L, "DOWN", "UP"
        );
        prepare(config, 2_000_000L, 2_000_000L, BigDecimal.ZERO, new BigDecimal("100.00"), new BigDecimal("110.00"));

        int processed = service.run(marketConfig(), sessionApproval());

        assertThat(processed).isZero();
        verify(orderExecutor, never()).placeOrdersWithOpenFenceHeld(anyList(), eq(sessionApproval()));
        verify(listingReader, never()).getAvailableQuantity(10L, "LST001");
    }

    @Test
    void run_buyOnlyWithZeroTargetHolding_doesNotTreatZeroAsUnlimited() {
        ListingAutoAccountConfig config = listingConfig(10L, "BUY_ONLY", 10, 10L, 0L, 0L, 0L, "DOWN", "UP");
        prepare(config, 0L, 0L, new BigDecimal("10000.00"), new BigDecimal("100.00"), new BigDecimal("110.00"));

        int processed = service.run(marketConfig(), sessionApproval());

        assertThat(processed).isZero();
        verify(orderExecutor, never()).placeOrdersWithOpenFenceHeld(anyList(), eq(sessionApproval()));
        verify(listingReader, never()).getCashBalance(10L);
    }

    @Test
    void run_holdingBelowTarget_capsBuyOrdersAtRemainingTargetGap() {
        ListingAutoAccountConfig config = listingConfig(
                10L, "BUY_ONLY", 300_000, 300_000L, 0L, 2_000_000L, 0L, "DOWN", "UP"
        );
        prepare(config, 1_800_000L, 0L, new BigDecimal("1000000000.00"), new BigDecimal("100.00"), new BigDecimal("110.00"));
        when(orderReader.getOpenOrderQuantity(10L, "LST001", "BUY")).thenReturn(50_000L);
        acceptAllPlannedOrders();

        int processed = service.run(marketConfig(), sessionApproval());

        assertThat(processed).isEqualTo(1);
        assertThat(capturePlannedOrders()).singleElement().satisfies(order -> assertThat(order.quantity()).isEqualTo(150_000L));
    }

    @Test
    void run_collectsExpiredOrdersAndExpiresThemInOneBatch() {
        ListingAutoAccountConfig firstConfig = listingConfig(10L, "NONE", 10, 0L, 0L, 0L, 0L, "DOWN", "UP");
        ListingAutoAccountConfig secondConfig = listingConfig(20L, "NONE", 10, 0L, 0L, 0L, 0L, "DOWN", "UP");
        AutoOrder firstOrder = new AutoOrder(1L, 10L, "LST001", "BUY", 1L, 0L, new BigDecimal("1000.00"));
        AutoOrder secondOrder = new AutoOrder(2L, 20L, "LST001", "SELL", 2L, 0L, BigDecimal.ZERO);
        when(listingReader.findEnabledListingAutoAccountConfigs(marketConfig())).thenReturn(List.of(firstConfig, secondConfig));
        when(orderReader.findExpiredListingAutoOrders(firstConfig, now().minusSeconds(90))).thenReturn(List.of(firstOrder));
        when(orderReader.findExpiredListingAutoOrders(secondConfig, now().minusSeconds(90))).thenReturn(List.of(secondOrder));
        when(orderExecutor.expireOrders(List.of(firstOrder, secondOrder), now())).thenReturn(2);
        when(orderExecutor.loadOrderBookState("LST001")).thenReturn(new AutoMarketOrderBookState(null, null, 0L, 0L));

        int processed = service.run(marketConfig(), sessionApproval());

        assertThat(processed).isEqualTo(2);
        verify(orderExecutor).expireOrders(List.of(firstOrder, secondOrder), now());
    }

    @Test
    void run_targetThirtyThousandWithThreeThousandMax_replenishesTenOrdersPerSideInOneBatch() {
        ListingAutoAccountConfig config = listingConfig(
                10L, "TWO_SIDED", 3_000, 30_000L, 30_000L, 200_000L, 30_000L, "DOWN", "UP"
        );
        prepare(config, 200_000L, 200_000L, new BigDecimal("1000000000.00"), new BigDecimal("100.00"), new BigDecimal("110.00"));
        acceptAllPlannedOrders();

        int processed = service.run(marketConfig(), sessionApproval());

        List<AutoMarketPlannedOrder> orders = capturePlannedOrders();
        assertThat(processed).isEqualTo(20);
        assertThat(orders).hasSize(20).allSatisfy(order -> assertThat(order.quantity()).isLessThanOrEqualTo(3_000L));
        assertThat(orders.stream().filter(order -> "BUY".equals(order.side())).mapToLong(AutoMarketPlannedOrder::quantity).sum())
                .isEqualTo(30_000L);
        assertThat(orders.stream().filter(order -> "SELL".equals(order.side())).mapToLong(AutoMarketPlannedOrder::quantity).sum())
                .isEqualTo(30_000L);
    }

    @Test
    void run_oversizedDeficit_limitsNewOrdersPerSide() {
        ListingAutoAccountConfig config = listingConfig(
                10L, "BUY_ONLY", 10, 1_000L, 0L, 1_000L, 0L, "DOWN", "UP"
        );
        prepare(config, 0L, 0L, new BigDecimal("1000000.00"), new BigDecimal("100.00"), new BigDecimal("110.00"));
        acceptAllPlannedOrders();

        int processed = service.run(marketConfig(), sessionApproval());

        List<AutoMarketPlannedOrder> orders = capturePlannedOrders();
        assertThat(processed).isEqualTo(ListingAutoAccountOrderService.MAX_NEW_ORDERS_PER_SIDE_PER_RUN);
        assertThat(orders).hasSize(ListingAutoAccountOrderService.MAX_NEW_ORDERS_PER_SIDE_PER_RUN);
        assertThat(orders).allSatisfy(order -> assertThat(order.quantity()).isEqualTo(10L));
    }

    @Test
    void run_buyUpDirection_canCrossMarketBestAsk() {
        ListingAutoAccountConfig config = listingConfig(10L, "BUY_ONLY", 10, 10L, 0L, 10L, 0L, "UP", "UP");
        prepare(config, 0L, 0L, new BigDecimal("10000.00"), new BigDecimal("100.00"), new BigDecimal("100.00"));
        acceptAllPlannedOrders();

        service.run(marketConfig(), sessionApproval());

        assertThat(capturePlannedOrders()).singleElement().satisfies(order -> {
            assertThat(order.price()).isGreaterThan(new BigDecimal("100.00"));
            assertThat(order.side()).isEqualTo("BUY");
        });
    }

    @Test
    void run_sellDownDirection_canCrossMarketBestBid() {
        ListingAutoAccountConfig config = listingConfig(10L, "SELL_ONLY", 10, 0L, 10L, 0L, 0L, "DOWN", "DOWN");
        prepare(config, 10L, 10L, BigDecimal.ZERO, new BigDecimal("100.00"), new BigDecimal("100.00"));
        acceptAllPlannedOrders();

        service.run(marketConfig(), sessionApproval());

        assertThat(capturePlannedOrders()).singleElement().satisfies(order -> {
            assertThat(order.price()).isLessThan(new BigDecimal("100.00"));
            assertThat(order.side()).isEqualTo("SELL");
        });
    }

    @Test
    void run_fragmentedOrders_useOneExternalQuoteReferenceWithoutSelfRatcheting() {
        ListingAutoAccountConfig config = new ListingAutoAccountConfig(
                "LST001",
                10L,
                "listing-user-10",
                "BUY_ONLY",
                1,
                90,
                1,
                3L,
                0L,
                3L,
                0L,
                "UP",
                "UP",
                BigDecimal.ONE,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                BigDecimal.valueOf(30)
        );
        prepare(config, 0L, 0L, new BigDecimal("10000.00"), new BigDecimal("100.00"), new BigDecimal("101.00"));
        acceptAllPlannedOrders();

        service.run(marketConfig(), sessionApproval());

        assertThat(capturePlannedOrders())
                .hasSize(3)
                .extracting(AutoMarketPlannedOrder::price)
                .containsOnly(new BigDecimal("101.00"));
    }

    private void prepare(
            ListingAutoAccountConfig config,
            long holdingQuantity,
            long availableQuantity,
            BigDecimal cashBalance,
            BigDecimal bestBid,
            BigDecimal bestAsk
    ) {
        when(listingReader.findEnabledListingAutoAccountConfigs(marketConfig())).thenReturn(List.of(config));
        when(listingReader.getHoldingQuantity(config.accountId(), config.symbol())).thenReturn(holdingQuantity);
        when(listingReader.getAvailableQuantity(config.accountId(), config.symbol())).thenReturn(availableQuantity);
        when(listingReader.getCashBalance(config.accountId())).thenReturn(cashBalance);
        when(orderExecutor.loadOrderBookState(config.symbol()))
                .thenReturn(new AutoMarketOrderBookState(bestBid, bestAsk, 0L, 0L));
    }

    private void acceptAllPlannedOrders() {
        when(orderExecutor.placeOrdersWithOpenFenceHeld(anyList(), eq(sessionApproval())))
                .thenAnswer(invocation -> {
                    List<AutoMarketPlannedOrder> orders = invocation.getArgument(0);
                    int buyCount = (int) orders.stream().filter(order -> "BUY".equals(order.side())).count();
                    int sellCount = orders.size() - buyCount;
                    return AutoParticipantOrderGenerationResult.execution(
                            orders.size(),
                            orders.size(),
                            buyCount,
                            sellCount,
                            0,
                            0
                    );
                });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<AutoMarketPlannedOrder> capturePlannedOrders() {
        ArgumentCaptor<List<AutoMarketPlannedOrder>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(orderExecutor).placeOrdersWithOpenFenceHeld(captor.capture(), eq(sessionApproval()));
        return captor.getValue();
    }

    private AutoMarketConfig marketConfig() {
        return new AutoMarketConfig(
                "LST001",
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

    private ListingAutoAccountConfig listingConfig(
            long accountId,
            String positionSide,
            int maxOrderQuantity,
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
                maxOrderQuantity,
                90,
                5,
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

    private LocalDateTime now() {
        return LocalDateTime.of(2026, 7, 3, 9, 0);
    }

    private MarketSessionFenceService.MarketSessionApproval sessionApproval() {
        return new MarketSessionFenceService.MarketSessionApproval(
                LocalDate.of(2026, 7, 3),
                Map.of("LST001", 1L),
                now()
        );
    }
}
