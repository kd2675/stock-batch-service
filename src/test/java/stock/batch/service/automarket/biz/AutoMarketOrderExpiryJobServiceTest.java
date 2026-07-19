package stock.batch.service.automarket.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import stock.batch.service.batch.automarket.model.AutoMarketConfig;
import stock.batch.service.batch.automarket.reader.AutoMarketReader;
import stock.batch.service.execution.lock.OrderBookSymbolLock;
import stock.batch.service.marketclose.biz.MarketSessionFenceService;
import stock.batch.service.simulation.SimulationClockService;
import stock.batch.service.simulation.SimulationMarketSessionService;
import web.common.core.simulation.SimulationClockSnapshot;
import web.common.core.simulation.SimulationMarketSession;

class AutoMarketOrderExpiryJobServiceTest {

    @Test
    void expireAutoMarketOrders_deadlockDuringSymbolTransaction_retriesAndCompletes() {
        AutoMarketReader autoMarketReader = mock(AutoMarketReader.class);
        AutoMarketOrderExpiryService autoMarketOrderExpiryService = mock(AutoMarketOrderExpiryService.class);
        AutoProfileBehaviorSupport autoProfileBehaviorSupport = mock(AutoProfileBehaviorSupport.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        SimulationMarketSessionService simulationMarketSessionService = mock(SimulationMarketSessionService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        MarketSessionFenceService marketSessionFenceService = mock(MarketSessionFenceService.class);
        OrderBookSymbolLock orderBookSymbolLock = symbol -> Optional.of(() -> {
        });
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        AutoMarketConfig config = new AutoMarketConfig(
                "STOCK001",
                100,
                90,
                100000L,
                new BigDecimal("100.00"),
                new BigDecimal("70000.00"),
                new BigDecimal("70000.00"),
                null
        );
        AutoMarketOrderExpiryJobService service = new AutoMarketOrderExpiryJobService(
                autoMarketReader,
                autoMarketOrderExpiryService,
                autoProfileBehaviorSupport,
                simulationClockService,
                simulationMarketSessionService,
                transactionTemplate,
                orderBookSymbolLock,
                marketSessionFenceService,
                new SimpleMeterRegistry()
        );
        ReflectionTestUtils.setField(service, "deadlockRetryMaxAttempts", 2);
        ReflectionTestUtils.setField(service, "deadlockRetryBackoffMs", 0L);
        when(simulationClockService.currentSnapshot()).thenReturn(new SimulationClockSnapshot(
                LocalDate.of(2026, 7, 3),
                LocalDateTime.of(2026, 7, 3, 9, 0),
                LocalDateTime.of(2026, 7, 3, 0, 0),
                LocalDateTime.of(2026, 7, 3, 9, 0),
                LocalDateTime.of(2026, 7, 3, 0, 0),
                7200,
                true,
                false,
                0,
                LocalDateTime.of(2026, 7, 3, 9, 0),
                LocalDateTime.of(2026, 7, 3, 9, 0)
        ));
        when(simulationMarketSessionService.sessionAt(any(LocalDateTime.class)))
                .thenReturn(SimulationMarketSession.REGULAR);
        when(autoMarketReader.findEnabledMaintenanceConfigs()).thenReturn(List.of(config));
        when(autoMarketReader.findParticipantProfileConfigs()).thenReturn(List.of());
        when(autoProfileBehaviorSupport.policiesWithOverrides(List.of())).thenReturn(Map.of());
        when(marketSessionFenceService.lockOpenOrderBookFences(List.of("STOCK001")))
                .thenReturn(Optional.of(openApproval("STOCK001")));
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Integer> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        });
        when(autoMarketOrderExpiryService.expireOldAutoOrders(
                org.mockito.ArgumentMatchers.eq(config),
                org.mockito.ArgumentMatchers.eq(Map.of()),
                any(LocalDateTime.class)
        ))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenReturn(3);

        int expiredCount = service.expireAutoMarketOrders();

        assertThat(expiredCount).isEqualTo(3);
        verify(autoMarketOrderExpiryService, org.mockito.Mockito.times(2)).expireOldAutoOrders(
                org.mockito.ArgumentMatchers.eq(config),
                org.mockito.ArgumentMatchers.eq(Map.of()),
                any(LocalDateTime.class)
        );
    }

