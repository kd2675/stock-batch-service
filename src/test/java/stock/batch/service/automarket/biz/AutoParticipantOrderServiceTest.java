package stock.batch.service.automarket.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import stock.batch.service.automarket.profile.AutoProfileBehavior;
import stock.batch.service.automarket.profile.NoiseTraderBehavior;
import stock.batch.service.automarket.profile.ProfilePolicy;
import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.model.AutoParticipantProfileType;
import stock.batch.service.batch.automarket.model.AutoParticipantStrategy;
import stock.batch.service.batch.automarket.model.AutoParticipantTradingSnapshot;
import stock.batch.service.batch.automarket.reader.AutoMarketReader;
import stock.batch.service.simulation.SimulationClockService;
import web.common.core.simulation.SimulationClockSnapshot;

class AutoParticipantOrderServiceTest {

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
        SimulationClockService clockService = mock(SimulationClockService.class);
        AutoProfileBehavior behavior = mock(AutoProfileBehavior.class);
        AutoParticipantOrderService service = new AutoParticipantOrderService(
                reader,
                executor,
                pricing,
                behaviorSupport,
                clockService
        );
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
        when(clockService.currentSnapshot()).thenReturn(clock());
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
        when(pricing.avoidSelfCross(
                eq(config),
                anyString(),
                eq(orderPrice),
                nullable(BigDecimal.class),
                nullable(BigDecimal.class)
        )).thenReturn(orderPrice);
        when(executor.placeOrders(anyList())).thenAnswer(invocation -> {
            List<AutoMarketPlannedOrder> orders = invocation.getArgument(0);
            return AutoParticipantOrderGenerationResult.execution(orders.size(), orders.size(), 0, 0, 0, 0);
        });

        AutoParticipantOrderGenerationResult result = service.placeAutoOrders(
                List.of(strategy),
                config,
                policies,
                0.0
        );

        assertThat(result.droppedOrderCount(expectedReason)).isEqualTo(1);
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
                                null,
                                null,
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

    private static SimulationClockSnapshot clock() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 14, 9, 0);
        return new SimulationClockSnapshot(
                LocalDate.of(2026, 7, 14),
                now,
                LocalDateTime.of(2026, 7, 14, 0, 0),
                now,
                LocalDateTime.of(2026, 7, 14, 0, 0),
                7200,
                true,
                false,
                0,
                now,
                now
        );
    }
}
