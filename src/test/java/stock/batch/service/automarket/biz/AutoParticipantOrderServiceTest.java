package stock.batch.service.automarket.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import stock.batch.service.automarket.profile.AutoProfileBehavior;
import stock.batch.service.automarket.profile.NoiseTraderBehavior;
import stock.batch.service.automarket.profile.ProfilePolicy;
import stock.batch.service.automarket.profile.ProfileSignalContext;
import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;
import stock.batch.service.batch.automarket.model.AutoParticipantTradingSnapshot;
import stock.batch.service.batch.automarket.reader.AutoMarketReader;

class AutoParticipantOrderServiceTest {

    @Test
    void validateVolumeConfiguration_openOrderMultiplierAboveSafeMaximum_rejectsStartup() {
        AutoParticipantOrderService service = new AutoParticipantOrderService(
                mock(AutoMarketReader.class),
                mock(AutoMarketOrderExecutor.class),
                mock(AutoParticipantOrderPricing.class),
                mock(AutoProfileBehaviorSupport.class)
        );
        ReflectionTestUtils.setField(service, "maxOpenOrderQuantityMultiplier", 101);

        assertThatThrownBy(service::validateVolumeConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-open-order-quantity-multiplier must be between 1 and 100");
    }

    @ParameterizedTest
    @MethodSource("planningDropCases")
    void placeAutoOrders_unacceptedDecision_recordsExactPlanningDropReason(
            String side,
            List<AutoParticipantTradingSnapshot> snapshots,
            BigDecimal orderPrice,
            AutoMarketOrderDropReason expectedReason
    ) {
        AutoMarketReader reader = mock(AutoMarketReader.class);
        AutoMarketOrderExecutor executor = mock(AutoMarketOrderExecutor.class);
        AutoParticipantOrderPricing pricing = mock(AutoParticipantOrderPricing.class);
        AutoProfileBehaviorSupport behaviorSupport = mock(AutoProfileBehaviorSupport.class);
        AutoProfileBehavior behavior = mock(AutoProfileBehavior.class);
        AutoParticipantOrderService service = new AutoParticipantOrderService(
                reader,
                executor,
                pricing,
                behaviorSupport
        );
        ReflectionTestUtils.setField(service, "maxOpenOrderQuantityMultiplier", 1);
        AutoMarketConfig config = config();
        AutoParticipantStrategy strategy = new AutoParticipantStrategy(
                "auto-001",
                1L,
                5,
                AutoParticipantProfileType.NOISE_TRADER
        );
        ProfilePolicy policy = new NoiseTraderBehavior().defaultPolicy();
        Map<AutoParticipantProfileType, ProfilePolicy> policies = Map.of(
                AutoParticipantProfileType.NOISE_TRADER,
                policy
        );
        when(reader.findTradingSnapshots(anyList(), eq(config.symbol()), any(LocalDateTime.class)))
                .thenReturn(snapshots);
        when(executor.loadOrderBookState(config.symbol()))
                .thenReturn(new AutoMarketOrderBookState(null, null, 0, 0));
        when(behaviorSupport.behavior(AutoParticipantProfileType.NOISE_TRADER)).thenReturn(behavior);
        when(behaviorSupport.policy(policies, AutoParticipantProfileType.NOISE_TRADER)).thenReturn(policy);
        when(behavior.effectiveIntensity(strategy, config, policy)).thenReturn(5);
        when(behavior.orderCount(any())).thenReturn(1);
        when(behavior.chooseSide(any())).thenReturn(side);
        when(behavior.quantityUpperBound(anyInt(), eq(policy))).thenReturn(1);
        when(pricing.createAutoPrice(eq(config), eq(5), anyString(), eq(policy), any()))
                .thenReturn(orderPrice);
        when(executor.placeOrders(anyList())).thenAnswer(invocation -> {
            List<AutoMarketPlannedOrder> orders = invocation.getArgument(0);
            return AutoParticipantOrderGenerationResult.execution(orders.size(), orders.size(), 0, 0, 0, 0);
        });

        AutoParticipantOrderGenerationResult result = service.placeAutoOrders(
                List.of(strategy),
                config,
                policies,
                0.0,
                LocalDateTime.of(2026, 7, 14, 9, 0)
        );

        assertThat(result.droppedOrderCount(expectedReason)).isEqualTo(1);
    }

    @Test
    void placeAutoOrders_multiplePlannedOrders_recalculatesQuantitiesWithoutMovingReferenceQuotes() {
        AutoMarketReader reader = mock(AutoMarketReader.class);
        AutoMarketOrderExecutor executor = mock(AutoMarketOrderExecutor.class);
        AutoParticipantOrderPricing pricing = mock(AutoParticipantOrderPricing.class);
        AutoProfileBehaviorSupport behaviorSupport = mock(AutoProfileBehaviorSupport.class);
        AutoProfileBehavior behavior = mock(AutoProfileBehavior.class);
        AutoParticipantOrderService service = new AutoParticipantOrderService(
                reader,
                executor,
                pricing,
                behaviorSupport
        );
        ReflectionTestUtils.setField(service, "maxOpenOrderQuantityMultiplier", 10);
        AutoMarketConfig config = config();
        AutoParticipantStrategy strategy = new AutoParticipantStrategy(
                "auto-001",
                1L,
                5,
                AutoParticipantProfileType.NOISE_TRADER
        );
        ProfilePolicy policy = new NoiseTraderBehavior().defaultPolicy();
        Map<AutoParticipantProfileType, ProfilePolicy> policies = Map.of(
                AutoParticipantProfileType.NOISE_TRADER,
                policy
        );
        when(reader.findTradingSnapshots(anyList(), eq(config.symbol()), any(LocalDateTime.class)))
                .thenReturn(List.of(new AutoParticipantTradingSnapshot(
                        1L,
                        new BigDecimal("250.00"),
                        0,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        0,
                        0
                )));
        when(executor.loadOrderBookState(config.symbol()))
                .thenReturn(new AutoMarketOrderBookState(
                        new BigDecimal("99.00"),
                        new BigDecimal("101.00"),
                        0,
                        0
                ));
        when(behaviorSupport.behavior(AutoParticipantProfileType.NOISE_TRADER)).thenReturn(behavior);
        when(behaviorSupport.policy(policies, AutoParticipantProfileType.NOISE_TRADER)).thenReturn(policy);
        when(behavior.effectiveIntensity(strategy, config, policy)).thenReturn(5);
        when(behavior.orderCount(any())).thenReturn(2);
        List<String> observedStates = new ArrayList<>();
        when(behavior.chooseSide(any())).thenAnswer(invocation -> {
            ProfileSignalContext context = invocation.getArgument(0);
            observedStates.add(context.cashBalance().toPlainString() + ":" + context.herdPressure());
            return context.canBuyOne() ? "BUY" : null;
        });
        when(behavior.quantityUpperBound(anyInt(), eq(policy))).thenReturn(1);
        when(pricing.createAutoPrice(eq(config), eq(5), eq("BUY"), eq(policy), any()))
                .thenReturn(new BigDecimal("100.00"));
        when(executor.placeOrders(anyList())).thenAnswer(invocation -> {
            List<AutoMarketPlannedOrder> orders = invocation.getArgument(0);
            return AutoParticipantOrderGenerationResult.execution(orders.size(), orders.size(), 0, 0, 0, 0);
        });

        service.placeAutoOrders(
                List.of(strategy),
                config,
                policies,
                0.0,
                LocalDateTime.of(2026, 7, 19, 9, 0)
        );

        assertThat(observedStates).containsExactly("250.00:0.0", "150.00:1.0");
        ArgumentCaptor<AutoMarketOrderBookState> priceReferenceCaptor =
                ArgumentCaptor.forClass(AutoMarketOrderBookState.class);
        verify(pricing, times(2)).createAutoPrice(
                eq(config),
                eq(5),
                eq("BUY"),
                eq(policy),
                priceReferenceCaptor.capture()
        );
        assertThat(priceReferenceCaptor.getAllValues()).allSatisfy(reference -> {
            assertThat(reference.bestBid()).isEqualByComparingTo("99.00");
            assertThat(reference.bestAsk()).isEqualByComparingTo("101.00");
        });
        verify(reader, times(1)).findTradingSnapshots(anyList(), eq(config.symbol()), any(LocalDateTime.class));
        verify(executor, times(1)).loadOrderBookState(config.symbol());
    }

    @Test
    @SuppressWarnings("unchecked")
    void placeAutoOrders_crossingPrice_isPlacedWithoutAdjustment() {
        AutoMarketReader reader = mock(AutoMarketReader.class);
        AutoMarketOrderExecutor executor = mock(AutoMarketOrderExecutor.class);
        AutoParticipantOrderPricing pricing = mock(AutoParticipantOrderPricing.class);
        AutoProfileBehaviorSupport behaviorSupport = mock(AutoProfileBehaviorSupport.class);
        AutoProfileBehavior behavior = mock(AutoProfileBehavior.class);
        AutoParticipantOrderService service = new AutoParticipantOrderService(
                reader,
                executor,
                pricing,
                behaviorSupport
        );
        ReflectionTestUtils.setField(service, "maxOpenOrderQuantityMultiplier", 10);
        AutoMarketConfig config = config();
        AutoParticipantStrategy strategy = new AutoParticipantStrategy(
                "auto-001",
                1L,
                5,
                AutoParticipantProfileType.NOISE_TRADER
        );
        ProfilePolicy policy = new NoiseTraderBehavior().defaultPolicy();
        Map<AutoParticipantProfileType, ProfilePolicy> policies = Map.of(
                AutoParticipantProfileType.NOISE_TRADER,
                policy
        );
        when(reader.findTradingSnapshots(anyList(), eq(config.symbol()), any(LocalDateTime.class)))
                .thenReturn(List.of(new AutoParticipantTradingSnapshot(
                        1L,
                        BigDecimal.ZERO,
                        10,
                        new BigDecimal("90.00"),
                        BigDecimal.ZERO,
                        0,
                        0
                )));
        when(executor.loadOrderBookState(config.symbol()))
                .thenReturn(new AutoMarketOrderBookState(null, null, 0, 0));
        when(behaviorSupport.behavior(AutoParticipantProfileType.NOISE_TRADER)).thenReturn(behavior);
        when(behaviorSupport.policy(policies, AutoParticipantProfileType.NOISE_TRADER)).thenReturn(policy);
        when(behavior.effectiveIntensity(strategy, config, policy)).thenReturn(5);
        when(behavior.orderCount(any())).thenReturn(1);
        when(behavior.chooseSide(any())).thenReturn("SELL");
        when(pricing.createAutoPrice(eq(config), eq(5), eq("SELL"), eq(policy), any()))
                .thenReturn(new BigDecimal("100.00"));
        when(behavior.quantityUpperBound(anyInt(), eq(policy))).thenReturn(1);
        when(executor.placeOrders(anyList())).thenAnswer(invocation -> {
            List<AutoMarketPlannedOrder> orders = invocation.getArgument(0);
            return AutoParticipantOrderGenerationResult.execution(orders.size(), orders.size(), 0, 1, 0, 0);
        });

        AutoParticipantOrderGenerationResult result = service.placeAutoOrders(
                List.of(strategy),
                config,
                policies,
                0.0,
                LocalDateTime.of(2026, 7, 19, 9, 0)
        );

        ArgumentCaptor<List<AutoMarketPlannedOrder>> captor = ArgumentCaptor.forClass(List.class);
        verify(executor).placeOrders(captor.capture());
        assertThat(result.generatedOrderCount()).isEqualTo(1);
        assertThat(captor.getValue()).singleElement().satisfies(order -> {
            assertThat(order.side()).isEqualTo("SELL");
            assertThat(order.price()).isEqualByComparingTo("100.00");
        });
    }

    private static Stream<Arguments> planningDropCases() {
        BigDecimal validPrice = new BigDecimal("100.00");
        return Stream.of(
                Arguments.of(null, List.of(), validPrice, AutoMarketOrderDropReason.SIDE_NOT_SELECTED),
                Arguments.of("BUY", List.of(), BigDecimal.ZERO, AutoMarketOrderDropReason.INVALID_PRICE),
                Arguments.of("BUY", List.of(), validPrice, AutoMarketOrderDropReason.INSUFFICIENT_CASH),
                Arguments.of("SELL", List.of(), validPrice, AutoMarketOrderDropReason.INSUFFICIENT_HOLDING),
                Arguments.of(
                        "BUY",
                        List.of(new AutoParticipantTradingSnapshot(
                                1L,
                                new BigDecimal("100000.00"),
                                10,
                                new BigDecimal("90.00"),
                                BigDecimal.ZERO,
                                100,
                                0
                        )),
                        validPrice,
                        AutoMarketOrderDropReason.OPEN_QUANTITY_LIMIT
                )
        );
    }

    private static AutoMarketConfig config() {
        return new AutoMarketConfig(
                "STOCK001",
                100,
                1200,
                1_000_000L,
                BigDecimal.ONE,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                null
        );
    }

}