    @Test
    void expireAutoMarketOrders_deadlockRetryExhausted_skipsSymbolWithoutFailingJob() {
        AutoMarketReader autoMarketReader = mock(AutoMarketReader.class);
        AutoMarketOrderExpiryService autoMarketOrderExpiryService = mock(AutoMarketOrderExpiryService.class);
        AutoProfileBehaviorSupport autoProfileBehaviorSupport = mock(AutoProfileBehaviorSupport.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        SimulationMarketSessionService simulationMarketSessionService = mock(SimulationMarketSessionService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        MarketSessionFenceService marketSessionFenceService = mock(MarketSessionFenceService.class);
        OrderBookSymbolLock orderBookSymbolLock = symbol -> Optional.of(() -> {
        });
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        AutoMarketConfig config = new AutoMarketConfig(
                "STOCK001",
                100,
                90,
                100000L,
                new BigDecimal("100.00"),
                new BigDecimal("70000.00"),
                new BigDecimal("70000.00"),
                null
        );
        AutoMarketOrderExpiryJobService service = new AutoMarketOrderExpiryJobService(
                autoMarketReader,
                autoMarketOrderExpiryService,
                autoProfileBehaviorSupport,
                simulationClockService,
                simulationMarketSessionService,
                transactionTemplate,
                orderBookSymbolLock,
                marketSessionFenceService,
                new SimpleMeterRegistry()
        );
        ReflectionTestUtils.setField(service, "deadlockRetryMaxAttempts", 2);
        ReflectionTestUtils.setField(service, "deadlockRetryBackoffMs", 0L);
        when(simulationClockService.currentSnapshot()).thenReturn(new SimulationClockSnapshot(
                LocalDate.of(2026, 7, 3),
                LocalDateTime.of(2026, 7, 3, 9, 0),
                LocalDateTime.of(2026, 7, 3, 0, 0),
                LocalDateTime.of(2026, 7, 3, 9, 0),
                LocalDateTime.of(2026, 7, 3, 0, 0),
                7200,
                true,
                false,
                0,
                LocalDateTime.of(2026, 7, 3, 9, 0),
                LocalDateTime.of(2026, 7, 3, 9, 0)
        ));
        when(simulationMarketSessionService.sessionAt(any(LocalDateTime.class)))
                .thenReturn(SimulationMarketSession.REGULAR);
        when(autoMarketReader.findEnabledMaintenanceConfigs()).thenReturn(List.of(config));
        when(autoMarketReader.findParticipantProfileConfigs()).thenReturn(List.of());
        when(autoProfileBehaviorSupport.policiesWithOverrides(List.of())).thenReturn(Map.of());
        when(marketSessionFenceService.lockOpenOrderBookFences(List.of("STOCK001")))
                .thenReturn(Optional.of(openApproval("STOCK001")));
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Integer> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        });
        when(autoMarketOrderExpiryService.expireOldAutoOrders(
                org.mockito.ArgumentMatchers.eq(config),
                org.mockito.ArgumentMatchers.eq(Map.of()),
                any(LocalDateTime.class)
        ))
                .thenThrow(new CannotAcquireLockException("deadlock"));

        int expiredCount = service.expireAutoMarketOrders();

        assertThat(expiredCount).isZero();
        verify(autoMarketOrderExpiryService, org.mockito.Mockito.times(2)).expireOldAutoOrders(
                org.mockito.ArgumentMatchers.eq(config),
                org.mockito.ArgumentMatchers.eq(Map.of()),
                any(LocalDateTime.class)
        );
    }

