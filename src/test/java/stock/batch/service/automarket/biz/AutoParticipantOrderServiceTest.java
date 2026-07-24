package stock.batch.service.automarket.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import stock.batch.service.automarket.profile.DayTraderBehavior;
import stock.batch.service.automarket.profile.NoiseTraderBehavior;
import stock.batch.service.automarket.profile.ProfilePolicy;
import stock.batch.service.automarket.profile.ProfileDecision;
import stock.batch.service.automarket.profile.ProfileDecisionAction;
import stock.batch.service.automarket.profile.ProfileDecisionReason;
import stock.batch.service.automarket.profile.ProfileExecutionPolicy;
import stock.batch.service.automarket.profile.ProfileExitMode;
import stock.batch.service.automarket.profile.ProfileInventoryMode;
import stock.batch.service.automarket.profile.ProfilePricingMode;
import stock.batch.service.automarket.profile.ProfileSignalContext;
import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;
import stock.batch.service.batch.automarket.model.AutoParticipantBehaviorModelVersion;
import stock.batch.service.batch.automarket.model.AutoParticipantTradingSnapshot;
import stock.batch.service.batch.automarket.reader.AutoMarketReader;

class AutoParticipantOrderServiceTest {

    @Test
    void placeAutoOrders_v1DoesNotPopulateV2OnlyRuntimeTrackers() {
        AutoMarketReader reader = mock(AutoMarketReader.class);
        AutoMarketOrderExecutor executor = mock(AutoMarketOrderExecutor.class);
        AutoParticipantOrderPricing pricing = mock(AutoParticipantOrderPricing.class);
        AutoProfileBehaviorSupport behaviorSupport = mock(AutoProfileBehaviorSupport.class);
        AutoProfileBehavior behavior = mock(AutoProfileBehavior.class);
        RecentMarketActivityTracker recentMarketActivityTracker = mock(RecentMarketActivityTracker.class);
        AutoParticipantPositionActivityTracker positionActivityTracker =
                mock(AutoParticipantPositionActivityTracker.class);
        AutoParticipantOrderService service = new AutoParticipantOrderService(
                reader,
                executor,
                pricing,
                behaviorSupport
        );
        service.setRecentMarketActivityTracker(recentMarketActivityTracker);
        service.setPositionActivityTracker(positionActivityTracker);
        ReflectionTestUtils.setField(service, "maxOpenOrderQuantityMultiplier", 10);
        AutoMarketConfig config = config();
        LocalDateTime now = LocalDateTime.of(2027, 1, 18, 9, 0);
        AutoParticipantStrategy strategy = new AutoParticipantStrategy(
                "auto-v1",
                1L,
                5,
                AutoParticipantProfileType.NOISE_TRADER
        );
        ProfilePolicy policy = new NoiseTraderBehavior().defaultPolicy();
        when(reader.findLegacyTradingSnapshots(anyList(), eq(config.symbol()), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(executor.loadOrderBookState(config.symbol()))
                .thenReturn(new AutoMarketOrderBookState(null, null, 0, 0));
        when(behaviorSupport.behavior(strategy.profileType())).thenReturn(behavior);
        when(behaviorSupport.policy(Map.of(strategy.profileType(), policy), strategy.profileType()))
                .thenReturn(policy);
        when(behavior.activityLevel(strategy)).thenReturn(5);
        when(behavior.orderTtlSeconds(config.orderTtlSeconds(), policy.forLegacyExecution()))
                .thenReturn(config.orderTtlSeconds());
        when(behavior.orderCount(any())).thenReturn(0);
        when(executor.placeOrders(anyList()))
                .thenReturn(AutoParticipantOrderGenerationResult.execution(0, 0, 0, 0, 0, 0));

        service.placeAutoOrders(
                List.of(strategy),
                config,
                Map.of(strategy.profileType(), policy),
                0.0,
                now
        );

        verifyNoInteractions(recentMarketActivityTracker, positionActivityTracker);
        verify(reader, never()).findTradingSnapshots(anyList(), anyString(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void placeAutoOrders_v2MarketMakerPinsMinimumQuoteLifetimeAndPolicyIdentity() {
        AutoMarketReader reader = mock(AutoMarketReader.class);
        AutoMarketOrderExecutor executor = mock(AutoMarketOrderExecutor.class);
        AutoParticipantOrderPricing pricing = mock(AutoParticipantOrderPricing.class);
        AutoProfileBehaviorSupport behaviorSupport = mock(AutoProfileBehaviorSupport.class);
        AutoProfileBehavior behavior = mock(AutoProfileBehavior.class);
        AutoParticipantOrderService service = new AutoParticipantOrderService(reader, executor, pricing, behaviorSupport);
        ReflectionTestUtils.setField(service, "maxOpenOrderQuantityMultiplier", 10);
        AutoMarketConfig config = new AutoMarketConfig(
                "STOCK001", 100, 60, 1_000_000L, BigDecimal.ONE,
                new BigDecimal("100.00"), new BigDecimal("100.00"), null
        );
        LocalDateTime now = LocalDateTime.of(2027, 1, 18, 9, 0);
        AutoParticipantStrategy strategy = new AutoParticipantStrategy(
                "auto-mm", 1L, 5, AutoParticipantProfileType.MARKET_MAKER,
                null, null, null, AutoParticipantBehaviorModelVersion.V2,
                42L, now
        );
        ProfilePolicy policy = new NoiseTraderBehavior().defaultPolicy().withExecutionPolicy(
                new ProfileExecutionPolicy(
                        1.0, 1.0, ProfilePricingMode.MARKET_MAKING,
                        ProfileExitMode.SIGNAL_DRIVEN, ProfileInventoryMode.TARGET_ALLOCATION
                )
        );
        Map<AutoParticipantProfileType, ProfilePolicy> policies = Map.of(strategy.profileType(), policy);
        when(reader.findTradingSnapshots(anyList(), eq(config.symbol()), any(LocalDateTime.class), eq(now.toLocalDate())))
                .thenReturn(List.of(new AutoParticipantTradingSnapshot(
                        1L, new BigDecimal("10000.00"), 10L, new BigDecimal("100.00"),
                        BigDecimal.ZERO, 0L, 0L
                )));
        when(executor.loadOrderBookState(config.symbol()))
                .thenReturn(new AutoMarketOrderBookState(new BigDecimal("99.00"), new BigDecimal("101.00"), 0, 0));
        when(behaviorSupport.behavior(strategy.profileType())).thenReturn(behavior);
        when(behaviorSupport.policy(policies, strategy.profileType())).thenReturn(policy);
        when(behavior.activityLevel(strategy)).thenReturn(5);
        when(behavior.orderTtlSeconds(config.orderTtlSeconds(), policy)).thenReturn(36);
        when(behavior.decide(any())).thenReturn(new ProfileDecision(
                ProfileDecisionAction.BUY, ProfileDecisionReason.INVENTORY_BELOW_TARGET, 1, 1.0
        ));
        when(pricing.createAutoPrice(eq(config), eq(5), eq("BUY"), eq(policy), any(), any()))
                .thenReturn(new BigDecimal("100.00"));
        when(executor.placeOrders(anyList())).thenAnswer(invocation -> {
            List<AutoMarketPlannedOrder> orders = invocation.getArgument(0);
            return AutoParticipantOrderGenerationResult.execution(orders.size(), orders.size(), 0, 0, 0, 0);
        });

        service.placeAutoOrders(List.of(strategy), config, policies, 0.0, now);

        ArgumentCaptor<List<AutoMarketPlannedOrder>> captor = ArgumentCaptor.forClass(List.class);
        verify(executor).placeOrders(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(order -> {
            assertThat(order.expiresAt()).isEqualTo(now.plusSeconds(120));
            assertThat(order.profileType()).isEqualTo(AutoParticipantProfileType.MARKET_MAKER);
            assertThat(order.behaviorModelVersion()).isEqualTo(AutoParticipantBehaviorModelVersion.V2);
        });
    }

    @ParameterizedTest
    @MethodSource("holdReasons")
    void placeAutoOrders_v2HoldDecisionNeverCreatesExecutionIntent(ProfileDecisionReason holdReason) {
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
        LocalDateTime now = LocalDateTime.of(2027, 1, 18, 17, 0);
        AutoParticipantStrategy strategy = new AutoParticipantStrategy(
                "auto-hold",
                1L,
                5,
                AutoParticipantProfileType.DAY_TRADER,
                null,
                null,
                null,
                AutoParticipantBehaviorModelVersion.V2,
                42L,
                now
        );
        ProfilePolicy policy = new DayTraderBehavior().defaultPolicy();
        Map<AutoParticipantProfileType, ProfilePolicy> policies = Map.of(
                strategy.profileType(),
                policy
        );
        when(reader.findTradingSnapshots(anyList(), eq(config.symbol()), any(), eq(now.toLocalDate())))
                .thenReturn(List.of());
        when(executor.loadOrderBookState(config.symbol()))
                .thenReturn(new AutoMarketOrderBookState(null, null, 0, 0));
        when(behaviorSupport.behavior(strategy.profileType())).thenReturn(behavior);
        when(behaviorSupport.policy(policies, strategy.profileType())).thenReturn(policy);
        when(behavior.activityLevel(strategy)).thenReturn(5);
        when(behavior.orderTtlSeconds(config.orderTtlSeconds(), policy))
                .thenReturn(config.orderTtlSeconds());
        when(behavior.decide(any())).thenReturn(ProfileDecision.hold(holdReason, 1.0));
        when(executor.placeOrders(anyList()))
                .thenReturn(AutoParticipantOrderGenerationResult.execution(0, 0, 0, 0, 0, 0));

        AutoParticipantOrderGenerationResult result = service.placeAutoOrders(
                List.of(strategy),
                config,
                policies,
                0.0,
                now
        );

        assertThat(result.generatedOrderCount()).isZero();
        verifyNoInteractions(pricing);
    }

    @Test
    void scaleProfileQuantity_multiplierScalesContinuouslyWithinHardLimit() {
        assertThat(AutoParticipantOrderService.scaleProfileQuantity(50, 0.45, 100)).isEqualTo(23);
        assertThat(AutoParticipantOrderService.scaleProfileQuantity(50, 1.20, 100)).isEqualTo(60);
        assertThat(AutoParticipantOrderService.scaleProfileQuantity(80, 1.80, 100)).isEqualTo(100);
    }

    @Test
    void scaleProfileQuantity_zeroMultiplier_disablesQuantity() {
        assertThat(AutoParticipantOrderService.scaleProfileQuantity(50, 0, 100)).isZero();
    }

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
        when(reader.findLegacyTradingSnapshots(anyList(), eq(config.symbol()), any(LocalDateTime.class)))
                .thenReturn(snapshots);
        when(executor.loadOrderBookState(config.symbol()))
                .thenReturn(new AutoMarketOrderBookState(null, null, 0, 0));
        when(behaviorSupport.behavior(AutoParticipantProfileType.NOISE_TRADER)).thenReturn(behavior);
        when(behaviorSupport.policy(policies, AutoParticipantProfileType.NOISE_TRADER)).thenReturn(policy);
        when(behavior.activityLevel(strategy)).thenReturn(5);
        when(behavior.orderCount(any())).thenReturn(1);
        when(behavior.chooseSide(any())).thenReturn(side);
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
        AutoMarketConfig config = config(1);
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
        when(reader.findLegacyTradingSnapshots(anyList(), eq(config.symbol()), any(LocalDateTime.class)))
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
        when(behavior.activityLevel(strategy)).thenReturn(5);
        when(behavior.orderCount(any())).thenReturn(2);
        List<String> observedStates = new ArrayList<>();
        when(behavior.chooseSide(any())).thenAnswer(invocation -> {
            ProfileSignalContext context = invocation.getArgument(0);
            observedStates.add(context.cashBalance().toPlainString() + ":" + context.herdPressure());
            return context.cashBalance().compareTo(context.config().currentPrice()) >= 0 ? "BUY" : null;
        });
        when(pricing.createAutoPrice(eq(config), eq(5), eq("BUY"), eq(policy), any()))
                .thenReturn(new BigDecimal("100.00"));
        when(executor.placeOrders(anyList())).thenAnswer(invocation -> {
            List<AutoMarketPlannedOrder> orders = invocation.getArgument(0);
            return AutoParticipantOrderGenerationResult.execution(orders.size(), orders.size(), 0, 0, 0, 0);
        });

        AutoParticipantOrderGenerationResult result = service.placeAutoOrders(
                List.of(strategy),
                config,
                policies,
                0.0,
                LocalDateTime.of(2026, 7, 19, 9, 0)
        );

        assertThat(observedStates).containsExactly("250.00:0.0", "150.00:1.0");
        assertThat(result.decisionCount()).isEqualTo(1);
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
        verify(reader, times(1))
                .findLegacyTradingSnapshots(anyList(), eq(config.symbol()), any(LocalDateTime.class));
        verify(reader, never())
                .findTradingSnapshots(anyList(), eq(config.symbol()), any(LocalDateTime.class), any());
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
        when(reader.findLegacyTradingSnapshots(anyList(), eq(config.symbol()), any(LocalDateTime.class)))
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
        when(behavior.activityLevel(strategy)).thenReturn(5);
        when(behavior.orderCount(any())).thenReturn(1);
        when(behavior.chooseSide(any())).thenReturn("SELL");
        when(pricing.createAutoPrice(eq(config), eq(5), eq("SELL"), eq(policy), any()))
                .thenReturn(new BigDecimal("100.00"));
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
                Arguments.of(
                        "BUY",
                        List.of(new AutoParticipantTradingSnapshot(
                                1L,
                                new BigDecimal("100000.00"),
                                0,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                0,
                                0
                        )),
                        BigDecimal.ZERO,
                        AutoMarketOrderDropReason.INVALID_PRICE
                ),
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

    private static Stream<ProfileDecisionReason> holdReasons() {
        return Stream.of(ProfileDecisionReason.values());
    }

    private static AutoMarketConfig config() {
        return config(100);
    }

    private static AutoMarketConfig config(int maxOrderQuantity) {
        return new AutoMarketConfig(
                "STOCK001",
                maxOrderQuantity,
                1200,
                1_000_000L,
                BigDecimal.ONE,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                null
        );
    }

}