    @Test
    void expireAutoMarketOrders_symbolLockBusy_skipsSymbol() {
        AutoMarketReader autoMarketReader = mock(AutoMarketReader.class);
        AutoMarketOrderExpiryService autoMarketOrderExpiryService = mock(AutoMarketOrderExpiryService.class);
        AutoProfileBehaviorSupport autoProfileBehaviorSupport = mock(AutoProfileBehaviorSupport.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        SimulationMarketSessionService simulationMarketSessionService = mock(SimulationMarketSessionService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        MarketSessionFenceService marketSessionFenceService = mock(MarketSessionFenceService.class);
        OrderBookSymbolLock orderBookSymbolLock = symbol -> Optional.empty();
        AutoMarketConfig config = new AutoMarketConfig(
                "STOCK001",
                100,
                90,
                100000L,
                new BigDecimal("100.00"),
                new BigDecimal("70000.00"),
                new BigDecimal("70000.00"),
                null
        );
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AutoMarketOrderExpiryJobService service = new AutoMarketOrderExpiryJobService(
                autoMarketReader,
                autoMarketOrderExpiryService,
                autoProfileBehaviorSupport,
                simulationClockService,
                simulationMarketSessionService,
                transactionTemplate,
                orderBookSymbolLock,
                marketSessionFenceService,
                meterRegistry
        );
        when(simulationClockService.currentSnapshot()).thenReturn(new SimulationClockSnapshot(
                LocalDate.of(2026, 7, 3),
                LocalDateTime.of(2026, 7, 3, 9, 0),
                LocalDateTime.of(2026, 7, 3, 0, 0),
                LocalDateTime.of(2026, 7, 3, 9, 0),
                LocalDateTime.of(2026, 7, 3, 0, 0),
                7200,
                true,
                false,
                0,
                LocalDateTime.of(2026, 7, 3, 9, 0),
                LocalDateTime.of(2026, 7, 3, 9, 0)
        ));
        when(simulationMarketSessionService.sessionAt(any(LocalDateTime.class)))
                .thenReturn(SimulationMarketSession.REGULAR);
        when(autoMarketReader.findEnabledMaintenanceConfigs()).thenReturn(List.of(config));
        when(autoMarketReader.findParticipantProfileConfigs()).thenReturn(List.of());
        when(autoProfileBehaviorSupport.policiesWithOverrides(List.of())).thenReturn(Map.of());

        int expiredCount = service.expireAutoMarketOrders();

        assertThat(expiredCount).isZero();
        assertThat(meterRegistry.counter("stock.auto.market.order.expiry.symbol.lock.skips").count()).isEqualTo(1.0);
        verify(autoMarketOrderExpiryService, never()).expireOldAutoOrders(any(), any(), any());
    }

    @Test
    void expireAutoMarketOrders_pausedSimulationClockSkipsExpiryEvenDuringRegularSession() {
        AutoMarketReader autoMarketReader = mock(AutoMarketReader.class);
        AutoMarketOrderExpiryService autoMarketOrderExpiryService = mock(AutoMarketOrderExpiryService.class);
        AutoProfileBehaviorSupport autoProfileBehaviorSupport = mock(AutoProfileBehaviorSupport.class);
        SimulationClockService simulationClockService = mock(SimulationClockService.class);
        SimulationMarketSessionService simulationMarketSessionService = mock(SimulationMarketSessionService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        MarketSessionFenceService marketSessionFenceService = mock(MarketSessionFenceService.class);
        OrderBookSymbolLock orderBookSymbolLock = symbol -> Optional.of(() -> {
        });
        AutoMarketOrderExpiryJobService service = new AutoMarketOrderExpiryJobService(
                autoMarketReader,
                autoMarketOrderExpiryService,
                autoProfileBehaviorSupport,
                simulationClockService,
                simulationMarketSessionService,
                transactionTemplate,
                orderBookSymbolLock,
                marketSessionFenceService,
                new SimpleMeterRegistry()
        );
        when(simulationClockService.currentSnapshot()).thenReturn(new SimulationClockSnapshot(
                LocalDate.of(2026, 7, 3),
                LocalDateTime.of(2026, 7, 3, 9, 0),
                LocalDateTime.of(2026, 7, 3, 0, 0),
                LocalDateTime.of(2026, 7, 3, 9, 0),
                LocalDateTime.of(2026, 7, 3, 0, 0),
                7200,
                false,
                false,
                0,
                null,
                null
        ));
        when(simulationMarketSessionService.sessionAt(any(LocalDateTime.class)))
                .thenReturn(SimulationMarketSession.REGULAR);

        int expiredCount = service.expireAutoMarketOrders();

        assertThat(expiredCount).isZero();
        verify(autoMarketReader, never()).findEnabledMaintenanceConfigs();
        verify(autoMarketOrderExpiryService, never()).expireOldAutoOrders(any(), any(), any());
    }

    private MarketSessionFenceService.MarketSessionApproval openApproval(String symbol) {
        LocalDateTime now = LocalDateTime.of(2026, 7, 3, 9, 0);
        return new MarketSessionFenceService.MarketSessionApproval(
                now.toLocalDate(),
                Map.of(symbol, 1L),
                now
        );
    }
}
